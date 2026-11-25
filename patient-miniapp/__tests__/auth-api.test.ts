import { describe, expect, it, vi } from 'vitest';
import { createAuthApi } from '../miniprogram/services/auth-api';
import type { HttpClient } from '../miniprogram/services/http';

function fakeClient(): HttpClient {
  return {
    request: vi.fn(),
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    list: vi.fn(),
  } as unknown as HttpClient;
}

const me = {
  account_id: '100',
  name: '合成患者',
  identity_type: 'patient',
  must_change_password: false,
  roles: [],
};

describe('患者身份 API 契约', () => {
  it('微信登录严格区分 session/registration 两分支', async () => {
    const client = fakeClient();
    const api = createAuthApi(client);
    vi.mocked(client.post).mockResolvedValueOnce({
      data: { kind: 'session', session_token: 's', expires_at: '2099-01-01T00:00:00Z' },
    });
    expect(await api.wechatLogin('code-1')).toMatchObject({ kind: 'session', session_token: 's' });
    expect(client.post).toHaveBeenCalledWith('/auth/wechat', { code: 'code-1' }, { auth: false });

    vi.mocked(client.post).mockResolvedValueOnce({
      data: {
        kind: 'registration',
        registration_token: 'r',
        registration_expires_at: '2099-01-01T00:00:00Z',
      },
    });
    expect(await api.wechatLogin('code-2')).toMatchObject({ kind: 'registration', registration_token: 'r' });
  });

  it('拒绝同时含 session 与 registration 字段的混合响应', async () => {
    const client = fakeClient();
    const api = createAuthApi(client);
    vi.mocked(client.post).mockResolvedValueOnce({
      data: {
        kind: 'session',
        session_token: 's',
        expires_at: '2099-01-01T00:00:00Z',
        registration_token: 'r',
      },
    });
    await expect(api.wechatLogin('x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('注册发送契约字段，不发送 openid/app_id/account_id，不附加 Bearer', async () => {
    const client = fakeClient();
    const api = createAuthApi(client);
    vi.mocked(client.post).mockResolvedValue({
      data: { session_token: 's', patient_id: '200', expires_at: '2099-01-01T00:00:00Z' },
    });

    await api.register({
      registration_token: 'reg',
      name: '张三',
      phone: '13800000000',
      birth_date: '1990-01-02',
      allergy_status: '有',
      allergy_notes: '青霉素',
    });

    const body = vi.mocked(client.post).mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body).toEqual({
      registration_token: 'reg',
      name: '张三',
      phone: '13800000000',
      birth_date: '1990-01-02',
      allergy_status: '有',
      allergy_notes: '青霉素',
    });
    expect(body['openid']).toBeUndefined();
    expect(body['app_id']).toBeUndefined();
    expect(body['account_id']).toBeUndefined();
    expect(client.post).toHaveBeenCalledWith('/auth/patient-register', body, { auth: false });
  });

  it('旧档绑定只发送两个凭证', async () => {
    const client = fakeClient();
    const api = createAuthApi(client);
    vi.mocked(client.post).mockResolvedValue({
      data: { session_token: 's', patient_id: '200' },
    });

    await api.bind({ registration_token: 'reg', binding_token: 'bind' });

    expect(client.post).toHaveBeenCalledWith(
      '/auth/patient-bind',
      { registration_token: 'reg', binding_token: 'bind' },
      { auth: false },
    );
  });

  it('/auth/me 解析患者与员工身份，缺字段拒绝', async () => {
    const client = fakeClient();
    const api = createAuthApi(client);
    vi.mocked(client.get).mockResolvedValueOnce({ data: me });
    expect((await api.me()).identity_type).toBe('patient');

    vi.mocked(client.get).mockResolvedValueOnce({ data: { ...me, identity_type: 'staff' } });
    expect((await api.me()).identity_type).toBe('staff');

    vi.mocked(client.get).mockResolvedValueOnce({ data: { account_id: '1', name: 'x' } });
    await expect(api.me()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
});
