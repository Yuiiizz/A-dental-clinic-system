// 患者小程序身份 API：严格解析 /auth/wechat、/auth/patient-register、/auth/patient-bind、/auth/me。
// 公开入口显式 auth:false；只有 /auth/me 等业务请求才带患者 Bearer。
import { ApiError, createHttpClient } from './http';
import type { HttpClient } from './http';
import { API_BASE_URL } from './config';
import { patientSession } from './session';
import type {
  Me,
  PatientRegistrationForm,
  PatientSessionResult,
  WechatLoginResult,
} from './auth-types';

function invalidResponse(): ApiError {
  return new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的响应。');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requiredText(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '') throw invalidResponse();
  return value;
}

function optionalText(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') throw invalidResponse();
  return value;
}

export function parseWechatLogin(value: unknown): WechatLoginResult {
  if (!isRecord(value)) throw invalidResponse();
  const kind = value['kind'];
  if (kind === 'session') {
    if ('registration_token' in value || 'registration_expires_at' in value) throw invalidResponse();
    return {
      kind: 'session',
      session_token: requiredText(value['session_token']),
      expires_at: requiredText(value['expires_at']),
    };
  }
  if (kind === 'registration') {
    if ('session_token' in value || 'expires_at' in value) throw invalidResponse();
    return {
      kind: 'registration',
      registration_token: requiredText(value['registration_token']),
      registration_expires_at: requiredText(value['registration_expires_at']),
    };
  }
  throw invalidResponse();
}

export function parsePatientSession(value: unknown): PatientSessionResult {
  if (!isRecord(value)) throw invalidResponse();
  const expiresAt = optionalText(value['expires_at']);
  return {
    session_token: requiredText(value['session_token']),
    patient_id: requiredText(value['patient_id']),
    ...(expiresAt === undefined ? {} : { expires_at: expiresAt }),
  };
}

export function parseMe(value: unknown): Me {
  if (!isRecord(value)) throw invalidResponse();
  const identity = value['identity_type'];
  if (identity !== 'patient' && identity !== 'staff') throw invalidResponse();
  const rawRoles = value['roles'];
  if (!Array.isArray(rawRoles)) throw invalidResponse();
  const roles = rawRoles.map((item) => {
    if (!isRecord(item)) throw invalidResponse();
    const code = requiredText(item['role_code']);
    const branches = item['branch_ids'];
    if (!Array.isArray(branches) || branches.some((branch) => typeof branch !== 'string')) {
      throw invalidResponse();
    }
    return { role_code: code, branch_ids: [...(branches as string[])] };
  });
  const mustChange = value['must_change_password'];
  if (typeof mustChange !== 'boolean') throw invalidResponse();
  return {
    account_id: requiredText(value['account_id']),
    name: requiredText(value['name']),
    identity_type: identity,
    must_change_password: mustChange,
    roles,
  };
}

export interface AuthApi {
  wechatLogin(code: string): Promise<WechatLoginResult>;
  register(input: PatientRegistrationForm & { registration_token: string }): Promise<PatientSessionResult>;
  bind(input: { registration_token: string; binding_token: string }): Promise<PatientSessionResult>;
  me(): Promise<Me>;
}

export function createAuthApi(client: HttpClient): AuthApi {
  return {
    async wechatLogin(code: string): Promise<WechatLoginResult> {
      const result = await client.post<unknown>('/auth/wechat', { code }, { auth: false });
      return parseWechatLogin(result.data);
    },

    async register(
      input: PatientRegistrationForm & { registration_token: string },
    ): Promise<PatientSessionResult> {
      const result = await client.post<unknown>(
        '/auth/patient-register',
        {
          registration_token: input.registration_token,
          name: input.name,
          phone: input.phone,
          birth_date: input.birth_date,
          allergy_status: input.allergy_status,
          allergy_notes: input.allergy_notes,
        },
        { auth: false },
      );
      return parsePatientSession(result.data);
    },

    async bind(input: { registration_token: string; binding_token: string }): Promise<PatientSessionResult> {
      const result = await client.post<unknown>(
        '/auth/patient-bind',
        {
          registration_token: input.registration_token,
          binding_token: input.binding_token,
        },
        { auth: false },
      );
      return parsePatientSession(result.data);
    },

    async me(): Promise<Me> {
      const result = await client.get<unknown>('/auth/me');
      return parseMe(result.data);
    },
  };
}

/** 默认身份客户端：只使用内存中的患者 Bearer；401 由 auth-flow 按请求/会话代次处理。 */
export const authHttp: HttpClient = createHttpClient({
  baseUrl: API_BASE_URL,
  timeoutMs: 15_000,
  getBearerToken: () => patientSession.token,
});

export const authApi: AuthApi = createAuthApi(authHttp);
