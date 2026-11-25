// 管理端员工主档 API：严格按现有 StaffController/OpenAPI 契约封装。
// 本服务不发送患者 Bearer、不读取 Cookie；CSRF 由调用方注入的 HttpClient 提供。
import { ApiError, type HttpClient, type PageResult } from './http';

export type StaffType = '牙医' | '其他人员';
export type EmploymentState = '在岗' | '离岗';
export type CredentialState = 'pending' | 'verified' | 'rejected';

export interface StaffRow {
  staff_id: string;
  name: string;
  staff_type: StaffType;
  job_title: string;
  employment_state: EmploymentState;
  version: number;
}

export interface StaffBranch {
  branch_id: string;
  name: string;
  state: string;
  enabled: boolean;
  verified_at: string | null;
}

export interface CredentialSummary {
  credential_id: string;
  credential_type: string;
  verification_state: CredentialState;
  valid_from: string | null;
  valid_to: string | null;
  no_expiry_confirmed: boolean;
  verified_at: string | null;
  version: number;
  effective: boolean;
}

export interface StaffDetail extends StaffRow {
  practice_no: string | null;
  branches: StaffBranch[];
  credentials: CredentialSummary[];
}

export interface StaffCredentialInput {
  credential_type: string;
  certificate_no: string;
  valid_from?: string;
  valid_to?: string;
  no_expiry_confirmed?: boolean;
}

export interface StaffCreateInput {
  name: string;
  staff_type: StaffType;
  job_title: string;
  practice_no?: string;
  credential?: StaffCredentialInput;
}

export interface StaffApi {
  list(cursor?: string, limit?: number): Promise<PageResult<StaffRow>>;
  detail(staffId: string): Promise<StaffDetail>;
  create(input: StaffCreateInput, idempotencyKey: string): Promise<StaffDetail>;
}

function invalidResponse(): ApiError {
  return new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的员工数据。');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requiredText(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '') throw invalidResponse();
  return value;
}

function optionalText(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value !== 'string') throw invalidResponse();
  return value;
}

function versionOf(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) throw invalidResponse();
  return value;
}

function parseStaffType(value: unknown): StaffType {
  if (value === '牙医' || value === '其他人员') return value;
  throw invalidResponse();
}

function parseEmployment(value: unknown): EmploymentState {
  if (value === '在岗' || value === '离岗') return value;
  throw invalidResponse();
}

function parseRow(value: unknown): StaffRow {
  if (!isRecord(value)) throw invalidResponse();
  return {
    staff_id: requiredText(value['staff_id']),
    name: requiredText(value['name']),
    staff_type: parseStaffType(value['staff_type']),
    job_title: requiredText(value['job_title']),
    employment_state: parseEmployment(value['employment_state']),
    version: versionOf(value['version']),
  };
}

function parseBranch(value: unknown): StaffBranch {
  if (!isRecord(value)) throw invalidResponse();
  const verifiedAt = optionalText(value['verified_at']);
  if (typeof value['enabled'] !== 'boolean') throw invalidResponse();
  return {
    branch_id: requiredText(value['branch_id']),
    name: requiredText(value['name']),
    state: requiredText(value['state']),
    enabled: value['enabled'],
    verified_at: verifiedAt,
  };
}

function parseCredentialState(value: unknown): CredentialState {
  if (value === 'pending' || value === 'verified' || value === 'rejected') return value;
  throw invalidResponse();
}

function parseCredential(value: unknown): CredentialSummary {
  if (!isRecord(value)) throw invalidResponse();
  if (typeof value['no_expiry_confirmed'] !== 'boolean' || typeof value['effective'] !== 'boolean') {
    throw invalidResponse();
  }
  return {
    credential_id: requiredText(value['credential_id']),
    credential_type: requiredText(value['credential_type']),
    verification_state: parseCredentialState(value['verification_state']),
    valid_from: optionalText(value['valid_from']),
    valid_to: optionalText(value['valid_to']),
    no_expiry_confirmed: value['no_expiry_confirmed'],
    verified_at: optionalText(value['verified_at']),
    version: versionOf(value['version']),
    effective: value['effective'],
  };
}

function parseDetail(value: unknown): StaffDetail {
  const row = parseRow(value);
  if (!isRecord(value)) throw invalidResponse();
  if (!Array.isArray(value['branches']) || !Array.isArray(value['credentials'])) throw invalidResponse();
  return {
    ...row,
    practice_no: optionalText(value['practice_no']),
    branches: value['branches'].map(parseBranch),
    credentials: value['credentials'].map(parseCredential),
  };
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  if (!Number.isFinite(date.getTime())) return false;
  return date.toISOString().slice(0, 10) === value;
}

function serializeCreate(input: StaffCreateInput): Record<string, unknown> {
  const name = input.name.trim();
  const jobTitle = input.job_title.trim();
  if (!name || name.length > 60) throw validation('姓名必须为 1-60 个字符。');
  if (!jobTitle || jobTitle.length > 60) throw validation('岗位必须为 1-60 个字符。');
  if (input.staff_type !== '牙医' && input.staff_type !== '其他人员') throw validation('人员类型无效。');

  const practiceNo = input.practice_no?.trim();
  if (practiceNo && practiceNo.length > 60) throw validation('执业编号不能超过 60 个字符。');
  if (input.staff_type === '牙医' && !practiceNo) throw validation('牙医必须填写执业编号。');

  const body: Record<string, unknown> = {
    name,
    staff_type: input.staff_type,
    job_title: jobTitle,
  };
  if (practiceNo) body['practice_no'] = practiceNo;
  if (input.credential) {
    const credential = input.credential;
    const type = credential.credential_type.trim();
    const certificate = credential.certificate_no.trim();
    if (!type || type.length > 40) throw validation('资格类型必须为 1-40 个字符。');
    if (!certificate || certificate.length > 80) throw validation('资格证件编号必须为 1-80 个字符。');
    const from = credential.valid_from?.trim();
    const to = credential.valid_to?.trim();
    const noExpiry = Boolean(credential.no_expiry_confirmed);
    if (from && !validDate(from)) throw validation('资格起始日期格式无效。');
    if (to && !validDate(to)) throw validation('资格截止日期格式无效。');
    if (from && to && to < from) throw validation('资格截止日期不能早于起始日期。');
    if (noExpiry && to) throw validation('确认无期限时不能填写截止日期。');
    const serialized: Record<string, unknown> = {
      credential_type: type,
      certificate_no: certificate,
      no_expiry_confirmed: noExpiry,
    };
    if (from) serialized['valid_from'] = from;
    if (to) serialized['valid_to'] = to;
    body['credential'] = serialized;
  }
  return body;
}

function validation(message: string): ApiError {
  return new ApiError('VALIDATION_FAILED', 0, message);
}

export function createStaffApi(client: HttpClient): StaffApi {
  return {
    async list(cursor?: string, limit?: number): Promise<PageResult<StaffRow>> {
      const page = await client.list<unknown>('/staff/staff-members', { query: { cursor, limit } });
      return { items: page.items.map(parseRow), nextCursor: page.nextCursor };
    },

    async detail(staffId: string): Promise<StaffDetail> {
      const id = staffId.trim();
      if (!/^[1-9]\d*$/.test(id)) throw validation('员工编号不符合要求。');
      const result = await client.get<unknown>(`/staff/staff-members/${id}`);
      return parseDetail(result.data);
    },

    async create(input: StaffCreateInput, idempotencyKey: string): Promise<StaffDetail> {
      const key = idempotencyKey.trim();
      if (!key) throw validation('缺少幂等键。');
      const result = await client.post<unknown>('/staff/staff-members', serializeCreate(input), {
        idempotencyKey: key,
      });
      try {
        return parseDetail(result.data);
      } catch (e) {
        if (e instanceof ApiError && e.code === 'INVALID_RESPONSE') {
          throw new ApiError('INVALID_RESPONSE', result.status ?? 0,
            '服务器返回了无法解析的员工数据。', result.requestId);
        }
        throw e;
      }
    },
  };
}

/** 仅用于测试/复用：不对客户端开放额外请求。 */
export function parseStaffDetailForTest(value: unknown): StaffDetail {
  return parseDetail(value);
}
