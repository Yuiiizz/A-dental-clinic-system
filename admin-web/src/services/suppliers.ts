import { ApiError, type HttpClient, type PageResult } from './http';

export interface SupplierRow {
  supplier_id: string;
  name: string;
  credit_code: string;
  valid_to: string | null;
  enabled: boolean;
  verified_at: string;
}
export interface SupplierDetail extends SupplierRow {
  qualification_ref: string;
  verified_by: string;
}
export interface SupplierCreate {
  name: string;
  credit_code: string;
  qualification_ref: string;
  valid_to: string;
}

function invalid(): never { throw new ApiError('INVALID_RESPONSE', 0, '供应商数据格式不正确，请稍后重试。'); }
function row(value: unknown): SupplierRow {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid();
  const v = value as Record<string, unknown>;
  if (typeof v.supplier_id !== 'string' || typeof v.name !== 'string' ||
      typeof v.credit_code !== 'string' || typeof v.enabled !== 'boolean' ||
      typeof v.verified_at !== 'string' || !(v.valid_to === null || typeof v.valid_to === 'string')) return invalid();
  return { supplier_id: v.supplier_id, name: v.name, credit_code: v.credit_code,
    valid_to: v.valid_to, enabled: v.enabled, verified_at: v.verified_at };
}
function detail(value: unknown): SupplierDetail {
  const base = row(value);
  const v = value as Record<string, unknown>;
  if (typeof v.qualification_ref !== 'string' || typeof v.verified_by !== 'string') return invalid();
  return { ...base, qualification_ref: v.qualification_ref, verified_by: v.verified_by };
}

export function createSuppliersApi(http: HttpClient) {
  return {
    async list(cursor?: string): Promise<PageResult<SupplierRow>> {
      const result = await http.list<unknown>('/staff/suppliers', { query: { cursor } });
      return { items: result.items.map(row), nextCursor: result.nextCursor };
    },
    async detail(id: string): Promise<SupplierDetail> {
      return detail((await http.get<unknown>(`/staff/suppliers/${encodeURIComponent(id)}`)).data);
    },
    async create(input: SupplierCreate, key: string): Promise<SupplierDetail> {
      return detail((await http.post<unknown>('/staff/suppliers', input,
        { idempotencyKey: key })).data);
    },
  };
}
