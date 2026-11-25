// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createHttpClient, type HttpClient } from './http';

interface MockResponse {
  status: number;
  ok: boolean;
  headers: { get: (name: string) => string | null };
  text: () => Promise<string>;
}

function mockResponse(status: number, body?: unknown, headers: Record<string, string> = {}): MockResponse {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: {
      get: (name: string) => headers[name] ?? headers[name.toLowerCase()] ?? null,
    },
    text: async () => (typeof body === 'string' ? body : body === undefined ? '' : JSON.stringify(body)),
  };
}

const fetchMock = vi.fn<typeof fetch>();

function lastInit(): RequestInit {
  const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
  return call?.[1] ?? {};
}

function lastUrl(): string {
  const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
  return (call?.[0] as string) ?? '';
}

function headerValue(init: RequestInit, name: string): string | undefined {
  const h = init.headers as Record<string, string> | undefined;
  return h?.[name];
}

describe('管理端 http 客户端', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('解析成功响应 { data, request_id }', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(200, { data: { appointment_id: '5001' }, request_id: 'req-1' }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    const result = await client.get<{ appointment_id: string }>('/me/appointments/5001');
    expect(result.data).toEqual({ appointment_id: '5001' });
    expect(result.requestId).toBe('req-1');
  });

  it('204 空响应返回 undefined 数据，request_id 取自 X-Request-Id 头', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(204, undefined, { 'X-Request-Id': 'req-204' }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    const result = await client.delete('/auth/logout');
    expect(result.data).toBeUndefined();
    expect(result.requestId).toBe('req-204');
  });

  it('业务错误解析出 code/status/request_id/field_errors', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(422, {
        code: 'VALIDATION_FAILED',
        message: '字段不合法',
        request_id: 'req-2',
        field_errors: [{ field: 'name', message: '必填' }],
      }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await expect(client.post('/staff/branches', {})).rejects.toMatchObject({
      name: 'ApiError',
      code: 'VALIDATION_FAILED',
      status: 422,
      requestId: 'req-2',
      fieldErrors: [{ field: 'name', message: '必填' }],
    });
  });

  it('401 触发登录失效回调，403 不触发', async () => {
    vi.stubGlobal('fetch', fetchMock);
    const onUnauthorized = vi.fn();

    fetchMock.mockResolvedValueOnce(
      mockResponse(401, { code: 'UNAUTHENTICATED', message: '未登录' }) as unknown as Response,
    );
    const client401 = createHttpClient({ baseUrl: '/api/v1', onUnauthorized });
    await expect(client401.get('/auth/me')).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    fetchMock.mockResolvedValueOnce(
      mockResponse(403, { code: 'FORBIDDEN', message: '无权操作' }) as unknown as Response,
    );
    const client403 = createHttpClient({ baseUrl: '/api/v1', onUnauthorized });
    await expect(client403.get('/staff/accounts')).rejects.toMatchObject({ code: 'FORBIDDEN', status: 403 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1); // 未因 403 再次触发
  });

  it('409 保留冲突信息且不自动重试', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(409, { code: 'VERSION_CONFLICT', message: '版本已变化', request_id: 'req-3' }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await expect(
      client.post('/me/appointments/1/cancel', { reason: 'x', expected_version: 1 }),
    ).rejects.toMatchObject({ code: 'VERSION_CONFLICT', status: 409, requestId: 'req-3' });
    expect(fetchMock).toHaveBeenCalledTimes(1); // 无自动重新提交
  });

  it('非 JSON 错误页不回传原始 HTML', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(502, '<html><body>Bad Gateway</body></html>') as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    let caught: ApiError | undefined;
    try {
      await client.get('/staff/branches');
    } catch (e) {
      caught = e as ApiError;
    }
    expect(caught).toMatchObject({ code: 'REQUEST_FAILED', status: 502 });
    expect(caught?.message).not.toContain('<html>');
    expect(caught?.message).not.toContain('Bad Gateway');
  });

  it('网络失败抛出 NETWORK_ERROR', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockRejectedValueOnce(new TypeError('fetch failed'));
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await expect(client.get('/staff/branches')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
  });

  it('超时抛出 TIMEOUT', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
            once: true,
          });
        }),
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1', timeoutMs: 10 });
    await expect(client.get('/slow')).rejects.toMatchObject({ code: 'TIMEOUT', status: 0 });
  });

  it('员工变更请求注入 X-CSRF-Token，登录/公开接口例外，GET 不注入', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue(mockResponse(200, { data: {} }) as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1', getCsrfToken: () => 'csrf-123' });

    await client.post('/staff/branches', { name: 'x' });
    expect(headerValue(lastInit(), 'X-CSRF-Token')).toBe('csrf-123');

    await client.post('/auth/staff-login', { login_name: 'a', password: 'b' }, { csrf: false });
    expect(headerValue(lastInit(), 'X-CSRF-Token')).toBeUndefined();

    await client.get('/staff/branches');
    expect(headerValue(lastInit(), 'X-CSRF-Token')).toBeUndefined();
  });

  it('不发送患者 Bearer，不触碰 Cookie；使用 credentials include', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue(mockResponse(200, { data: {} }) as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1', getCsrfToken: () => 't' });

    await client.post('/staff/patients', { name: 'x' });
    const init = lastInit();
    expect(headerValue(init, 'Authorization')).toBeUndefined();
    expect(headerValue(init, 'Cookie')).toBeUndefined();
    expect(init.credentials).toBe('include');
  });

  it('Idempotency-Key 由调用方原样保留，未提供则不添加', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue(mockResponse(200, { data: {} }) as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });

    await client.post('/me/appointments', { schedule_id: '1' }, { idempotencyKey: 'key-abc' });
    expect(headerValue(lastInit(), 'Idempotency-Key')).toBe('key-abc');

    await client.post('/me/appointments', { schedule_id: '1' });
    expect(headerValue(lastInit(), 'Idempotency-Key')).toBeUndefined();
  });

  it('查询参数序列化并跳过空值', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValue(mockResponse(200, { data: { items: [] } }) as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await client.get('/public/branches', { query: { limit: 20, branch_id: 'B1', empty: undefined } });
    expect(lastUrl()).toBe('/api/v1/public/branches?limit=20&branch_id=B1');
  });

  it('list 解析真实契约格式的顶层 { items, next_cursor }，不按 data 包络', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(200, {
        items: [{ branch_id: 'B1' }, { branch_id: 'B2' }],
        next_cursor: 'cur-123',
      }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    const page = await client.list<{ branch_id: string }>('/public/branches');
    expect(page.items).toEqual([{ branch_id: 'B1' }, { branch_id: 'B2' }]);
    expect(page.nextCursor).toBe('cur-123');
  });

  it('list 支持 next_cursor 为 null', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(200, { items: [], next_cursor: null }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    const page = await client.list('/public/branches');
    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it('list 校验 items/next_cursor，畸形分页不静默成功', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(200, { items: 'not-an-array', next_cursor: 123 }) as unknown as Response,
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await expect(client.list('/public/branches')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
  });

  it('成功响应为 null/数组/缺少 data 的对象时拒绝，不静默成功', async () => {
    vi.stubGlobal('fetch', fetchMock);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });

    fetchMock.mockResolvedValueOnce(mockResponse(200, null) as unknown as Response);
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

    fetchMock.mockResolvedValueOnce(mockResponse(200, [1, 2, 3]) as unknown as Response);
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

    fetchMock.mockResolvedValueOnce(mockResponse(200, { items: [], next_cursor: null }) as unknown as Response);
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('响应头已返回但正文延迟，超时覆盖正文读取', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockImplementation(
      (_url, init) =>
        Promise.resolve({
          status: 200,
          ok: true,
          headers: { get: () => null },
          text: () =>
            new Promise((_resolve, reject) => {
              init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
                once: true,
              });
            }),
        } as unknown as Response),
    );
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1', timeoutMs: 10 });
    await expect(client.get('/slow-body')).rejects.toMatchObject({ code: 'TIMEOUT', status: 0 });
  });

  it('正文读取失败转为安全 ApiError，不回传底层异常', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce({
      status: 200,
      ok: true,
      headers: { get: () => null },
      text: () => Promise.reject(new Error('stream broken')),
    } as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    let caught: ApiError | undefined;
    try {
      await client.get('/x');
    } catch (e) {
      caught = e as ApiError;
    }
    expect(caught).toMatchObject({ code: 'INVALID_RESPONSE', status: 200 });
    expect(caught?.message).not.toContain('stream broken');
  });

  it('正文读取失败但状态为业务错误时不误判为网络错误', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce({
      status: 400,
      ok: false,
      headers: { get: () => null },
      text: () => Promise.reject(new Error('stream broken')),
    } as unknown as Response);
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1' });
    await expect(client.get('/x')).rejects.toMatchObject({ code: 'REQUEST_FAILED', status: 400 });
  });

  it('401 回调抛错仍按 401 拒绝，不替代正常错误', async () => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(
      mockResponse(401, { code: 'UNAUTHENTICATED', message: '未登录' }) as unknown as Response,
    );
    const onUnauthorized = vi.fn(() => {
      throw new Error('callback boom');
    });
    const client: HttpClient = createHttpClient({ baseUrl: '/api/v1', onUnauthorized });
    await expect(client.get('/auth/me')).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});
