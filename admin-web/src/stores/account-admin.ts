// 账号管理页面状态。HTTP 客户端复用会话 store 的 CSRF 提供器与同源 Cookie；
// 本 store 只缓存账号列表，不保存任何登录态、密码或凭证。
//
// 缓存隔离：每个列表/变更请求都捕获当前会话 revision 与账号编号；身份变化后旧请求
// 的成功/401 一律丢弃，避免旧账号数据覆盖新会话。列表另用 queryId/requestId 防止
// 并行 reset 与分页交叉；变更操作在 store 层防重并返回显式的“未执行/已过期”结果。
import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { createHttpClient } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import {
  createAccountAdminApi,
  type AccountAdminApi,
  type CreateAccountInput,
  type CreatedStaffAccount,
  type RoleScopeDetail,
  type RoleScopeGrant,
  type RoleScopeUpdateResult,
  type StaffAccount,
} from '@/services/account-admin';

export type AdminMutationBlockedReason = 'busy' | 'auth-changing' | 'not-ready' | 'not-admin';

/**
 * 变更结果：
 * - executed=false：store 层未执行（重入/身份不满足），页面不得宣称成功；
 * - executed=true && stale=true：请求已发出，但等待期间会话身份/代次已变，结果不可用于当前 UI，
 *   也不声称能撤销服务端可能已提交的操作。
 */
export interface AdminMutationOutcome<T = void> {
  executed: boolean;
  stale: boolean;
  data?: T;
  reason?: AdminMutationBlockedReason;
}

export type RoleDetailBlockedReason = AdminMutationBlockedReason | 'not-selected';

/** 角色详情读取结果；页面必须按 requestId/账号/会话判断是否属于自己的有效操作。 */
export interface RoleDetailOutcome {
  executed: boolean;
  stale: boolean;
  requestId: number;
  accountId: string;
  data?: RoleScopeDetail;
  reason?: RoleDetailBlockedReason;
}

interface IdentitySnapshot {
  revision: number;
  accountId: string | null;
  status: string;
  isSysadmin: boolean;
}

export const useAccountAdminStore = defineStore('accountAdmin', () => {
  const session = useSessionStore();
  const api: AccountAdminApi = createAccountAdminApi(
    createHttpClient({
      baseUrl: (import.meta.env.VITE_API_BASE_URL as string | undefined) || '/api/v1',
      timeoutMs: 30_000,
      getCsrfToken: () => session.csrfToken,
    }),
  );

  const accounts = ref<StaffAccount[]>([]);
  const nextCursor = ref<string | null>(null);
  const selectedAccountId = ref<string | null>(null);
  const roleDetail = ref<RoleScopeDetail | null>(null);
  const roleDetailLoading = ref(false);
  const listLoading = ref(false);
  const createSubmitting = ref(false);
  const rolesSubmitting = ref(false);
  const disableSubmitting = ref(false);
  // 缓存被身份/权限变化强制清空时递增，页面据此清空选择、草稿和危险操作面板。
  const cacheRevision = ref(0);

  let listQueryId = 0;
  let latestListRequest = 0;
  let roleDetailRequestId = 0;

  const hasMore = computed(() => nextCursor.value !== null);

  function identitySnapshot(): IdentitySnapshot {
    return {
      revision: session.sessionRevision,
      accountId: session.me?.account_id ?? null,
      status: session.status,
      isSysadmin: session.isSysadmin,
    };
  }

  function isStale(snapshot: IdentitySnapshot): boolean {
    return (
      session.sessionRevision !== snapshot.revision ||
      (session.me?.account_id ?? null) !== snapshot.accountId ||
      session.status !== snapshot.status ||
      session.isSysadmin !== snapshot.isSysadmin
    );
  }

  function clearRoleDetail(): void {
    roleDetailRequestId += 1;
    roleDetail.value = null;
    roleDetailLoading.value = false;
  }

  function clearCache(): void {
    // 让所有在途列表/详情请求失效，并清空跨身份缓存/选择。
    listQueryId += 1;
    latestListRequest += 1;
    accounts.value = [];
    nextCursor.value = null;
    selectedAccountId.value = null;
    clearRoleDetail();
    listLoading.value = false;
    cacheRevision.value += 1;
  }

  // 退出/切换账号/失去管理员/身份未确认时立即清空，不能继续展示上一身份缓存。
  watch(() => session.sessionRevision, clearCache, { flush: 'sync' });
  watch(
    () => session.status,
    (status) => {
      if (status !== 'ready') clearCache();
    },
    { flush: 'sync' },
  );
  watch(
    () => session.isSysadmin,
    (isSysadmin) => {
      if (!isSysadmin) clearCache();
    },
    { flush: 'sync' },
  );

  function blocked(reason: AdminMutationBlockedReason): AdminMutationOutcome<never> {
    return { executed: false, stale: false, reason };
  }

  function stale<T>(): AdminMutationOutcome<T> {
    return { executed: true, stale: true };
  }

  function mutationGate(): AdminMutationBlockedReason | null {
    if (session.authSubmitting) return 'auth-changing';
    if (session.status !== 'ready' || session.me === null) return 'not-ready';
    if (!session.isSysadmin) return 'not-admin';
    return null;
  }

  async function load(reset = true): Promise<void> {
    if (session.status !== 'ready' || session.me === null) return;
    if (!reset && (listLoading.value || nextCursor.value === null)) return;

    const requestId = ++latestListRequest;
    const snapshot = identitySnapshot();
    let queryId: number;
    let cursor: string | undefined;

    if (reset) {
      queryId = ++listQueryId;
      cursor = undefined;
    } else {
      queryId = listQueryId;
      cursor = nextCursor.value ?? undefined;
      if (!cursor) return;
    }

    listLoading.value = true;
    try {
      const page = await api.list(cursor);
      // 旧请求、旧查询或旧身份的结果都不能写入当前缓存。
      if (requestId !== latestListRequest || queryId !== listQueryId || isStale(snapshot)) return;
      accounts.value = reset ? page.items : [...accounts.value, ...page.items];
      nextCursor.value = page.nextCursor;
      if (
        reset &&
        selectedAccountId.value !== null &&
        !accounts.value.some((account) => account.account_id === selectedAccountId.value)
      ) {
        selectedAccountId.value = null;
      }
    } catch (error) {
      // 旧请求的 401/失败不得触发新会话的身份复核或错误提示。
      if (requestId !== latestListRequest || queryId !== listQueryId || isStale(snapshot)) return;
      throw error;
    } finally {
      if (requestId === latestListRequest) listLoading.value = false;
    }
  }

  async function create(
    input: CreateAccountInput,
    idempotencyKey: string,
  ): Promise<AdminMutationOutcome<CreatedStaffAccount>> {
    if (createSubmitting.value) return blocked('busy');
    const gate = mutationGate();
    if (gate) return blocked(gate);
    const snapshot = identitySnapshot();
    createSubmitting.value = true;
    try {
      const data = await api.create(input, idempotencyKey);
      if (isStale(snapshot)) return stale<CreatedStaffAccount>();
      return { executed: true, stale: false, data };
    } catch (error) {
      if (isStale(snapshot)) return stale<CreatedStaffAccount>();
      throw error;
    } finally {
      createSubmitting.value = false;
    }
  }

  async function setRoles(
    accountId: string,
    expectedVersion: number,
    roles: readonly RoleScopeGrant[],
  ): Promise<AdminMutationOutcome<RoleScopeUpdateResult>> {
    if (rolesSubmitting.value) return blocked('busy');
    const gate = mutationGate();
    if (gate) return blocked(gate);
    const snapshot = identitySnapshot();
    rolesSubmitting.value = true;
    try {
      const data = await api.setRoleScopes(accountId, expectedVersion, roles);
      if (isStale(snapshot) || selectedAccountId.value !== accountId) return stale<RoleScopeUpdateResult>();
      return { executed: true, stale: false, data };
    } catch (error) {
      if (isStale(snapshot) || selectedAccountId.value !== accountId) return stale<RoleScopeUpdateResult>();
      throw error;
    } finally {
      rolesSubmitting.value = false;
    }
  }

  async function loadRoleScopes(accountId: string): Promise<RoleDetailOutcome> {
    const blockedBase = { executed: false, stale: false, requestId: 0, accountId } as const;
    if (session.status !== 'ready' || session.me === null) {
      return { ...blockedBase, reason: 'not-ready' };
    }
    if (!session.isSysadmin) return { ...blockedBase, reason: 'not-admin' };
    if (selectedAccountId.value !== accountId) {
      return { ...blockedBase, reason: 'not-selected' };
    }
    const requestId = ++roleDetailRequestId;
    const snapshot = identitySnapshot();
    roleDetail.value = null;
    roleDetailLoading.value = true;
    try {
      const detail = await api.getRoleScopes(accountId);
      if (
        requestId !== roleDetailRequestId ||
        selectedAccountId.value !== accountId ||
        isStale(snapshot)
      ) {
        return { executed: true, stale: true, requestId, accountId };
      }
      roleDetail.value = detail;
      return { executed: true, stale: false, requestId, accountId, data: detail };
    } catch (error) {
      if (
        requestId !== roleDetailRequestId ||
        selectedAccountId.value !== accountId ||
        isStale(snapshot)
      ) {
        return { executed: true, stale: true, requestId, accountId };
      }
      throw error;
    } finally {
      if (requestId === roleDetailRequestId) roleDetailLoading.value = false;
    }
  }

  async function disable(accountId: string, reason: string): Promise<AdminMutationOutcome> {
    if (disableSubmitting.value) return blocked('busy');
    const gate = mutationGate();
    if (gate) return blocked(gate);
    const snapshot = identitySnapshot();
    disableSubmitting.value = true;
    try {
      await api.disable(accountId, reason);
      if (isStale(snapshot)) return stale();
      return { executed: true, stale: false };
    } catch (error) {
      if (isStale(snapshot)) return stale();
      throw error;
    } finally {
      disableSubmitting.value = false;
    }
  }

  function selectAccount(accountId: string): void {
    if (selectedAccountId.value !== accountId) clearRoleDetail();
    selectedAccountId.value = accountId;
  }

  function clearSelection(): void {
    selectedAccountId.value = null;
    clearRoleDetail();
  }

  return {
    accounts,
    nextCursor,
    selectedAccountId,
    roleDetail,
    roleDetailLoading,
    hasMore,
    listLoading,
    createSubmitting,
    rolesSubmitting,
    disableSubmitting,
    cacheRevision,
    load,
    create,
    loadRoleScopes,
    setRoles,
    disable,
    selectAccount,
    clearSelection,
    clearRoleDetail,
    clearCache,
  };
});
