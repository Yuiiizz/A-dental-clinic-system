import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthFlow } from '../miniprogram/services/auth-flow';
import { createAuthApi } from '../miniprogram/services/auth-api';
import { ApiError, createHttpClient } from '../miniprogram/services/http';
import { PatientSessionStore } from '../miniprogram/services/session';
import type { AuthApi } from '../miniprogram/services/auth-api';
import type { Me, PatientRegistrationForm, PatientSessionResult, WechatLoginResult } from '../miniprogram/services/auth-types';

const patientMe: Me = {
  account_id: '100',
  name: '合成患者',
  identity_type: 'patient',
  must_change_password: false,
  roles: [],
};

const future = '2099-01-01T00:00:00Z';

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function registrationLogin(): WechatLoginResult {
  return { kind: 'registration', registration_token: 'reg-token', registration_expires_at: future };
}

function sessionLogin(): WechatLoginResult {
  return { kind: 'session', session_token: 'session-token', expires_at: future };
}

function patientSession(): PatientSessionResult {
  return { session_token: 'session-token', patient_id: '200', expires_at: future };
}

function fakeApi(overrides: Partial<AuthApi> = {}): AuthApi {
  return {
    wechatLogin: vi.fn().mockResolvedValue(sessionLogin()),
    register: vi.fn().mockResolvedValue(patientSession()),
    bind: vi.fn().mockResolvedValue(patientSession()),
    me: vi.fn().mockResolvedValue(patientMe),
    ...overrides,
  };
}

function activeContext() {
  let active = true;
  return {
    context: { active: () => active },
    deactivate: () => {
      active = false;
    },
  };
}

describe('患者认证流程', () => {
  let session: PatientSessionStore;
  let wxLogin: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    session = new PatientSessionStore();
    wxLogin = vi.fn().mockResolvedValue('wx-code');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function flow(api: AuthApi): AuthFlow {
    return new AuthFlow({ wxLogin, api, session });
  }

  it('已绑定分支只建立患者会话并核验 /auth/me，不产生注册上下文', async () => {
    const api = fakeApi();
    const f = flow(api);
    const result = await f.login(activeContext().context);

    expect(result.status).toBe('session');
    expect(session.token).toBe('session-token');
    expect(session.me?.name).toBe('合成患者');
    expect(f.hasRegistration).toBe(false);
    expect(api.wechatLogin).toHaveBeenCalledWith('wx-code');
    expect(api.me).toHaveBeenCalledTimes(1);
  });

  it('未绑定分支只保存内存注册上下文，不建立会话、不调用 me', async () => {
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()) });
    const f = flow(api);
    const result = await f.login(activeContext().context);

    expect(result.status).toBe('registration');
    expect(f.registrationToken).toBe('reg-token');
    expect(session.token).toBeNull();
    expect(api.me).not.toHaveBeenCalled();
  });

  it('注册按契约序列化并条件校验过敏说明', async () => {
    const register = vi.fn().mockResolvedValue(patientSession());
    const api = fakeApi({
      wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
      register,
    });
    const f = flow(api);
    await f.login(activeContext().context);
    const form: PatientRegistrationForm = {
      name: ' 张三 ',
      phone: ' 13800000000 ',
      birth_date: '1990-01-02',
      allergy_status: '有',
      allergy_notes: ' 青霉素 ',
    };

    const result = await f.register(activeContext().context, form);

    expect(result.status).toBe('session');
    expect(register).toHaveBeenCalledWith({
      registration_token: 'reg-token',
      name: '张三',
      phone: '13800000000',
      birth_date: '1990-01-02',
      allergy_status: '有',
      allergy_notes: '青霉素',
    });

    const invalid = await flow(api).register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '有',
    });
    expect(invalid.status).toBe('validation');
    expect(register).toHaveBeenCalledTimes(1);
  });

  it('旧档绑定只提交 registration_token + binding_token，不提交手机号', async () => {
    const bind = vi.fn().mockResolvedValue(patientSession());
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), bind });
    const f = flow(api);
    await f.login(activeContext().context);

    const result = await f.bind(activeContext().context, ' binding-token ');

    expect(result.status).toBe('session');
    expect(bind).toHaveBeenCalledWith({
      registration_token: 'reg-token',
      binding_token: 'binding-token',
    });
    expect(JSON.stringify(bind.mock.calls[0]?.[0])).not.toContain('phone');
  });

  it('注册超时返回结果未确认，清除注册上下文且只调用一次', async () => {
    const register = vi.fn().mockRejectedValue(new ApiError('TIMEOUT', 0, 'raw-secret'));
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), register });
    const f = flow(api);
    await f.login(activeContext().context);

    const result = await f.register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });

    expect(result.status).toBe('unknown');
    expect(result.message).toContain('结果未确认');
    expect(result.message).not.toContain('raw-secret');
    expect(register).toHaveBeenCalledTimes(1);
    expect(f.hasRegistration).toBe(false);
  });

  it('绑定 401 清理注册上下文且不重试', async () => {
    const bind = vi.fn().mockRejectedValue(new ApiError('BINDING_TOKEN_INVALID', 401, 'raw-secret'));
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), bind });
    const f = flow(api);
    await f.login(activeContext().context);

    const result = await f.bind(activeContext().context, 'bad-token');

    expect(result.status).toBe('session-expired');
    expect(bind).toHaveBeenCalledTimes(1);
    expect(f.hasRegistration).toBe(false);
    expect(result.message).not.toContain('raw-secret');
  });

  it('注册未知/失效凭证 401 清理上下文且不重试', async () => {
    const register = vi.fn().mockRejectedValue(new ApiError('REGISTRATION_TOKEN_INVALID', 401));
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), register });
    const f = flow(api);
    await f.login(activeContext().context);

    const result = await f.register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });

    expect(result.status).toBe('session-expired');
    expect(register).toHaveBeenCalledTimes(1);
    expect(f.hasRegistration).toBe(false);
  });

  it('403 不当作登出；网络失败不建立会话', async () => {
    const forbidden = fakeApi({
      me: vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403)),
    });
    const f = flow(forbidden);
    session.setSession('kept-token');
    const denied = await f.verifySession(activeContext().context);
    expect(denied.status).toBe('forbidden');
    expect(session.token).toBe('kept-token');

    session.clear();
    const network = flow(
      fakeApi({ wechatLogin: vi.fn().mockRejectedValue(new ApiError('NETWORK_ERROR', 0)) }),
    );
    const result = await network.login(activeContext().context);
    expect(result.status).toBe('unverified');
    expect(session.token).toBeNull();
  });

  it('会话核验网络失败保留令牌并提示未确认，不误登出', async () => {
    const api = fakeApi({ me: vi.fn().mockRejectedValue(new ApiError('NETWORK_ERROR', 0, 'raw')) });
    const f = flow(api);
    session.setSession('kept-token', undefined, future);

    const result = await f.verifySession(activeContext().context);

    expect(result.status).toBe('unverified');
    expect(session.token).toBe('kept-token');
  });

  it('会话核验 401 清理令牌；员工身份拒绝患者端', async () => {
    const f401 = flow(fakeApi({ me: vi.fn().mockRejectedValue(new ApiError('UNAUTHENTICATED', 401)) }));
    session.setSession('expired-token');
    expect((await f401.verifySession(activeContext().context)).status).toBe('session-expired');
    expect(session.token).toBeNull();

    const staff = fakeApi({ me: vi.fn().mockResolvedValue({ ...patientMe, identity_type: 'staff' }) });
    const fStaff = flow(staff);
    session.setSession('staff-token');
    expect((await fStaff.verifySession(activeContext().context)).status).toBe('staff-rejected');
    expect(session.token).toBeNull();
  });

  it('同一时刻只执行一次认证；页面卸载后的迟到结果不写会话', async () => {
    let resolveLogin!: (value: WechatLoginResult) => void;
    const pending = new Promise<WechatLoginResult>((resolve) => {
      resolveLogin = resolve;
    });
    const api = fakeApi({ wechatLogin: vi.fn().mockReturnValue(pending) });
    const f = flow(api);
    const page = activeContext();
    const first = f.login(page.context);
    expect((await f.login(page.context)).status).toBe('busy');

    page.deactivate();
    resolveLogin(sessionLogin());
    expect((await first).status).toBe('stale');
    expect(session.token).toBeNull();
  });

  it('结果不明后重新登录核对：已绑定则恢复，未绑定则回到注册分支', async () => {
    const register = vi.fn().mockRejectedValue(new ApiError('NETWORK_ERROR', 0, 'raw'));
    const wechatLogin = vi
      .fn()
      .mockResolvedValueOnce(registrationLogin())
      .mockResolvedValueOnce(sessionLogin());
    const api = fakeApi({ wechatLogin, register });
    const f = flow(api);
    await f.login(activeContext().context);
    await f.register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });

    expect((await f.recheck(activeContext().context)).status).toBe('session');

    const unboundFlow = flow(
      fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()) }),
    );
    await unboundFlow.login(activeContext().context);
    const unbound = await unboundFlow.recheck(activeContext().context);
    expect(unbound.status).toBe('registration');
    expect(unbound.message).toContain('仍未绑定');
  });

  it('session 与 registration 分支互斥，新身份开始清除旧身份', async () => {
    const wechatLogin = vi
      .fn()
      .mockResolvedValueOnce(registrationLogin())
      .mockResolvedValueOnce(sessionLogin());
    const api = fakeApi({ wechatLogin });
    const f = flow(api);
    session.setSession('old-session');

    expect((await f.login(activeContext().context)).status).toBe('registration');
    expect(session.token).toBeNull();
    expect(f.hasRegistration).toBe(true);

    expect((await f.login(activeContext().context)).status).toBe('session');
    expect(f.hasRegistration).toBe(false);
    expect(session.token).toBe('session-token');
  });

  it('迟到 401 不清后来登录的会话', async () => {
    const pending = deferred<Me>();
    const api = fakeApi({ me: vi.fn().mockReturnValue(pending.promise) });
    const f = flow(api);
    session.setSession('old-token');
    const request = f.verifySession(activeContext().context);

    session.setSession('new-token');
    pending.reject(new ApiError('UNAUTHENTICATED', 401));

    expect((await request).status).toBe('stale');
    expect(session.token).toBe('new-token');
  });

  it('真实 createHttpClient + mock wx.request：旧 401 不清理新 Bearer', async () => {
    let respond!: () => void;
    const requestMock = vi.fn((opts: { success: (res: unknown) => void }) => {
      respond = () =>
        opts.success({
          statusCode: 401,
          data: { code: 'UNAUTHENTICATED', message: '未登录' },
          header: {},
        });
    });
    vi.stubGlobal('wx', { request: requestMock });
    const client = createHttpClient({
      baseUrl: 'https://test.invalid/api/v1',
      getBearerToken: () => session.token,
    });
    const f = new AuthFlow({ wxLogin, api: createAuthApi(client), session });
    session.setSession('old-token');
    const request = f.verifySession(activeContext().context);
    session.setSession('new-token');
    respond();

    expect((await request).status).toBe('stale');
    expect(session.token).toBe('new-token');
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('注册/绑定畸形响应或 5xx 视为结果不明并废弃旧凭证', async () => {
    const register = vi.fn().mockRejectedValue(new ApiError('INVALID_RESPONSE', 200));
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), register });
    const f = flow(api);
    await f.login(activeContext().context);
    const result = await f.register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });
    expect(result.status).toBe('unknown');
    expect(f.hasRegistration).toBe(false);

    const bind = vi.fn().mockRejectedValue(new ApiError('INTERNAL_ERROR', 500));
    const bindFlow = flow(
      fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), bind }),
    );
    await bindFlow.login(activeContext().context);
    const bound = await bindFlow.bind(activeContext().context, 'bind-token');
    expect(bound.status).toBe('unknown');
    expect(bindFlow.hasRegistration).toBe(false);
  });

  it('提交中卸载/取消不复活旧注册凭证，返回 stale 且清除上下文', async () => {
    const pending = deferred<PatientSessionResult>();
    const register = vi.fn().mockReturnValue(pending.promise);
    const api = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()), register });
    const f = flow(api);
    await f.login(activeContext().context);
    const page = activeContext();
    const request = f.register(page.context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });
    page.deactivate();
    pending.resolve(patientSession());

    expect((await request).status).toBe('stale');
    expect(f.hasRegistration).toBe(false);
    expect(session.token).toBeNull();

    const secondPending = deferred<PatientSessionResult>();
    const secondFlow = flow(
      fakeApi({
        wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
        register: vi.fn().mockReturnValue(secondPending.promise),
      }),
    );
    await secondFlow.login(activeContext().context);
    const second = secondFlow.register(activeContext().context, {
      name: '张三',
      phone: '13800000000',
      allergy_status: '无已知',
    });
    secondFlow.cancelPending();
    secondPending.resolve(patientSession());
    expect((await second).status).toBe('stale');
    expect(secondFlow.hasRegistration).toBe(false);
  });

  it('A 旧注册迟到成功/失败不清 B 的新注册状态', async () => {
    const firstRegister = deferred<PatientSessionResult>();
    const firstApi = fakeApi({
      wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
      register: vi.fn().mockReturnValue(firstRegister.promise),
    });
    const firstFlow = flow(firstApi);
    const pageA = activeContext();
    await firstFlow.login(pageA.context);
    const firstRequest = firstFlow.register(pageA.context, {
      name: '甲',
      phone: '13800000001',
      allergy_status: '无已知',
    });
    firstFlow.cancelPending(pageA.context);

    const secondApi = fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()) });
    const secondFlow = flow(secondApi);
    await secondFlow.login(activeContext().context);
    expect(secondFlow.registrationToken).toBe('reg-token');

    firstRegister.resolve(patientSession());
    expect((await firstRequest).status).toBe('stale');
    expect(secondFlow.registrationToken).toBe('reg-token');

    const bindPending = deferred<PatientSessionResult>();
    const bindFlow = flow(
      fakeApi({
        wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
        bind: vi.fn().mockReturnValue(bindPending.promise),
      }),
    );
    await bindFlow.login(activeContext().context);
    const bindRequest = bindFlow.bind(activeContext().context, 'bind-token');
    bindFlow.cancelPending();
    const newRegistrationFlow = flow(
      fakeApi({ wechatLogin: vi.fn().mockResolvedValue(registrationLogin()) }),
    );
    await newRegistrationFlow.login(activeContext().context);
    bindPending.reject(new ApiError('NETWORK_ERROR', 0));
    expect((await bindRequest).status).toBe('stale');
    expect(newRegistrationFlow.registrationToken).toBe('reg-token');
  });

  it('A 迟到不清 B 提交中的互斥状态', async () => {
    const firstRegister = deferred<PatientSessionResult>();
    const firstFlow = flow(
      fakeApi({
        wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
        register: vi.fn().mockReturnValue(firstRegister.promise),
      }),
    );
    const pageA = activeContext();
    await firstFlow.login(pageA.context);
    const firstRequest = firstFlow.register(pageA.context, {
      name: '甲',
      phone: '13800000001',
      allergy_status: '无已知',
    });
    firstFlow.cancelPending(pageA.context);

    const secondRegister = deferred<PatientSessionResult>();
    const secondFlow = flow(
      fakeApi({
        wechatLogin: vi.fn().mockResolvedValue(registrationLogin()),
        register: vi.fn().mockReturnValue(secondRegister.promise),
      }),
    );
    await secondFlow.login(activeContext().context);
    const secondRequest = secondFlow.register(activeContext().context, {
      name: '乙',
      phone: '13800000002',
      allergy_status: '无已知',
    });
    firstRegister.resolve(patientSession());
    expect((await firstRequest).status).toBe('stale');
    expect(secondFlow.registrationToken).toBeNull();

    secondRegister.resolve(patientSession());
    expect((await secondRequest).status).toBe('session');
    expect(session.token).toBe('session-token');
  });

  it('取消只取消所属页面操作，旧页面卸载不影响新页面请求', async () => {
    const pendingA = deferred<WechatLoginResult>();
    const pendingB = deferred<WechatLoginResult>();
    const api = fakeApi({
      wechatLogin: vi.fn().mockReturnValueOnce(pendingA.promise).mockReturnValueOnce(pendingB.promise),
    });
    const f = flow(api);
    const ownerA = {};
    const ownerB = {};
    let activeA = true;
    let activeB = true;
    const ctxA = { active: () => activeA, owner: ownerA, generation: 1 };
    const ctxB = { active: () => activeB, owner: ownerB, generation: 1 };

    const requestA = f.login(ctxA);
    f.cancelPending(ctxA);
    const requestB = f.login(ctxB);
    f.cancelPending(ctxA); // A 的重复取消/卸载不能取消 B
    pendingA.resolve(sessionLogin());
    pendingB.resolve(sessionLogin());

    expect((await requestA).status).toBe('stale');
    expect((await requestB).status).toBe('session');
    expect(f.hasSession).toBe(true);
  });

  it('取消发生在 wxLogin 返回后时不再发身份交换请求', async () => {
    const code = deferred<string>();
    wxLogin.mockReturnValue(code.promise);
    const api = fakeApi();
    const f = flow(api);
    const page = activeContext();
    const request = f.login(page.context);
    page.deactivate();
    f.cancelPending(page.context);
    code.resolve('late-code');

    expect((await request).status).toBe('stale');
    expect(api.wechatLogin).not.toHaveBeenCalled();
  });
});
