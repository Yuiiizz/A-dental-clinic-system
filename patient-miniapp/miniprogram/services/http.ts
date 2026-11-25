// 患者小程序统一 HTTP 客户端（患者 Bearer）。
//
// 对齐 specs/001-scheduling-booking/contracts/api.md 与 error-codes.md：
//  - 普通域名走 wx.request；配置 AnyService 后走 wx.cloud.callContainer。
//  - 令牌由可注入提供器获取（不写日志）；公开请求与注册/绑定流程显式 auth:false，不误用业务令牌。
//  - 成功体 { data, request_id }；分页列表体 { items, next_cursor }（见 list）；错误体
//    { code, message, request_id, field_errors? }。
//  - 401 = 未登录/会话失效 → 触发 onUnauthorized（回调抛错不替代 401 错误、不使 Promise 悬挂）；
//    403 = 已登录但无权，不视为退出登录；409 保留冲突信息，不自动重新提交。
//  - 不自动重试任何变更请求；Idempotency-Key 由调用方传入并原样保留，不自动生成、不按次更换。
//  - 成功响应结构必须校验：null、数组、缺少 data 的普通对象不静默成功；分页校验 items 与 next_cursor。
//  - 错误对象/提示不包含令牌、密码、完整请求体或原始 HTML 错误页。
//  - 服务端才是授权依据，本模块不在客户端判断业务权限。

import { API_BASE_URL, CLOUD_ACCESS } from './config';

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
}

// 微信 wx.request 不支持 PATCH；患者端契约仅用 GET/POST/PUT/DELETE。
type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  /** 相对路径，如 `/me/appointments`，自动拼接到 baseUrl 之后。 */
  path: string;
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  /** 调用方提供的幂等键，原样写入 Idempotency-Key 头；本模块绝不自动生成或更换。 */
  idempotencyKey?: string;
  /** 默认 true：业务请求附带患者 Bearer；公开请求与注册/绑定流程显式 false。 */
  auth?: boolean;
}

export interface HttpOptions {
  /** API 基础地址；留空时发起请求会明确报错（配置缺失）。 */
  baseUrl?: string;
  /** 超时毫秒数，传给 wx.request（覆盖连接与正文读取全过程）。 */
  timeoutMs?: number;
  /** 获取当前患者 Bearer 令牌；返回 null/undefined 表示暂无。 */
  getBearerToken?: () => string | null;
  /** 401 登录失效回调。 */
  onUnauthorized?: () => void;
  /** 显式云接入配置；生产默认值来自 config，供隔离测试注入。 */
  cloudAccess?: { envId: string; serviceName: string } | null;
}

export interface HttpClient {
  request<T>(options: RequestOptions): Promise<HttpResult<T>>;
  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path'>): Promise<HttpResult<T>>;
  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<HttpResult<T>>;
  put<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<HttpResult<T>>;
  delete<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path'>): Promise<HttpResult<T>>;
  /** 分页列表：解析顶层 { items, next_cursor }，不按 { data, request_id } 解析。 */
  list<T>(path: string, options?: Omit<RequestOptions, 'method' | 'path' | 'body'>): Promise<PageResult<T>>;
}

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

// 微信响应头大小写不敏感读取。
function readHeader(header: unknown, name: string): string | undefined {
  if (!header || typeof header !== 'object') return undefined;
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(header as Record<string, unknown>)) {
    if (key.toLowerCase() === target && typeof value === 'string') return value;
  }
  return undefined;
}

type ParsedBody =
  | { kind: 'none' }
  | { kind: 'json'; value: unknown }
  | { kind: 'text'; text: string };

// 将 wx.request 的 res.data 归一化：对象/数组/null 视为已解析 JSON，字符串区分空体/JSON/纯文本。
function normalizeBody(data: unknown): ParsedBody {
  if (data === undefined) return { kind: 'none' };
  if (typeof data === 'string') {
    const text = data.trim();
    if (text === '') return { kind: 'none' };
    try {
      return { kind: 'json', value: JSON.parse(text) };
    } catch {
      return { kind: 'text', text };
    }
  }
  return { kind: 'json', value: data };
}

interface RawResult {
  status: number;
  ok: boolean;
  requestId?: string;
  body: ParsedBody;
}

let initializedCloudEnv = '';

interface CloudResponse {
  statusCode: number;
  data: unknown;
  header?: Record<string, unknown>;
}

interface CloudApi {
  init: (options: { env: string; traceUser: boolean }) => void;
  callContainer: (options: {
    path: string;
    method: HttpMethod;
    data?: unknown;
    header: Record<string, string>;
    timeout: number;
  }) => Promise<CloudResponse>;
}

export function createHttpClient(options: HttpOptions = {}): HttpClient {
  const baseUrl = (options.baseUrl ?? '').replace(/\/+$/, '');
  const timeoutMs = options.timeoutMs ?? 10_000;
  const getBearerToken = options.getBearerToken;
  const onUnauthorized = options.onUnauthorized;
  const cloudAccess = options.cloudAccess === undefined ? CLOUD_ACCESS : options.cloudAccess;

  function raw(opts: RequestOptions): Promise<RawResult> {
    const method = (opts.method ?? 'GET').toUpperCase() as HttpMethod;
    const auth = opts.auth ?? true;

    return new Promise<RawResult>((resolve, reject) => {
      if (!baseUrl && !cloudAccess) {
        reject(new ApiError('CONFIG_ERROR', 0, 'API 地址未配置。'));
        return;
      }
      let settled = false;
      const timer = setTimeout(() => fail(new ApiError('TIMEOUT', 0, '请求超时，请稍后重试。')), timeoutMs + 1000);
      const finish = (result: RawResult) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(result);
      };
      const fail = (error: ApiError) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      };

      const header: Record<string, string> = { Accept: 'application/json' };
      if (opts.body !== undefined) {
        header['Content-Type'] = 'application/json';
      }

      // 业务请求附带患者 Bearer；公开/注册/绑定 auth:false 不误用业务令牌。
      if (auth) {
        const token = getBearerToken?.();
        if (token) header['Authorization'] = `Bearer ${token}`;
      }

      if (opts.idempotencyKey) header['Idempotency-Key'] = opts.idempotencyKey;

      const onSuccess = (res: CloudResponse) => {
          if (settled) return;
          const headerRequestId = readHeader(res.header, 'X-Request-Id');

          if (res.statusCode === 401) {
            try { onUnauthorized?.(); } catch { /* 401 仍由调用方处理 */ }
          }

          const ok = res.statusCode >= 200 && res.statusCode < 300;
          if (res.statusCode === 204) {
            finish({ status: res.statusCode, ok: true, requestId: headerRequestId, body: { kind: 'none' } });
            return;
          }
          finish({ status: res.statusCode, ok, requestId: headerRequestId, body: normalizeBody(res.data) });
      };
      const onFailure = (err: { errMsg?: string }) => {
        const message = err?.errMsg || '';
        if (/timeout/i.test(message)) fail(new ApiError('TIMEOUT', 0, '请求超时，请稍后重试。'));
        else fail(new ApiError('NETWORK_ERROR', 0, '网络请求失败，请检查网络连接。'));
      };

      if (cloudAccess) {
        try {
          const cloud = (wx as unknown as { cloud?: CloudApi }).cloud;
          if (!cloud?.init || !cloud.callContainer) {
            fail(new ApiError('CONFIG_ERROR', 0, '云开发调用不可用。'));
            return;
          }
          if (initializedCloudEnv !== cloudAccess.envId) {
            cloud.init({ env: cloudAccess.envId, traceUser: true });
            initializedCloudEnv = cloudAccess.envId;
          }
          void cloud.callContainer({
            path: `/api/v1${opts.path}${buildQuery(opts.query)}`,
            method,
            data: opts.body,
            header: { ...header, 'X-WX-SERVICE': 'tcbanyservice', 'X-AnyService-Name': cloudAccess.serviceName },
            timeout: timeoutMs,
          }).then(onSuccess, onFailure);
        } catch {
          fail(new ApiError('CONFIG_ERROR', 0, '云开发配置无效。'));
        }
        return;
      }

      wx.request({
        url: `${baseUrl}${opts.path}${buildQuery(opts.query)}`,
        method,
        data: opts.body as WechatMiniprogram.IAnyObject,
        header,
        timeout: timeoutMs,
        success: onSuccess,
        fail: onFailure,
      });
    });
  }

  async function request<T>(opts: RequestOptions): Promise<HttpResult<T>> {
    const r = await raw(opts);

    if (r.body.kind === 'json') {
      if (!r.ok) throw toApiError(r.body.value, r.status, r.requestId);
      const value = r.body.value;
      if (isRecord(value) && Object.prototype.hasOwnProperty.call(value, 'data')) {
        const requestId = typeof value['request_id'] === 'string' ? value['request_id'] : r.requestId;
        return { data: value['data'] as T, requestId };
      }
      throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的响应。', r.requestId);
    }

    if (r.body.kind === 'text') {
      if (r.ok) throw new ApiError('INVALID_RESPONSE', r.status, '服务器返回了无法解析的响应。', r.requestId);
      throw new ApiError(fallbackCode(r.status), r.status, '请求失败，请稍后重试。', r.requestId);
    }

    // kind === 'none'：204 或空 2xx
    if (r.ok) return { data: undefined as T, requestId: r.requestId };
    throw new ApiError(fallbackCode(r.status), r.status, '请求失败，请稍后重试。', r.requestId);
  }

  async function list<T>(path: string, opts: Omit<RequestOptions, 'method' | 'path' | 'body'> = {}): Promise<PageResult<T>> {
    const r = await raw({ ...opts, method: 'GET', path });

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
    delete: (path, o) => request({ ...o, method: 'DELETE', path }),
    list,
  };
}

/** 默认客户端实例：地址取自 config.API_BASE_URL，未配置时请求会明确报错。 */
export const http: HttpClient = createHttpClient({ baseUrl: API_BASE_URL });
