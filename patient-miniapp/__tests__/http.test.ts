import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createHttpClient, type HttpClient } from '../miniprogram/services/http';

interface MockRequestOptions {
  url: string;
  method: string;
  data?: unknown;
  header?: Record<string, string>;
  timeout?: number;
  success: (res: { statusCode: number; data: unknown; header?: Record<string, unknown> }) => void;
  fail: (err: { errMsg: string }) => void;
}

const requestMock = vi.fn((_opts: MockRequestOptions) => ({}));

function lastOptions(): MockRequestOptions {
  const call = requestMock.mock.calls[requestMock.mock.calls.length - 1];
  return call[0];
}

function respond(statusCode: number, data: unknown, header: Record<string, unknown> = {}) {
  return (opts: MockRequestOptions) => opts.success({ statusCode, data, header });
}

function failWith(errMsg: string) {
  return (opts: MockRequestOptions) => opts.fail({ errMsg });
}

describe('患者小程序 http 客户端', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    requestMock.mockReset();
  });

  beforeEach(() => {
    vi.stubGlobal('wx', { request: requestMock });
  });

  it('AnyService 透传 Bearer、幂等键与路径，解析业务响应', async () => {
    const init = vi.fn();
    const callContainer = vi.fn().mockResolvedValue({
      statusCode: 201,
      data: { data: { appointment_id: 5 }, request_id: 'cloud-1' },
      header: {},
    });
    vi.stubGlobal('wx', { request: requestMock, cloud: { init, callContainer } });
    const client = createHttpClient({
      cloudAccess: { envId: 'test-env', serviceName: 'dental-api' },
      getBearerToken: () => 'patient-token',
    });
    const result = await client.post('/me/appointments', { slot: '09:00' }, { idempotencyKey: 'same-key' });
    expect(result.data).toEqual({ appointment_id: 5 });
    expect(callContainer).toHaveBeenCalledWith(expect.objectContaining({
      path: '/api/v1/me/appointments', method: 'POST', data: { slot: '09:00' },
      header: expect.objectContaining({
        'X-WX-SERVICE': 'tcbanyservice', 'X-AnyService-Name': 'dental-api',
        Authorization: 'Bearer patient-token', 'Idempotency-Key': 'same-key',
      }),
    }));
    expect(requestMock).not.toHaveBeenCalled();
  });

  it('AnyService 的业务 409 原样交给调用方且不自动重试', async () => {
    const callContainer = vi.fn().mockResolvedValue({
      statusCode: 409, data: { code: 'SLOT_UNAVAILABLE', message: '该时段已不可预约' }, header: {},
    });
    vi.stubGlobal('wx', { request: requestMock, cloud: { init: vi.fn(), callContainer } });
    const client = createHttpClient({ cloudAccess: { envId: 'test-env', serviceName: 'dental-api' } });
    await expect(client.post('/me/appointments', {}, { idempotencyKey: 'same-key' }))
      .rejects.toMatchObject({ code: 'SLOT_UNAVAILABLE', status: 409 });
    expect(callContainer).toHaveBeenCalledTimes(1);
  });

  it('AnyService 一直不返回时在本地超时，迟到响应不再改写登录状态', async () => {
    vi.useFakeTimers();
    let resolveCloud!: (value: unknown) => void;
    const callContainer = vi.fn().mockReturnValue(new Promise((resolve) => { resolveCloud = resolve; }));
    const onUnauthorized = vi.fn();
    vi.stubGlobal('wx', { request: requestMock, cloud: { init: vi.fn(), callContainer } });
    const client = createHttpClient({ cloudAccess: { envId: 'test-env', serviceName: 'dental-api' }, timeoutMs: 15, onUnauthorized });
    const expectation = expect(client.post('/me/appointments/8/checkin', {}, { idempotencyKey: 'same-key' }))
      .rejects.toMatchObject({ code: 'TIMEOUT', status: 0 });
    await vi.advanceTimersByTimeAsync(1015);
    await expectation;
    resolveCloud({ statusCode: 401, data: { code: 'UNAUTHENTICATED', message: '迟到响应' } });
    await Promise.resolve();
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(callContainer).toHaveBeenCalledTimes(1);
  });

  it('解析成功响应 { data, request_id }', async () => {
    requestMock.mockImplementationOnce(respond(200, { data: { appointment_id: '5001' }, request_id: 'req-1' }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const result = await client.get<{ appointment_id: string }>('/me/appointments/5001');
    expect(result.data).toEqual({ appointment_id: '5001' });
    expect(result.requestId).toBe('req-1');
  });

  it('204 空响应返回 undefined 数据，request_id 取自 X-Request-Id 头', async () => {
    requestMock.mockImplementationOnce(respond(204, '', { 'X-Request-Id': 'req-204' }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const result = await client.delete('/me/doctor-preferences/1');
    expect(result.data).toBeUndefined();
    expect(result.requestId).toBe('req-204');
  });

  it('业务错误解析出 code/status/request_id/field_errors', async () => {
    requestMock.mockImplementationOnce(
      respond(422, {
        code: 'VALIDATION_FAILED',
        message: '字段不合法',
        request_id: 'req-2',
        field_errors: [{ field: 'name', message: '必填' }],
      }),
    );
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    await expect(client.post('/me/appointments', {})).rejects.toMatchObject({
      name: 'ApiError',
      code: 'VALIDATION_FAILED',
      status: 422,
      requestId: 'req-2',
      fieldErrors: [{ field: 'name', message: '必填' }],
    });
  });

  it('401 触发登录失效回调，403 不触发', async () => {
    const onUnauthorized = vi.fn();

    requestMock.mockImplementationOnce(respond(401, { code: 'UNAUTHENTICATED', message: '未登录' }));
    const client401 = createHttpClient({ baseUrl: 'https://test.invalid/api/v1', onUnauthorized });
    await expect(client401.get('/me/appointments')).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    requestMock.mockImplementationOnce(respond(403, { code: 'FORBIDDEN', message: '无权操作' }));
    const client403 = createHttpClient({ baseUrl: 'https://test.invalid/api/v1', onUnauthorized });
    await expect(client403.get('/me/restrictions')).rejects.toMatchObject({ code: 'FORBIDDEN', status: 403 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('409 保留冲突信息且不自动重试', async () => {
    requestMock.mockImplementationOnce(
      respond(409, { code: 'POLICY_CHANGED', message: '政策已更新', request_id: 'req-3' }),
    );
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    await expect(client.post('/me/appointments', { schedule_id: '1' })).rejects.toMatchObject({
      code: 'POLICY_CHANGED',
      status: 409,
      requestId: 'req-3',
    });
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('业务请求附带患者 Bearer，公开/注册绑定流程不误用业务令牌，不发送员工 Cookie', async () => {
    requestMock.mockImplementation(respond(200, { data: {} }));
    const client: HttpClient = createHttpClient({
      baseUrl: 'https://test.invalid/api/v1',
      getBearerToken: () => 'patient-token',
    });

    await client.get('/me/appointments');
    expect(lastOptions().header?.Authorization).toBe('Bearer patient-token');
    expect(lastOptions().header?.Cookie).toBeUndefined();

    await client.post('/auth/wechat', { code: 'x' }, { auth: false });
    expect(lastOptions().header?.Authorization).toBeUndefined();

    await client.post('/auth/patient-register', { registration_token: 'r', name: 'n', allergy_status: '无已知' }, { auth: false });
    expect(lastOptions().header?.Authorization).toBeUndefined();
  });

  it('Idempotency-Key 由调用方原样保留，未提供则不添加', async () => {
    requestMock.mockImplementation(respond(200, { data: {} }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });

    await client.post('/me/appointments', { schedule_id: '1' }, { idempotencyKey: 'key-abc' });
    expect(lastOptions().header?.['Idempotency-Key']).toBe('key-abc');

    await client.post('/me/appointments', { schedule_id: '1' });
    expect(lastOptions().header?.['Idempotency-Key']).toBeUndefined();
  });

  it('网络失败抛出 NETWORK_ERROR，超时抛出 TIMEOUT', async () => {
    requestMock.mockImplementationOnce(failWith('request:fail network error'));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    await expect(client.get('/public/branches')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });

    requestMock.mockImplementationOnce(failWith('request:fail timeout'));
    await expect(client.get('/public/branches')).rejects.toMatchObject({ code: 'TIMEOUT', status: 0 });
  });

  it('非 JSON 错误页不回传原始 HTML', async () => {
    requestMock.mockImplementationOnce(respond(502, '<html><body>Bad Gateway</body></html>'));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const err = await client.get('/public/branches').catch((e: ApiError) => e);
    expect(err).toMatchObject({ code: 'REQUEST_FAILED', status: 502 });
    expect(err.message).not.toContain('<html>');
    expect(err.message).not.toContain('Bad Gateway');
  });

  it('baseUrl 未配置时明确报错，不静默请求', async () => {
    const client: HttpClient = createHttpClient({ baseUrl: '' });
    await expect(client.get('/me/appointments')).rejects.toMatchObject({ code: 'CONFIG_ERROR', status: 0 });
    expect(requestMock).not.toHaveBeenCalled();
  });

  it('查询参数序列化并跳过空值', async () => {
    requestMock.mockImplementation(respond(200, { data: { items: [] } }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    await client.get('/public/branches', { query: { limit: 20, branch_id: 'B1', empty: undefined } });
    expect(lastOptions().url).toBe('https://test.invalid/api/v1/public/branches?limit=20&branch_id=B1');
  });

  it('list 解析真实契约格式的顶层 { items, next_cursor }，不按 data 包络', async () => {
    requestMock.mockImplementationOnce(
      respond(200, { items: [{ branch_id: 'B1' }, { branch_id: 'B2' }], next_cursor: 'cur-1' }),
    );
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const page = await client.list<{ branch_id: string }>('/public/branches');
    expect(page.items).toEqual([{ branch_id: 'B1' }, { branch_id: 'B2' }]);
    expect(page.nextCursor).toBe('cur-1');
  });

  it('list 支持 next_cursor 为 null', async () => {
    requestMock.mockImplementationOnce(respond(200, { items: [], next_cursor: null }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const page = await client.list('/public/branches');
    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it('list 校验 items/next_cursor，畸形分页不静默成功', async () => {
    requestMock.mockImplementationOnce(respond(200, { items: 'x', next_cursor: 1 }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    await expect(client.list('/public/branches')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
  });

  it('成功响应为 null/数组/缺少 data 的对象时拒绝，不静默成功', async () => {
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });

    requestMock.mockImplementationOnce(respond(200, null));
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

    requestMock.mockImplementationOnce(respond(200, [1, 2, 3]));
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

    requestMock.mockImplementationOnce(respond(200, { items: [], next_cursor: null }));
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('401 回调抛错仍按 401 拒绝，Promise 不悬挂', async () => {
    const onUnauthorized = vi.fn(() => {
      throw new Error('callback boom');
    });
    requestMock.mockImplementationOnce(respond(401, { code: 'UNAUTHENTICATED', message: '未登录' }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1', onUnauthorized });
    await expect(client.get('/me/appointments')).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('响应头大小写不敏感读取 X-Request-Id', async () => {
    requestMock.mockImplementationOnce(respond(200, { data: {} }, { 'x-request-id': 'req-lower' }));
    const client: HttpClient = createHttpClient({ baseUrl: 'https://test.invalid/api/v1' });
    const result = await client.get('/me/appointments/1');
    expect(result.requestId).toBe('req-lower');
  });
});
