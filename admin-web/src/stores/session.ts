// 管理端会话状态（Pinia）。会话 Cookie（A_CLINIC_SESSION，HttpOnly）只由浏览器携带，
// 本 store 绝不读取 document.cookie、不发送患者 Bearer、不保存员工会话令牌或密码。
//
// 鉴权模型：
//  - 登录 / 改密响应只回 must_change_password + csrf_token；身份（姓名/角色/门店）以 GET /auth/me 为准。
//  - CSRF 仅保存在内存 + sessionStorage（刷新恢复）；改密/退出必须注入，登录 csrf:false。
//  - 初始化/刷新先核实 /auth/me，不把 sessionStorage 当授权。只有 /auth/me 的 401 才算“会话失效”
//    （登录 401=密码错；改密 401 语义不明，需再用 /auth/me 区分密码错与会话失效）；403 不当作退出。
//  - 身份只有“staff + 完整有效 Me”才可进入 ready/mustChangePassword；核验失败为 unverified（可重试），
//    绝不因 /auth/me 失败而降级为 ready，也不把网络失败当登出。
//  - 认证变更互斥：登录/改密/退出/恢复共用一个闸门，同一标签内禁止并行认证变更
//    （Set-Cookie 由浏览器处理，忽略 JS 结果并不能阻止并发响应覆盖 Cookie）。
//  - epoch 代次护栏：每次认证状态变化 +1，旧操作迟到的成功/失败/finally 一律按代次丢弃，
//    不得写 CSRF、清空新会话或触发页面跳转。
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { ApiError, createHttpClient } from '@/services/http';
import type { LoginResult, Me, RoleScope } from '@/services/session';
import { SESSION_CSRF_STORAGE_KEY } from '@/services/session';

export type SessionStatus = 'checking' | 'guest' | 'unverified' | 'mustChangePassword' | 'ready';

/** 认证操作未执行的原因（页面必须据此放弃“成功”判断）。 */
export type AuthBlockedReason = 'busy';

/**
 * 认证操作结果。
 * - executed=false：因互斥/前置条件未执行，页面不得宣称成功、不得跳转。
 * - superseded=true：执行期间认证代次已变，结果作废，不得写状态或跳转。
 */
export interface AuthOutcome {
  executed: boolean;
  superseded: boolean;
  status: SessionStatus;
  reason?: AuthBlockedReason;
}

/** 改密结果：401 不再一概吞掉，按 /auth/me 复核结果区分密码错与会话失效。 */
export interface ChangePasswordOutcome extends AuthOutcome {
  result?: 'changed' | 'password-rejected' | 'session-invalid' | 'unconfirmed';
}

export interface LogoutOutcome extends AuthOutcome {
  /** 仅当服务端确认退出（204）或会话已失效（401）时为 true。 */
  ok?: boolean;
}

/** 单次 /auth/me 核验结论；null 表示代次已变、结果作废。 */
type MeCheck =
  | { kind: 'ok'; me: Me }
  | { kind: 'unauthenticated' }
  | { kind: 'non-staff' }
  | { kind: 'failed' };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 严格解析 GET /auth/me：任一必需字段缺失或类型不符都视为“结构不完整”，
 * 不能靠部分字段拼出一个可用的已登录态。
 */
function parseMe(value: unknown): Me | null {
  if (!isRecord(value)) return null;
  const accountId = value['account_id'];
  const name = value['name'];
  const identityType = value['identity_type'];
  const mustChange = value['must_change_password'];
  const rawRoles = value['roles'];

  if (typeof accountId !== 'string' || accountId === '') return null;
  if (typeof name !== 'string') return null;
  if (identityType !== 'staff' && identityType !== 'patient') return null;
  if (typeof mustChange !== 'boolean') return null;
  if (!Array.isArray(rawRoles)) return null;

  const roles: RoleScope[] = [];
  for (const raw of rawRoles) {
    if (!isRecord(raw)) return null;
    const code = raw['role_code'];
    const branchIds = raw['branch_ids'];
    if (typeof code !== 'string' || code === '') return null;
    if (!Array.isArray(branchIds) || branchIds.some((id) => typeof id !== 'string')) return null;
    roles.push({ role_code: code, branch_ids: [...(branchIds as string[])] });
  }

  return {
    account_id: accountId,
    name,
    identity_type: identityType,
    roles,
    must_change_password: mustChange,
  };
}

/** 登录/改密响应体结构校验，避免把缺字段的响应当作成功。 */
function parseAuthSessionResult(value: unknown): LoginResult | null {
  if (!isRecord(value)) return null;
  const token = value['csrf_token'];
  const mustChange = value['must_change_password'];
  if (typeof token !== 'string' || token === '') return null;
  if (typeof mustChange !== 'boolean') return null;
  return { csrf_token: token, must_change_password: mustChange };
}

export const useSessionStore = defineStore('session', () => {
  const status = ref<SessionStatus>('checking');
  const csrfToken = ref<string | null>(null);
  const me = ref<Me | null>(null);
  const loginSubmitting = ref(false);
  const changeSubmitting = ref(false);
  const logoutSubmitting = ref(false);
  // 统一认证变更闸门：登录/改密/退出/恢复互斥。
  const authBusy = ref(false);
  // 身份核验失败原因，供页面展示“身份确认未完成/非员工身份”。
  const verificationIssue = ref<'unconfirmed' | 'non-staff' | null>(null);
  // 认证代次：每次登录/改密/清理自增，用于丢弃旧请求迟到结果，避免覆盖新会话。
  const epoch = ref(0);

  // 客户端在 store 内部创建，直接闭包读取 csrfToken，避免模块级可变持有点导致跨测试串味。
  const client = createHttpClient({
    baseUrl: (import.meta.env.VITE_API_BASE_URL as string | undefined) || '/api/v1',
    timeoutMs: 30_000,
    getCsrfToken: () => csrfToken.value,
    // 不在此接 onUnauthorized：401 语义因接口而异，仅 /auth/me 的 401 由 verifyMe 统一处理并带代次护栏。
  });

  const isAuthenticated = computed(() => status.value === 'ready' || status.value === 'mustChangePassword');
  // 仅用于展示管理入口；服务端权限仍是最终依据，不能由本计算属性放行接口。
  const isSysadmin = computed(
    () => me.value?.roles.some((role) => role.role_code === 'sysadmin') ?? false,
  );
  const mustChangePassword = computed(() => status.value === 'mustChangePassword');
  const csrfMissing = computed(() => isAuthenticated.value && !csrfToken.value);
  const authSubmitting = computed(() => authBusy.value);
  // 只读会话代次：登录/改密/清理时递增，供页面/缓存隔离判断旧请求是否属于当前会话。
  // 不暴露 epoch 写入口，外部不能随意修改。
  const sessionRevision = computed(() => epoch.value);
  const verificationMessage = computed(() => {
    if (status.value === 'unverified') return '身份确认未完成，请检查网络后重试。';
    if (verificationIssue.value === 'non-staff') return '当前登录身份不是员工账号，请使用员工账号登录。';
    return null;
  });

  function setCsrf(token: string | null): void {
    csrfToken.value = token;
    try {
      if (token) sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, token);
      else sessionStorage.removeItem(SESSION_CSRF_STORAGE_KEY);
    } catch {
      // 隐私模式/无 storage 环境：仅内存态继续工作，刷新恢复不可用。
    }
  }

  /** 清理本端身份与 CSRF；不改服务端会话（服务端撤销由 logout 端点负责）。 */
  function clearSession(expectedEpoch?: number): void {
    if (expectedEpoch !== undefined && expectedEpoch !== epoch.value) return; // 旧请求不覆盖新会话
    epoch.value += 1;
    me.value = null;
    verificationIssue.value = null;
    setCsrf(null);
    status.value = 'guest';
  }

  function beginAuth(): boolean {
    if (authBusy.value) return false;
    authBusy.value = true;
    return true;
  }

  function endAuth(): void {
    authBusy.value = false;
  }

  function blocked(): AuthOutcome {
    return { executed: false, superseded: false, status: status.value, reason: 'busy' };
  }

  /**
   * GET /auth/me 并在代次未变时解析。
   * 返回 null 表示核验期间认证代次已变，调用方必须丢弃该结果（不写任何状态）。
   */
  async function verifyMe(startEpoch: number): Promise<MeCheck | null> {
    let data: unknown;
    try {
      data = (await client.get<unknown>('/auth/me')).data;
    } catch (e) {
      if (startEpoch !== epoch.value) return null;
      return e instanceof ApiError && e.status === 401 ? { kind: 'unauthenticated' } : { kind: 'failed' };
    }
    if (startEpoch !== epoch.value) return null;
    const parsed = parseMe(data);
    if (!parsed) return { kind: 'failed' };
    if (parsed.identity_type !== 'staff') return { kind: 'non-staff' };
    return { kind: 'ok', me: parsed };
  }

  /** 把核验结论落到状态；代次已变则一律不写。返回是否进入已登录态。 */
  function applyMeCheck(check: MeCheck, startEpoch: number): boolean {
    if (startEpoch !== epoch.value) return false;
    if (check.kind === 'ok') {
      me.value = check.me;
      verificationIssue.value = null;
      status.value = check.me.must_change_password ? 'mustChangePassword' : 'ready';
      return true;
    }
    if (check.kind === 'unauthenticated') {
      clearSession(startEpoch);
      return false;
    }
    if (check.kind === 'non-staff') {
      clearSession(startEpoch);
      verificationIssue.value = 'non-staff';
      return false;
    }
    // 网络/5xx/403/结构不完整：无法确认身份。保留已取得 CSRF，标记可重试，不当作登出。
    me.value = null;
    verificationIssue.value = 'unconfirmed';
    status.value = 'unverified';
    return false;
  }

  /** GET /auth/me 核实身份（可重试入口）。成功进入 ready/mustChangePassword；失败不抛错，落到状态。 */
  async function fetchMe(): Promise<boolean> {
    const startEpoch = epoch.value;
    const check = await verifyMe(startEpoch);
    if (!check) return false;
    return applyMeCheck(check, startEpoch);
  }

  // 共享进行中的恢复流程，避免多个路由守卫并行重复恢复。
  let bootstrapInFlight: Promise<void> | null = null;

  /** 初始化/刷新：先从 sessionStorage 恢复 CSRF，再核实 /auth/me。绝不把 storage 当授权。 */
  function bootstrap(): Promise<void> {
    if (bootstrapInFlight) return bootstrapInFlight;
    if (!beginAuth()) return Promise.resolve();
    const startEpoch = epoch.value;
    status.value = 'checking';
    verificationIssue.value = null;

    const run = (async () => {
      let restored: string | null = null;
      try {
        restored = sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY);
      } catch {
        restored = null;
      }
      if (restored) csrfToken.value = restored;

      const check = await verifyMe(startEpoch);
      if (!check) return; // 旧恢复流程结果作废
      // 方案要求 CSRF 缺失即重新登录，不杜撰获取接口。Cookie 可能仍有效（如新标签页无 sessionStorage），
      // 但此时无法安全改密/退出，因此不进入已登录态（已先核实 /auth/me，未把 storage 当授权）。
      if (check.kind === 'ok' && !csrfToken.value) {
        clearSession(startEpoch);
        return;
      }
      applyMeCheck(check, startEpoch);
    })().catch(() => {
      // 防御：核验流程自身异常时给出可重试状态，而不是静默落到 guest。
      if (epoch.value === startEpoch) {
        me.value = null;
        verificationIssue.value = 'unconfirmed';
        status.value = 'unverified';
      }
    });

    bootstrapInFlight = run.finally(() => {
      bootstrapInFlight = null;
      endAuth();
    });
    return bootstrapInFlight;
  }

  /**
   * 重新核验身份（“身份确认未完成”面板的重试入口）。
   * 与登录/改密/退出共用互斥闸门。
   */
  async function retryVerify(): Promise<AuthOutcome> {
    if (!beginAuth()) return blocked();
    const startEpoch = epoch.value;
    try {
      const check = await verifyMe(startEpoch);
      if (!check) return { executed: true, superseded: true, status: status.value };
      if (check.kind === 'ok' && !csrfToken.value) {
        clearSession(startEpoch);
        return { executed: true, superseded: false, status: status.value };
      }
      applyMeCheck(check, startEpoch);
      return { executed: true, superseded: false, status: status.value };
    } finally {
      endAuth();
    }
  }

  /** 登录。重复调用返回 executed=false；请求失败上抛；身份未确认时落到 unverified 而非 ready。 */
  async function login(loginName: string, password: string): Promise<AuthOutcome> {
    if (!beginAuth()) return blocked();
    const startEpoch = epoch.value;
    loginSubmitting.value = true;
    try {
      const res = await client.post<unknown>(
        '/auth/staff-login',
        { login_name: loginName, password },
        { csrf: false },
      );
      if (startEpoch !== epoch.value) return { executed: true, superseded: true, status: status.value };
      const data = parseAuthSessionResult(res.data);
      if (!data) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的响应。');

      // 新会话开始：旧代次作废，先落 CSRF 与登录响应状态，再由 /auth/me 定论是否进入已登录态。
      epoch.value += 1;
      const committedEpoch = epoch.value;
      verificationIssue.value = null;
      setCsrf(data.csrf_token);
      me.value = null;
      status.value = data.must_change_password ? 'mustChangePassword' : 'unverified';

      const check = await verifyMe(committedEpoch);
      if (!check) return { executed: true, superseded: true, status: status.value };
      applyMeCheck(check, committedEpoch);
      return { executed: true, superseded: false, status: status.value };
    } finally {
      loginSubmitting.value = false;
      endAuth();
    }
  }

  /**
   * 改密。401 语义不明（当前密码错 or 会话失效），用受代次保护的 /auth/me 确认：
   *  - me 有效       → password-rejected（保留会话与 CSRF，提示密码错误）
   *  - me 401        → session-invalid（清理并回登录）
   *  - me 网络/5xx   → unconfirmed（不登出、不清 CSRF，提示重试）
   * 403/网络等其它失败原样上抛，不擅自登出。
   */
  async function changePassword(
    currentPassword: string,
    newPassword: string,
  ): Promise<ChangePasswordOutcome> {
    if (!beginAuth()) return blocked();
    const startEpoch = epoch.value;
    changeSubmitting.value = true;
    try {
      if (!csrfToken.value) {
        throw new ApiError('CSRF_MISSING', 0, '会话安全令牌缺失，请重新登录。');
      }

      let raw: unknown;
      try {
        const res = await client.post<unknown>(
          '/auth/password-change',
          { current_password: currentPassword, new_password: newPassword },
          { csrf: true },
        );
        raw = res.data;
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e; // 403/网络/422：不擅自登出
        if (startEpoch !== epoch.value) return { executed: true, superseded: true, status: status.value };

        const probeEpoch = epoch.value;
        const probe = await verifyMe(probeEpoch);
        if (!probe) return { executed: true, superseded: true, status: status.value };
        if (probe.kind === 'ok') {
          applyMeCheck(probe, probeEpoch);
          return {
            executed: true,
            superseded: false,
            status: status.value,
            result: 'password-rejected',
          };
        }
        if (probe.kind === 'unauthenticated' || probe.kind === 'non-staff') {
          applyMeCheck(probe, probeEpoch);
          return {
            executed: true,
            superseded: false,
            status: status.value,
            result: 'session-invalid',
          };
        }
        // 无法确认会话：保留 CSRF、不登出，给出可重试状态。
        me.value = null;
        verificationIssue.value = 'unconfirmed';
        status.value = 'unverified';
        return { executed: true, superseded: false, status: status.value, result: 'unconfirmed' };
      }

      if (startEpoch !== epoch.value) return { executed: true, superseded: true, status: status.value };
      const data = parseAuthSessionResult(raw);
      if (!data) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的响应。');

      // 改密成功：旧会话已整体撤销，替换 CSRF 后再核验身份（改密失败不得盲目重发）。
      epoch.value += 1;
      const committedEpoch = epoch.value;
      verificationIssue.value = null;
      setCsrf(data.csrf_token);
      status.value = data.must_change_password ? 'mustChangePassword' : 'unverified';

      const check = await verifyMe(committedEpoch);
      if (!check) return { executed: true, superseded: true, status: status.value };
      applyMeCheck(check, committedEpoch);
      return { executed: true, superseded: false, status: status.value, result: 'changed' };
    } finally {
      changeSubmitting.value = false;
      endAuth();
    }
  }

  /** 仅服务端成功后宣称退出；网络/403 上抛（页面提示“退出未确认”并可重试），不自动重试。 */
  async function logout(): Promise<LogoutOutcome> {
    if (!beginAuth()) return blocked();
    const startEpoch = epoch.value;
    logoutSubmitting.value = true;
    try {
      if (!csrfToken.value) {
        throw new ApiError('CSRF_MISSING', 0, '会话安全令牌缺失，请重新登录。');
      }
      try {
        await client.post<void>('/auth/logout', undefined, { csrf: true });
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e; // 网络失败 / 403：退出未确认
        // 会话已失效（被撤销/过期）：视为已退出，但仍受代次保护。
        if (startEpoch !== epoch.value) return { executed: true, superseded: true, status: status.value };
        clearSession(startEpoch);
        return { executed: true, superseded: false, status: status.value, ok: true };
      }
      if (startEpoch !== epoch.value) return { executed: true, superseded: true, status: status.value };
      clearSession(startEpoch);
      return { executed: true, superseded: false, status: status.value, ok: true };
    } finally {
      logoutSubmitting.value = false;
      endAuth();
    }
  }

  return {
    status,
    csrfToken,
    me,
    loginSubmitting,
    changeSubmitting,
    logoutSubmitting,
    authSubmitting,
    sessionRevision,
    verificationIssue,
    verificationMessage,
    isAuthenticated,
    isSysadmin,
    mustChangePassword,
    csrfMissing,
    bootstrap,
    login,
    changePassword,
    logout,
    fetchMe,
    retryVerify,
    clearSession,
  };
});
