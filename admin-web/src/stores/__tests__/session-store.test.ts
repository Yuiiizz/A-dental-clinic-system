import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useSessionStore } from '@/stores/session';
import { SESSION_CSRF_STORAGE_KEY } from '@/services/session';

interface MockResponse {
  status: number;
  ok: boolean;
  headers: { get: (name: string) => string | null };
  text: () => Promise<string>;
}

function ok(body: unknown): MockResponse {
  return {
    status: 200,
    ok: true,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  };
}

function empty(): MockResponse {
  return { status: 204, ok: true, headers: { get: () => 'req-204' }, text: async () => '' };
}

function err(status: number, code: string): MockResponse {
  return {
    status,
    ok: false,
    headers: { get: () => 'req-err' },
    text: async () => JSON.stringify({ code, message: '失败' }),
  };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const mePayload = {
  data: {
    account_id: '42',
    name: '张三',
    identity_type: 'staff',
    roles: [{ role_code: 'frontdesk', branch_ids: ['B1'] }],
    must_change_password: false,
  },
};

describe('会话 store', () => {
  let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

  beforeEach(() => {
    setActivePinia(createPinia());
    sessionStorage.clear();
    fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  type FetchCall = Parameters<typeof fetch>;

  function headerOf(call: FetchCall | undefined, name: string): string | undefined {
    const init = call?.[1];
    const headers = init?.headers as Record<string, string> | undefined;
    return headers?.[name];
  }

  function bodyOf(call: FetchCall | undefined): Record<string, unknown> | undefined {
    const init = call?.[1];
    if (typeof init?.body !== 'string') return undefined;
    return JSON.parse(init.body) as Record<string, unknown>;
  }

  it('正常登录：csrf:false、设置 CSRF 与身份、密码不落入 storage', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok-1' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');

    const loginCall = fetchMock.mock.calls[0];
    expect(loginCall?.[0]).toBe('/api/v1/auth/staff-login');
    expect(headerOf(loginCall, 'X-CSRF-Token')).toBeUndefined();
    expect(bodyOf(loginCall)).toEqual({ login_name: 'alice', password: 'secret-pass' });

    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('tok-1');
    expect(store.me?.name).toBe('张三');
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBe('tok-1');
    // 密码不持久化：storage 中只有 CSRF 一个键
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.key(0)).toBe(SESSION_CSRF_STORAGE_KEY);
    expect(sessionStorage.getItem('password')).toBeNull();
  });

  it('强制改密：登录返回 must_change_password=true 时进入改密态', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: true, csrf_token: 'tok-2' } }) as unknown as Response)
      .mockResolvedValueOnce(
        ok({ data: { ...mePayload.data, must_change_password: true } }) as unknown as Response,
      );

    const store = useSessionStore();
    await store.login('bob', 'pass-12345678');

    expect(store.status).toBe('mustChangePassword');
    expect(store.mustChangePassword).toBe(true);
  });

  it('改密成功替换 CSRF 并注入旧 CSRF、重新 GET /auth/me', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'old' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'new' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    await store.changePassword('secret-pass', 'new-pass-123456');

    const changeCall = fetchMock.mock.calls[2];
    expect(changeCall?.[0]).toBe('/api/v1/auth/password-change');
    expect(headerOf(changeCall, 'X-CSRF-Token')).toBe('old');
    expect(bodyOf(changeCall)).toEqual({ current_password: 'secret-pass', new_password: 'new-pass-123456' });

    expect(store.csrfToken).toBe('new');
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBe('new');
    expect(store.status).toBe('ready');
    // 重新核实身份
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/auth/me');
  });

  it('刷新恢复：从 sessionStorage 恢复 CSRF 后核实 /auth/me，不把 storage 当授权', async () => {
    sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, 'restored');
    fetchMock.mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.bootstrap();

    expect(store.csrfToken).toBe('restored');
    expect(store.status).toBe('ready');
    expect(store.me?.name).toBe('张三');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/me');
  });

  it('刷新恢复：/auth/me 401 清理本端身份与 CSRF 并回登录', async () => {
    sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, 'restored');
    fetchMock.mockResolvedValueOnce(err(401, 'UNAUTHENTICATED') as unknown as Response);

    const store = useSessionStore();
    await store.bootstrap();

    expect(store.status).toBe('guest');
    expect(store.csrfToken).toBeNull();
    expect(store.me).toBeNull();
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBeNull();
  });

  it('刷新时 CSRF 缺失（Cookie 可能仍有效）→ 要求重新登录', async () => {
    // 不预置 sessionStorage CSRF：模拟新标签页/存储被清空
    fetchMock.mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.bootstrap();

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/me'); // 仍先核实身份，不信任 storage
    expect(store.status).toBe('guest');
    expect(store.csrfToken).toBeNull();
    expect(store.me).toBeNull();
  });

  it('403 不当登出：/auth/me 之外接口的 403 不清空身份', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(err(403, 'FORBIDDEN') as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    await expect(store.logout()).rejects.toMatchObject({ code: 'FORBIDDEN', status: 403 });

    expect(store.status).toBe('ready'); // 未被登出
    expect(store.csrfToken).toBe('tok');
  });

  it('退出成功：服务端 204 后清空会话并宣称退出', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(empty() as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const result = await store.logout();

    expect(result.ok).toBe(true);
    expect(store.status).toBe('guest');
    expect(store.csrfToken).toBeNull();
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBeNull();
  });

  it('退出网络失败：不宣称退出、不清空会话，交由页面重试', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockRejectedValueOnce(new TypeError('fetch failed'));

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    await expect(store.logout()).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });

    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('tok');
  });

  it('缺 CSRF 时改密/退出要求重新登录，不杜撰获取接口', async () => {
    const store = useSessionStore(); // 全新 store，无 CSRF
    store.status = 'guest';
    await expect(store.changePassword('a', 'b')).rejects.toMatchObject({ code: 'CSRF_MISSING' });
    await expect(store.logout()).rejects.toMatchObject({ code: 'CSRF_MISSING' });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('双击防重：登录提交期间重复调用不再发请求', async () => {
    let resolveLogin!: (r: Response) => void;
    const pending = new Promise<Response>((res) => {
      resolveLogin = res;
    });
    fetchMock
      .mockImplementationOnce(() => pending)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    const p1 = store.login('alice', 'secret-pass');
    const p2 = store.login('alice', 'secret-pass'); // 应被防重拦截

    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveLogin(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response);
    await p1;
    const second = await p2;
    // 重复调用必须返回明确的“未执行”，不能返回 undefined 让页面误判成功。
    expect(second.executed).toBe(false);
    expect(second.reason).toBe('busy');

    expect(store.status).toBe('ready');
    expect(fetchMock).toHaveBeenCalledTimes(2); // 仅 login + 其内部 fetchMe
  });

  it('旧请求迟到的 401 不覆盖新会话', async () => {
    let resolveOld!: (r: Response) => void;
    const oldMe = new Promise<Response>((res) => {
      resolveOld = res;
    });
    fetchMock
      .mockImplementationOnce(() => oldMe) // R1 /auth/me 挂起
      .mockImplementationOnce(async () => ok({ data: { must_change_password: false, csrf_token: 'new-token' } }) as unknown as Response)
      .mockImplementationOnce(async () => ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    const p1 = store.fetchMe(); // R1 旧请求，先挂起
    await store.login('alice', 'secret-pass'); // 触发新会话

    resolveOld(err(401, 'UNAUTHENTICATED') as unknown as Response);
    const result = await p1;

    expect(result).toBe(false); // 旧结果作废
    expect(store.status).toBe('ready'); // 未被旧 401 覆盖为 guest
    expect(store.csrfToken).toBe('new-token');
    expect(store.me?.name).toBe('张三');
  });

  it('改密进行中退出返回未执行，且不发送退出请求', async () => {
    const pendingChange = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingChange.promise)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const changePromise = store.changePassword('old-pass', 'new-pass-123456');
    const blocked = await store.logout();

    expect(blocked.executed).toBe(false);
    expect(blocked.reason).toBe('busy');
    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/v1/auth/logout')).toBe(false);

    pendingChange.resolve(ok({ data: { must_change_password: false, csrf_token: 'new-tok' } }) as unknown as Response);
    const changed = await changePromise;
    expect(changed.executed).toBe(true);
    expect(changed.result).toBe('changed');
    expect(store.status).toBe('ready');
  });

  it('退出进行中改密返回未执行，且不发送改密请求', async () => {
    const pendingLogout = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingLogout.promise);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const logoutPromise = store.logout();
    const blocked = await store.changePassword('old-pass', 'new-pass-123456');

    expect(blocked.executed).toBe(false);
    expect(blocked.reason).toBe('busy');
    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/v1/auth/password-change')).toBe(false);

    pendingLogout.resolve(empty() as unknown as Response);
    const loggedOut = await logoutPromise;
    expect(loggedOut.ok).toBe(true);
    expect(store.status).toBe('guest');
  });

  it('旧退出成功结果不清理新代次会话', async () => {
    const pendingLogout = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingLogout.promise);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const logoutPromise = store.logout();

    // 模拟旧退出在途时本端已建立新代次会话（例如重新登录/重新核验成功）。
    store.clearSession();
    store.status = 'ready';
    store.csrfToken = 'new-token';
    store.me = { ...mePayload.data, identity_type: 'staff' as const, account_id: '99', name: '新会话' };

    pendingLogout.resolve(empty() as unknown as Response);
    const result = await logoutPromise;

    expect(result.superseded).toBe(true);
    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('new-token');
    expect(store.me?.account_id).toBe('99');
  });

  it('旧退出 401 结果不清理新代次会话', async () => {
    const pendingLogout = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingLogout.promise);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const logoutPromise = store.logout();

    store.clearSession();
    store.status = 'ready';
    store.csrfToken = 'new-token';
    store.me = { ...mePayload.data, identity_type: 'staff' as const, account_id: '99', name: '新会话' };

    pendingLogout.resolve(err(401, 'UNAUTHENTICATED') as unknown as Response);
    const result = await logoutPromise;

    expect(result.superseded).toBe(true);
    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('new-token');
  });

  it('旧改密成功结果不覆盖新代次 CSRF/身份，也不跳转', async () => {
    const pendingChange = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingChange.promise);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const changePromise = store.changePassword('old-pass', 'new-pass-123456');

    store.clearSession();
    store.status = 'ready';
    store.csrfToken = 'new-token';
    store.me = { ...mePayload.data, identity_type: 'staff' as const, account_id: '99', name: '新会话' };
    sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, 'new-token');

    pendingChange.resolve(ok({ data: { must_change_password: false, csrf_token: 'late-token' } }) as unknown as Response);
    const result = await changePromise;

    expect(result.superseded).toBe(true);
    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('new-token');
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBe('new-token');
    expect(store.me?.account_id).toBe('99');
    // 旧结果不得再触发 /auth/me 核验
    expect(fetchMock.mock.calls.filter((call) => call[0] === '/api/v1/auth/me')).toHaveLength(1);
  });

  it('旧改密 401 结果不清理新代次会话', async () => {
    const pendingChange = deferred<Response>();
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockImplementationOnce(() => pendingChange.promise);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const changePromise = store.changePassword('old-pass', 'new-pass-123456');

    store.clearSession();
    store.status = 'ready';
    store.csrfToken = 'new-token';
    store.me = { ...mePayload.data, identity_type: 'staff' as const, account_id: '99', name: '新会话' };

    pendingChange.resolve(err(401, 'UNAUTHENTICATED') as unknown as Response);
    const result = await changePromise;

    expect(result.superseded).toBe(true);
    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('new-token');
    // 旧 401 不得再探测 /auth/me
    expect(fetchMock.mock.calls.filter((call) => call[0] === '/api/v1/auth/me')).toHaveLength(1);
  });

  it('登录后 /auth/me 网络失败：不 ready、保留 CSRF，可重试进入 ready', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok-1' } }) as unknown as Response)
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    const outcome = await store.login('alice', 'secret-pass');

    expect(outcome.executed).toBe(true);
    expect(outcome.superseded).toBe(false);
    expect(store.status).toBe('unverified');
    expect(store.me).toBeNull();
    expect(store.csrfToken).toBe('tok-1');
    expect(store.verificationMessage).toContain('身份确认未完成');

    const retried = await store.retryVerify();
    expect(retried.executed).toBe(true);
    expect(store.status).toBe('ready');
    expect(store.me?.name).toBe('张三');
  });

  it('bootstrap 失败为可见 unverified、保留 CSRF，且并行只恢复一次，重试可恢复', async () => {
    sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, 'restored');
    const pendingMe = deferred<Response>();
    fetchMock
      .mockImplementationOnce(() => pendingMe.promise)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    const first = store.bootstrap();
    const second = store.bootstrap();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    pendingMe.reject(new TypeError('fetch failed'));
    await Promise.all([first, second]);

    expect(store.status).toBe('unverified');
    expect(store.me).toBeNull();
    expect(store.csrfToken).toBe('restored');
    expect(store.verificationMessage).toContain('身份确认未完成');

    const retried = await store.retryVerify();
    expect(retried.executed).toBe(true);
    expect(store.status).toBe('ready');
  });

  it('旧 bootstrap 失败不覆盖新代次状态', async () => {
    sessionStorage.setItem(SESSION_CSRF_STORAGE_KEY, 'restored');
    const pendingMe = deferred<Response>();
    fetchMock.mockImplementationOnce(() => pendingMe.promise);

    const store = useSessionStore();
    const restore = store.bootstrap();
    store.clearSession();
    expect(store.status).toBe('guest');

    pendingMe.reject(new TypeError('fetch failed'));
    await restore;

    expect(store.status).toBe('guest');
    expect(store.verificationIssue).toBeNull();
  });

  it('patient 身份不进入管理端 ready', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok-1' } }) as unknown as Response)
      .mockResolvedValueOnce(
        ok({
          data: {
            account_id: '77',
            name: '患者',
            identity_type: 'patient',
            roles: [{ role_code: 'patient', branch_ids: [] }],
            must_change_password: false,
          },
        }) as unknown as Response,
      );

    const store = useSessionStore();
    const outcome = await store.login('alice', 'secret-pass');

    expect(outcome.executed).toBe(true);
    expect(store.status).toBe('guest');
    expect(store.me).toBeNull();
    expect(store.csrfToken).toBeNull();
    expect(store.verificationIssue).toBe('non-staff');
    expect(store.verificationMessage).toContain('员工');
  });

  it('改密 401 + /auth/me 有效 → 仅密码错，保留会话与 CSRF', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(err(401, 'UNAUTHENTICATED') as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const outcome = await store.changePassword('wrong-old', 'new-pass-123456');

    expect(outcome.result).toBe('password-rejected');
    expect(store.status).toBe('ready');
    expect(store.csrfToken).toBe('tok');
    expect(store.me?.name).toBe('张三');
  });

  it('改密 401 + /auth/me 401 → 会话已失效，清理并回登录', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(err(401, 'UNAUTHENTICATED') as unknown as Response)
      .mockResolvedValueOnce(err(401, 'UNAUTHENTICATED') as unknown as Response);

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const outcome = await store.changePassword('wrong-old', 'new-pass-123456');

    expect(outcome.result).toBe('session-invalid');
    expect(store.status).toBe('guest');
    expect(store.csrfToken).toBeNull();
    expect(store.me).toBeNull();
  });

  it('改密 401 后 /auth/me 网络失败 → unconfirmed，保留 CSRF 不登出', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(err(401, 'UNAUTHENTICATED') as unknown as Response)
      .mockRejectedValueOnce(new TypeError('fetch failed'));

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const outcome = await store.changePassword('wrong-old', 'new-pass-123456');

    expect(outcome.result).toBe('unconfirmed');
    expect(store.status).toBe('unverified');
    expect(store.csrfToken).toBe('tok');
    expect(store.me).toBeNull();
  });

  it('改密成功但身份核验失败 → changed + unverified，不进入 ready', async () => {
    fetchMock
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'tok' } }) as unknown as Response)
      .mockResolvedValueOnce(ok(mePayload) as unknown as Response)
      .mockResolvedValueOnce(ok({ data: { must_change_password: false, csrf_token: 'new-tok' } }) as unknown as Response)
      .mockRejectedValueOnce(new TypeError('fetch failed'));

    const store = useSessionStore();
    await store.login('alice', 'secret-pass');
    const outcome = await store.changePassword('old-pass', 'new-pass-123456');

    expect(outcome.result).toBe('changed');
    expect(store.status).toBe('unverified');
    expect(store.me).toBeNull();
    expect(store.csrfToken).toBe('new-tok');
    expect(sessionStorage.getItem(SESSION_CSRF_STORAGE_KEY)).toBe('new-tok');
  });

  it('isSysadmin 只由 /auth/me 的 sysadmin 角色决定，不凭其他角色推断', () => {
    const store = useSessionStore();
    expect(store.isSysadmin).toBe(false);

    store.me = {
      account_id: '1',
      name: '管理员',
      identity_type: 'staff',
      roles: [{ role_code: 'owner', branch_ids: ['1'] }],
      must_change_password: false,
    };
    expect(store.isSysadmin).toBe(false);

    store.me = {
      ...store.me,
      roles: [{ role_code: 'sysadmin', branch_ids: [] }],
    };
    expect(store.isSysadmin).toBe(true);
  });
});
