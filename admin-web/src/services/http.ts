// 管理端统一 HTTP 客户端（员工会话）。
//
// 对齐 specs/001-scheduling-booking/contracts/api.md 与 error-codes.md：
//  - 仅用原生 fetch + credentials；会话 Cookie（A_CLINIC_SESSION，HttpOnly）由浏览器自动携带，
//    本模块绝不读取 HttpOnly Cookie、不触碰 document.cookie。
//  - 不发送患者 Bearer；员工状态变更请求按会话注入 X-CSRF-Token（令牌由可注入提供器获取，不写日志）。
//  - 成功体 { data, request_id }；分页列表体 { items, next_cursor }（见 list）；错误体
//    { code, message, request_id, field_errors? }。
//  - 401 = 未登录/会话失效 → 触发 onUnauthorized（回调抛错不替代 401 错误）；403 = 已登录但无权，
//    不视为退出登录；409 保留冲突信息，不自动重新提交。
//  - 不自动重试任何变更请求；Idempotency-Key 由调用方传入并原样保留，不自动生成、不按次更换。
//  - 超时覆盖 fetch 与响应正文读取全过程，结束统一清理定时器与监听；正文读取失败转为安全 ApiError。
//  - 错误对象/提示不包含令牌、密码、完整请求体或原始 HTML 错误页。
//  - 服务端才是授权依据，本模块不在客户端判断业务权限。

export interface FieldError {
  field: string;
  message: string;
}

/** 顶层分页响应 { items, next_cursor }。 */
export interface PageResult<T> {
  items: T[];
  nextCursor: string | null;
}

export interface SuccessBody<T> {
  data: T;
  request_id?: string;
}

export interface ErrorBody {
  code: string;
  message: string;
  request_id?: string;
  field_errors?: FieldError[];
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly requestId?: string;
  readonly fieldErrors?: FieldError[];

  constructor(
    code: string,
    status: number,
    message: string,
    requestId?: string,
    fieldErrors?: FieldError[],
  ) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.requestId = requestId;
    this.fieldErrors = fieldErrors;
  }
}

export interface HttpResult<T> {
  data: T;
  requestId?: string;
  /** 成功响应的 HTTP 状态；供调用方区分畸形 2xx 与普通解析失败。 */
  status?: number;
}

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  /** 相对路径，如 `/auth/me`，自动拼接到 baseUrl 之后。 */
  path: string;
  /** 请求体；仅对象会被 JSON 序列化。 */
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  /** 调用方提供的幂等键，原样写入 Idempotency-Key 头；本模块绝不自动生成或更换。 */
  idempotencyKey?: string;
  /** 默认：非安全方法注入 X-CSRF-Token；登录/公开/认证流转接口显式传 false。 */
  csrf?: boolean;
  /** 调用方取消信号（用于主动取消/外部超时）。 */
  signal?: AbortSignal;
}

export interface HttpOptions {
  /** API 基础地址，默认同源相对地址 /api/v1（不写死生产域名、不回退 localhost）。 */
  baseUrl?: string;
  /** 超时毫秒数；不设置则不超时。 */
  timeoutMs?: number;
  /** 获取当前会话的 CSRF 令牌；返回 null/undefined 表示暂无（如未登录）。 */
  getCsrfToken?: () => string | null;
  /** 401 登录失效回调。 */
  onUnauthorized?: () => void;
}

export interface HttpClient {
  request<T>(options: RequestOptions): Promise<HttpResult<T>>;
  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path'>): Promise<HttpResult<T>>;
  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<HttpResult<T>>;
  put<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<HttpResult<T>>;
  patch<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<HttpResult<T>>;
  delete<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path'>): Promise<HttpResult<T>>;
  /** 分页列表：解析顶层 { items, next_cursor }，不按 { data, request_id } 解析。 */
  list<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<PageResult<T>>;
}

const JSON_ACCEPT: Record<string, string> = { Accept: 'application/json' };

function buildQuery(query?: Record<string, string | number | boolean | undefined>): string {
  if (!query) return '';
  const parts = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length > 0 ? `?${parts.join('&')}` : '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function tryParse(text: string): { ok: true; value: unknown } | { ok: false } {
  if (text.trim() === '') return { ok: false };
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch {
    return { ok: false };
  }
}

function fallbackCode(status: number): string {
  switch (status) {
    case 401:
      return 'UNAUTHENTICATED';
    case 403:
      return 'FORBIDDEN';
    case 404:
      return 'NOT_FOUND';
    case 429:
      return 'RATE_LIMITED';
    default:
      return 'REQUEST_FAILED';
  }
}

function toApiError(value: unknown, status: number, headerRequestId?: string): ApiError {
  let code = fallbackCode(status);
  let message = '请求失败，请稍后重试。';
  let requestId = headerRequestId;
  let fieldErrors: FieldError[] | undefined;

  if (isRecord(value)) {
    if (typeof value['code'] === 'string' && value['code'] !== '') code = value['code'];
    if (typeof value['message'] === 'string' && value['message'] !== '') message = value['message'];
    if (typeof value['request_id'] === 'string') requestId = value['request_id'];

    const rawFieldErrors = value['field_errors'];
    if (Array.isArray(rawFieldErrors)) {
      const valid: FieldError[] = [];
      for (const item of rawFieldErrors) {
        if (isRecord(item) && typeof item['field'] === 'string' && typeof item['message'] === 'string') {
          valid.push({ field: item['field'], message: item['message'] });
        }
      }
      if (valid.length > 0) fieldErrors = valid;
    }
  }

  return new ApiError(code, status, message, requestId, fieldErrors);
}

interface TimeoutController {
  signal: AbortSignal | undefined;
  timedOut: () => boolean;
  cleanup: () => void;
}

function withTimeout(signal: AbortSignal | undefined, timeoutMs: number | undefined): TimeoutController {
  if (!timeoutMs) {
    return { signal, timedOut: () => false, cleanup: () => undefined };
  }
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const forward = () => controller.abort();
  if (signal) {
    if (signal.aborted) forward();
    else signal.addEventListener('abort', forward, { once: true });
  }
  return {
    signal: controller.signal,
    timedOut: () => timedOut,
    cleanup: () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', forward);
    },
  };
}

interface RawResult {
  status: number;
  ok: boolean;
  requestId?: string;
  body:
    | { kind: 'none' }
    | { kind: 'json'; value: unknown }
    | { kind: 'text'; text: string };
}

export function createHttpClient(options: HttpOptions = {}): HttpClient {
  const baseUrl = (options.baseUrl ?? '/api/v1').replace(/\/+$/, '');
  const timeoutMs = options.timeoutMs;
  const getCsrfToken = options.getCsrfToken;
  const onUnauthorized = options.onUnauthorized;

  async function raw(method: HttpMethod, opts: RequestOptions): Promise<RawResult> {
    const isSafe = method === 'GET';
    const csrf = opts.csrf ?? !isSafe;

    const headers: Record<string, string> = { ...JSON_ACCEPT };
    let body: string | undefined;
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    if (csrf && !isSafe) {
      const token = getCsrfToken?.();
      if (token) headers['X-CSRF-Token'] = token;
    }
    if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

    const url = `${baseUrl}${opts.path}${buildQuery(opts.query)}`;
    const { signal, timedOut, cleanup } = withTimeout(opts.signal, timeoutMs);

    try {
      let response: Response;
      try {
        response = await fetch(url, { method, headers, body, credentials: 'include', signal });
      } catch {
        if (timedOut()) throw new ApiError('TIMEOUT', 0, '请求超时，请稍后重试。');
        throw new ApiError('NETWORK_ERROR', 0, '网络请求失败，请检查网络连接。');
      }

      // 仅 401 触发登录失效回调；回调抛错不替代 401 错误。
      if (response.status === 401) {
        try {
          onUnauthorized?.();
        } catch {
          // 忽略回调异常，仍按 401 抛出
        }
      }

      const requestId = response.headers.get('X-Request-Id') ?? undefined;

      if (response.status === 204) {
        return { status: response.status, ok: true, requestId, body: { kind: 'none' } };
      }

      // 正文读取同样受超时覆盖。
      let text: string;
      try {
        text = await response.text();
      } catch {
        if (timedOut()) throw new ApiError('TIMEOUT', 0, '请求超时，请稍后重试。');
        if (!response.ok) {
          throw new ApiError(fallbackCode(response.status), response.status, '请求失败，请稍后重试。', requestId);
        }
        throw new ApiError('INVALID_RESPONSE', response.status, '服务器返回了无法解析的响应。', requestId);
      }

      const parsed = tryParse(text);
      if (!parsed.ok) {
        if (text.trim() === '') {
          return { status: response.status, ok: response.ok, requestId, body: { kind: 'none' } };
        }
        return { status: response.status, ok: response.ok, requestId, body: { kind: 'text', text } };
      }
      return { status: response.status, ok: response.ok, requestId, body: { kind: 'json', value: parsed.value } };
    } finally {
      cleanup();
    }
  }

  async function request<T>(opts: RequestOptions): Promise<HttpResult<T>> {
    const r = await raw((opts.method ?? 'GET').toUpperCase() as HttpMethod, opts);

    if (r.body.kind === 'json') {
      if (!r.ok) throw toApiError(r.body.value, r.status, r.requestId);
      const value = r.body.value;
      if (isRecord(value) && Object.prototype.hasOwnProperty.call(value, 'data')) {
        const requestId = typeof value['request_id'] === 'string' ? value['request_id'] : r.requestId;
        return { data: value['data'] as T, requestId, status: r.status };
      }
      throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的响应。', r.requestId);
    }

    if (r.body.kind === 'text') {
      if (r.ok) throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的响应。', r.requestId);
      throw new ApiError(fallbackCode(r.status), r.status, '请求失败，请稍后重试。', r.requestId);
    }

    // kind === 'none'：204 或空 2xx
    if (r.ok) return { data: undefined as T, requestId: r.requestId, status: r.status };
    throw new ApiError(fallbackCode(r.status), r.status, '请求失败，请稍后重试。', r.requestId);
  }

  async function list<T>(path: string, opts: Omit<RequestOptions, 'method' | 'path' | 'body'> = {}): Promise<PageResult<T>> {
    const r = await raw('GET', { ...opts, path });

    if (r.body.kind !== 'json') {
      if (r.ok) throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的分页响应。', r.requestId);
      throw new ApiError(fallbackCode(r.status), r.status, '请求失败，请稍后重试。', r.requestId);
    }

    if (!r.ok) throw toApiError(r.body.value, r.status, r.requestId);

    const value = r.body.value;
    const nextCursor = isRecord(value) ? value['next_cursor'] : undefined;
    if (
      isRecord(value) &&
      Array.isArray(value['items']) &&
      (nextCursor === null || typeof nextCursor === 'string')
    ) {
      return { items: value['items'] as T[], nextCursor: nextCursor as string | null };
    }
    throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的分页响应。', r.requestId);
  }

  return {
    request,
    get: (path, o) => request({ ...o, method: 'GET', path }),
    post: (path, body, o) => request({ ...o, method: 'POST', path, body }),
    put: (path, body, o) => request({ ...o, method: 'PUT', path, body }),
    patch: (path, body, o) => request({ ...o, method: 'PATCH', path, body }),
    delete: (path, o) => request({ ...o, method: 'DELETE', path }),
    list,
  };
}

const defaultBaseUrl = (import.meta.env.VITE_API_BASE_URL as string | undefined) || '/api/v1';

/** 默认客户端实例：同源相对地址，可经 VITE_API_BASE_URL 覆盖。 */
export const http: HttpClient = createHttpClient({ baseUrl: defaultBaseUrl, timeoutMs: 30_000 });
