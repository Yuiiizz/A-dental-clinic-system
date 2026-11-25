// 患者登录/建档/旧档绑定的内存编排。页面只通过这里改变身份状态，便于单测覆盖异步边界。
//
// 约束：
//  - 同一时刻只允许一次认证变更；页面卸载/上下文失效后旧结果不再写状态。
//  - 注册/绑定凭证只在内存保留，不写 storage、不写日志；超时/网络失败不自动重发。
//  - 不信任客户端 openid，不发送员工 Cookie/CSRF；只发送微信 code 或患者 Bearer。
import { ApiError } from './http';
import { authApi } from './auth-api';
import type { AuthApi } from './auth-api';
import { patientSession, PatientSessionStore } from './session';
import { wxLogin, WxLoginError } from './wx-login';
import type {
  AllergyStatus,
  Me,
  PatientRegistrationForm,
} from './auth-types';

export interface PageContext {
  active: () => boolean;
  /** 页面实例；用于取消时只允许操作归属者取消自己的请求。 */
  owner?: object;
  /** 页面生命周期代次；同页 hide/show 后旧代次不能取消新代次操作。 */
  generation?: number;
}

export type AuthFlowStatus =
  | 'session'
  | 'registration'
  | 'unverified'
  | 'unknown'
  | 'busy'
  | 'stale'
  | 'staff-rejected'
  | 'session-expired'
  | 'anonymous'
  | 'no-registration'
  | 'validation'
  | 'conflict'
  | 'forbidden'
  | 'rate-limited'
  | 'wechat-unavailable'
  | 'error';

export interface AuthFlowOutcome {
  status: AuthFlowStatus;
  message: string;
  me?: Me;
}

export interface AuthFlowDeps {
  wxLogin: () => Promise<string>;
  api: AuthApi;
  session: PatientSessionStore;
}

const ALLERGY_STATUSES: readonly AllergyStatus[] = ['未申报', '无已知', '有', '不确定'];

function messageFor(status: AuthFlowStatus): string {
  switch (status) {
    case 'registration':
      return '该微信尚未绑定档案。请完成本人建档，或使用前台核验后交付的绑定凭证。';
    case 'unverified':
      return '登录已返回，但本人身份尚未确认；请检查网络后重试核验。';
    case 'unknown':
      return '结果未确认。请重新微信登录核对是否已完成，不要重复提交同一凭证。';
    case 'busy':
      return '正在处理上一次操作，请稍候。';
    case 'stale':
      return '页面或会话已变化，本次结果未应用到当前页面。';
    case 'staff-rejected':
      return '该微信绑定的是员工身份，不能使用患者端；请使用员工端。';
    case 'session-expired':
      return '登录已失效，请重新微信登录。';
    case 'anonymous':
      return '尚未登录，请先使用微信登录。';
    case 'no-registration':
      return '注册上下文不存在或已失效，请重新微信登录。';
    case 'validation':
      return '填写内容不符合要求，请检查后重试。';
    case 'conflict':
      return '该身份或手机号已有档案，请联系前台核验，不要自行重复提交。';
    case 'forbidden':
      return '当前身份不能完成该操作，请联系前台。';
    case 'rate-limited':
      return '操作过于频繁，请稍后重试。';
    case 'wechat-unavailable':
      return '微信身份服务暂时不可用，请稍后重试。';
    default:
      return '操作未完成，请稍后重试。';
  }
}

function outcome(status: AuthFlowStatus, me?: Me): AuthFlowOutcome {
  return { status, message: messageFor(status), ...(me === undefined ? {} : { me }) };
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(parsed);
}

function validateForm(form: PatientRegistrationForm): AuthFlowOutcome | null {
  const name = form.name.trim();
  const phone = form.phone.trim();
  if (!name || name.length > 60) return outcome('validation');
  if (!phone || phone.length > 30) return outcome('validation');
  if (!ALLERGY_STATUSES.includes(form.allergy_status)) return outcome('validation');
  if (form.birth_date && !validDate(form.birth_date)) return outcome('validation');
  const notes = form.allergy_notes?.trim() ?? '';
  if ((form.allergy_status === '有' || form.allergy_status === '不确定') && !notes) {
    return {
      status: 'validation',
      message: '过敏状态为“有”或“不确定”时，请填写过敏说明。',
    };
  }
  return null;
}

export class AuthFlow {
  private readonly deps: AuthFlowDeps;
  private busy = false;
  private operation = 0;
  private activeOperation: { op: number; owner?: object; generation?: number } | null = null;
  private registrationValue: { token: string; expiresAt: string } | null = null;
  private singleUse: { op: number; registration: { token: string; expiresAt: string } | null } | null =
    null;

  constructor(deps: AuthFlowDeps) {
    this.deps = deps;
  }

  get registrationToken(): string | null {
    // 一次性注册凭证一旦发出请求，就不能再被页面重新提交；等待核对期间视为不可用。
    if (this.singleUse !== null || this.registrationValue === null) return null;
    const expires = Date.parse(this.registrationValue.expiresAt);
    if (!Number.isFinite(expires) || expires <= Date.now()) {
      this.registrationValue = null;
      return null;
    }
    return this.registrationValue.token;
  }

  get hasRegistration(): boolean {
    return this.registrationToken !== null;
  }

  get hasSession(): boolean {
    return this.deps.session.token !== null;
  }

  get sessionRevision(): number {
    return this.deps.session.revision;
  }

  private begin(ctx: PageContext): { op: number } | { outcome: AuthFlowOutcome } {
    if (!ctx.active()) return { outcome: outcome('stale') };
    if (this.busy) return { outcome: outcome('busy') };
    const op = ++this.operation;
    this.busy = true;
    this.activeOperation = { op, owner: ctx.owner, generation: ctx.generation };
    return { op };
  }

  private current(op: number, ctx: PageContext): boolean {
    return this.operation === op && this.activeOperation?.op === op && ctx.active();
  }

  private finish(op: number): void {
    if (this.activeOperation?.op !== op) return;
    this.busy = false;
    this.activeOperation = null;
  }

  /**
   * 只取消调用页面自己发起的操作；旧页面重复取消/A 页面卸载不会取消 B 页面的请求。
   * 不传 ctx 仅供流程单测直接取消当前操作。
   */
  cancelPending(ctx?: PageContext): void {
    const active = this.activeOperation;
    if (active === null) return;
    if (ctx?.owner !== undefined && active.owner !== undefined && ctx.owner !== active.owner) return;
    if (
      ctx?.generation !== undefined &&
      active.generation !== undefined &&
      ctx.generation !== active.generation
    ) {
      return;
    }
    const op = active.op;
    this.operation += 1;
    this.busy = false;
    this.activeOperation = null;
    if (this.singleUse?.op === op) this.abandonSingleUse(op);
  }

  private clearForNewIdentity(): number {
    // 新身份核对开始即清除旧身份与旧注册上下文；分支结果只能保留其中一个。
    this.registrationValue = null;
    this.singleUse = null;
    this.deps.session.clear();
    return this.deps.session.revision;
  }

  private beginSingleUse(op: number): void {
    this.singleUse = { op, registration: this.registrationValue };
    this.registrationValue = null;
  }

  private completeSingleUse(op: number): void {
    if (this.singleUse?.op !== op) return;
    this.singleUse = null;
  }

  private abandonSingleUse(op: number): void {
    if (this.singleUse?.op !== op) return;
    this.singleUse = null;
    this.registrationValue = null;
  }

  /** 明确业务拒绝可保留注册上下文；结果不明/失效则废弃其再次提交能力。 */
  private settleSingleUse(op: number, status: AuthFlowStatus): void {
    if (this.singleUse?.op !== op) return;
    const previous = this.singleUse.registration;
    this.singleUse = null;
    if (
      previous !== null &&
      (status === 'validation' || status === 'conflict' || status === 'forbidden' || status === 'rate-limited')
    ) {
      this.registrationValue = previous;
    } else {
      this.registrationValue = null;
    }
  }

  private commitSession(
    op: number,
    ctx: PageContext,
    result: { session_token: string; patient_id?: string; expires_at?: string },
  ): Promise<AuthFlowOutcome> {
    if (!this.current(op, ctx)) return Promise.resolve(outcome('stale'));
    this.deps.session.setSession(result.session_token, result.patient_id, result.expires_at);
    return this.verifyCurrent(op, ctx, this.deps.session.revision);
  }

  private async verifyCurrent(
    op: number,
    ctx: PageContext,
    expectedRevision: number,
  ): Promise<AuthFlowOutcome> {
    try {
      const me = await this.deps.api.me();
      if (!this.current(op, ctx)) return outcome('stale');
      if (this.deps.session.revision !== expectedRevision || this.deps.session.token === null) {
        return outcome('stale');
      }
      if (me.identity_type !== 'patient') {
        this.deps.session.clear();
        return outcome('staff-rejected');
      }
      this.deps.session.setMe(me);
      return outcome('session', me);
    } catch (error) {
      if (!this.current(op, ctx)) return outcome('stale');
      if (this.deps.session.revision !== expectedRevision || this.deps.session.token === null) {
        return outcome('stale');
      }
      if (error instanceof ApiError) {
        if (error.status === 401) {
          this.deps.session.clear();
          return outcome('session-expired');
        }
        if (error.status === 403) {
          // 已认证但当前访问被拒：保留 Bearer，不伪装成登录失效。
          return outcome('forbidden');
        }
        if (
          error.code === 'TIMEOUT' ||
          error.code === 'NETWORK_ERROR' ||
          error.code === 'INVALID_RESPONSE' ||
          error.status >= 500
        ) {
          // 会话已签发但身份未确认：保留令牌，页面重试 /auth/me，不重新提交凭证。
          return outcome('unverified');
        }
      }
      return outcome('error');
    }
  }

  async login(ctx: PageContext): Promise<AuthFlowOutcome> {
    const started = this.begin(ctx);
    if ('outcome' in started) return started.outcome;
    const op = started.op;
    try {
      this.clearForNewIdentity();
      const code = await this.deps.wxLogin();
      if (!this.current(op, ctx)) return outcome('stale');
      const result = await this.deps.api.wechatLogin(code);
      if (!this.current(op, ctx)) return outcome('stale');
      if (result.kind === 'registration') {
        this.registrationValue = {
          token: result.registration_token,
          expiresAt: result.registration_expires_at,
        };
        return outcome('registration');
      }
      return await this.commitSession(op, ctx, {
        session_token: result.session_token,
        expires_at: result.expires_at,
      });
    } catch (error) {
      if (!this.current(op, ctx)) return outcome('stale');
      return this.classify(error, 'login');
    } finally {
      this.finish(op);
    }
  }

  async verifySession(ctx: PageContext): Promise<AuthFlowOutcome> {
    if (!this.deps.session.token) return outcome('anonymous');
    const started = this.begin(ctx);
    if ('outcome' in started) return started.outcome;
    const op = started.op;
    const expectedRevision = this.deps.session.revision;
    try {
      return await this.verifyCurrent(op, ctx, expectedRevision);
    } finally {
      this.finish(op);
    }
  }

  async register(ctx: PageContext, form: PatientRegistrationForm): Promise<AuthFlowOutcome> {
    const invalid = validateForm(form);
    if (invalid) return invalid;
    const token = this.registrationToken;
    if (!token) return outcome('no-registration');
    const started = this.begin(ctx);
    if ('outcome' in started) return started.outcome;
    const op = started.op;
    this.beginSingleUse(op);
    try {
      const result = await this.deps.api.register({
        registration_token: token,
        name: form.name.trim(),
        phone: form.phone.trim(),
        ...(form.birth_date ? { birth_date: form.birth_date } : {}),
        allergy_status: form.allergy_status,
        ...(form.allergy_notes?.trim() ? { allergy_notes: form.allergy_notes.trim() } : {}),
      });
      if (!this.current(op, ctx)) {
        this.abandonSingleUse(op);
        return outcome('stale');
      }
      this.completeSingleUse(op);
      return await this.commitSession(op, ctx, result);
    } catch (error) {
      if (!this.current(op, ctx)) {
        this.abandonSingleUse(op);
        return outcome('stale');
      }
      const classified = this.classify(error, 'register');
      this.settleSingleUse(op, classified.status);
      return classified;
    } finally {
      this.finish(op);
    }
  }

  async bind(ctx: PageContext, bindingToken: string): Promise<AuthFlowOutcome> {
    const normalized = bindingToken.trim();
    if (!normalized) {
      return { status: 'validation', message: '请填写前台交付的绑定凭证。' };
    }
    const token = this.registrationToken;
    if (!token) return outcome('no-registration');
    const started = this.begin(ctx);
    if ('outcome' in started) return started.outcome;
    const op = started.op;
    this.beginSingleUse(op);
    try {
      const result = await this.deps.api.bind({
        registration_token: token,
        binding_token: normalized,
      });
      if (!this.current(op, ctx)) {
        this.abandonSingleUse(op);
        return outcome('stale');
      }
      this.completeSingleUse(op);
      return await this.commitSession(op, ctx, result);
    } catch (error) {
      if (!this.current(op, ctx)) {
        this.abandonSingleUse(op);
        return outcome('stale');
      }
      const classified = this.classify(error, 'bind');
      this.settleSingleUse(op, classified.status);
      return classified;
    } finally {
      this.finish(op);
    }
  }

  /** 超时/结果不明后重新取 wx.login code 核对，不复用旧凭证、不重复提交。 */
  async recheck(ctx: PageContext): Promise<AuthFlowOutcome> {
    const result = await this.login(ctx);
    if (result.status === 'registration') {
      return {
        status: 'registration',
        message: '重新登录成功，但当前微信仍未绑定档案；请完成本人建档或使用前台凭证绑定。',
      };
    }
    return result;
  }

  private classify(error: unknown, action: 'login' | 'register' | 'bind'): AuthFlowOutcome {
    if (error instanceof WxLoginError) return outcome('error');
    if (!(error instanceof ApiError)) return outcome('error');
    if (error.code === 'TIMEOUT' || error.code === 'NETWORK_ERROR') {
      return action === 'login' ? outcome('unverified') : outcome('unknown');
    }
    if (error.status === 502 || error.status === 503) {
      return action === 'login' ? outcome('wechat-unavailable') : outcome('unknown');
    }
    if (error.code === 'INVALID_RESPONSE' || error.status >= 500) {
      // 注册/绑定可能已提交但响应畸形或代理失败：不能当明确失败并复用旧凭证。
      return action === 'login' ? outcome('unverified') : outcome('unknown');
    }
    if (error.status === 401) {
      return action === 'login' ? outcome('session-expired') : outcome('session-expired');
    }
    if (error.status === 403) return outcome('forbidden');
    if (error.status === 429 || error.code === 'RATE_LIMITED') return outcome('rate-limited');
    if (error.status === 409) return outcome('conflict');
    if (error.status === 422 || error.code === 'VALIDATION_FAILED' || error.code === 'UNKNOWN_FIELD') {
      return outcome('validation');
    }
    if (error.status === 502 || error.status === 503 || error.code === 'WECHAT_EXCHANGE_FAILED' || error.code === 'WECHAT_NOT_CONFIGURED') {
      return outcome('wechat-unavailable');
    }
    return outcome('error');
  }
}

export const authFlow = new AuthFlow({
  wxLogin,
  api: authApi,
  session: patientSession,
});
