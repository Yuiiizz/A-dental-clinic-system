import { ApiError, type HttpClient } from './http';

export type CredentialDecision = 'verified' | 'rejected' | 'revoke';

export interface CredentialFacts {
  credential_id: string;
  staff_id: string;
  credential_type: string;
  certificate_no: string;
  valid_from: string | null;
  valid_to: string | null;
  no_expiry_confirmed: boolean;
  verification_state: 'pending' | 'verified' | 'rejected';
  version: number;
}

export interface CredentialReviewItem {
  review_id: string;
  credential_id: string;
  action: 'submit' | 'verify' | 'reject' | 'revoke';
  from_state: 'pending' | 'verified' | 'rejected' | null;
  to_state: 'pending' | 'verified' | 'rejected';
  actor_staff_id: string;
  basis: string | null;
  occurred_at: string;
  resulting_version: number;
}

export interface CredentialReviewPage {
  items: CredentialReviewItem[];
  next_cursor: string | null;
}

export interface NewCredentialInput {
  credential_type: string;
  certificate_no: string;
  valid_from?: string;
  valid_to?: string;
  no_expiry_confirmed: boolean;
}

function invalid(): never {
  throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的资格资料。');
}

function parseFacts(value: unknown): CredentialFacts {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return invalid();
  const row = value as Record<string, unknown>;
  for (const key of ['credential_id', 'staff_id', 'credential_type', 'certificate_no']) {
    if (typeof row[key] !== 'string' || !row[key]) return invalid();
  }
  for (const key of ['valid_from', 'valid_to']) {
    if (row[key] !== null && typeof row[key] !== 'string') return invalid();
  }
  if (typeof row.no_expiry_confirmed !== 'boolean'
    || !['pending', 'verified', 'rejected'].includes(String(row.verification_state))
    || typeof row.version !== 'number' || !Number.isInteger(row.version) || row.version < 1) return invalid();
  return row as unknown as CredentialFacts;
}

function path(staffId: string, credentialId: string): string {
  if (!/^[1-9]\d*$/.test(staffId) || !/^[1-9]\d*$/.test(credentialId)) {
    throw new ApiError('VALIDATION_FAILED', 0, '员工或资格编号无效。');
  }
  return `/staff/staff-members/${staffId}/credentials/${credentialId}`;
}

function parseReviews(value: unknown, credentialId: string): CredentialReviewPage {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return invalid();
  const page = value as Record<string, unknown>;
  if (!Array.isArray(page.items) || (page.next_cursor !== null && typeof page.next_cursor !== 'string')) return invalid();
  const states = ['pending', 'verified', 'rejected'];
  for (const item of page.items) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) return invalid();
    const row = item as Record<string, unknown>;
    if (typeof row.review_id !== 'string' || !/^[1-9]\d*$/.test(row.review_id)
      || row.credential_id !== credentialId
      || !['submit', 'verify', 'reject', 'revoke'].includes(String(row.action))
      || (row.from_state !== null && !states.includes(String(row.from_state)))
      || !states.includes(String(row.to_state))
      || typeof row.actor_staff_id !== 'string' || !/^[1-9]\d*$/.test(row.actor_staff_id)
      || (row.basis !== null && typeof row.basis !== 'string')
      || typeof row.occurred_at !== 'string' || !Number.isFinite(Date.parse(row.occurred_at))
      || typeof row.resulting_version !== 'number' || !Number.isInteger(row.resulting_version)
      || row.resulting_version < 1) return invalid();
  }
  if (page.next_cursor !== null && !/^[1-9]\d*$/.test(page.next_cursor)) return invalid();
  return page as unknown as CredentialReviewPage;
}

export function createCredentialReviewApi(client: HttpClient) {
  return {
    async add(staffId: string, input: NewCredentialInput, key: string): Promise<string> {
      if (!/^[1-9]\d*$/.test(staffId)) throw new ApiError('VALIDATION_FAILED', 0, '员工编号无效。');
      if (!key.trim()) throw new ApiError('VALIDATION_FAILED', 0, '缺少幂等键。');
      const credentialType = input.credential_type.trim();
      const certificateNo = input.certificate_no.trim();
      if (!credentialType || credentialType.length > 40 || !certificateNo || certificateNo.length > 80) {
        throw new ApiError('VALIDATION_FAILED', 0, '请核对资格类型和证件编号。');
      }
      const body: NewCredentialInput = {
        credential_type: credentialType, certificate_no: certificateNo,
        no_expiry_confirmed: input.no_expiry_confirmed,
      };
      if (input.valid_from) body.valid_from = input.valid_from;
      if (input.valid_to) body.valid_to = input.valid_to;
      const result = await client.post<unknown>(`/staff/staff-members/${staffId}/credentials`, body, { idempotencyKey: key });
      const row = result.data;
      if (typeof row !== 'object' || row === null || Array.isArray(row)) return invalid();
      const value = row as Record<string, unknown>;
      if (value.staff_id !== staffId || typeof value.credential_id !== 'string'
        || !/^[1-9]\d*$/.test(value.credential_id)) return invalid();
      return value.credential_id;
    },

    async facts(staffId: string, credentialId: string): Promise<CredentialFacts> {
      const result = await client.get<unknown>(`${path(staffId, credentialId)}/facts`);
      const facts = parseFacts(result.data);
      if (facts.staff_id !== staffId || facts.credential_id !== credentialId) return invalid();
      return facts;
    },

    async reviews(staffId: string, credentialId: string, cursor: string | null = null): Promise<CredentialReviewPage> {
      if (cursor !== null && !/^[1-9]\d*$/.test(cursor)) {
        throw new ApiError('VALIDATION_FAILED', 0, '审核历史游标无效。');
      }
      const query = cursor === null ? '?limit=20' : `?limit=20&cursor=${encodeURIComponent(cursor)}`;
      const result = await client.get<unknown>(`${path(staffId, credentialId)}/reviews${query}`);
      return parseReviews(result.data, credentialId);
    },

    async decide(facts: CredentialFacts, decision: CredentialDecision, basis: string, key: string): Promise<void> {
      const normalizedBasis = basis.trim();
      if (!normalizedBasis || normalizedBasis.length > 300) {
        throw new ApiError('VALIDATION_FAILED', 0, '审核依据必须为 1–300 个字符。');
      }
      if (!key.trim()) throw new ApiError('VALIDATION_FAILED', 0, '缺少幂等键。');
      if (decision === 'revoke' && facts.verification_state !== 'verified') {
        throw new ApiError('VALIDATION_FAILED', 0, '只有已核验资格可以撤销。');
      }
      if (decision !== 'revoke' && facts.verification_state !== 'pending') {
        throw new ApiError('VALIDATION_FAILED', 0, '只有待审核资格可以首次审核。');
      }
      const base = path(facts.staff_id, facts.credential_id);
      const endpoint = decision === 'revoke' ? `${base}/revoke` : `${base}/review`;
      const body = decision === 'revoke'
        ? { basis: normalizedBasis, expected_version: facts.version }
        : { decision, basis: normalizedBasis, expected_version: facts.version };
      await client.post<unknown>(endpoint, body, { idempotencyKey: key });
    },
  };
}
