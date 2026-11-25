import { ApiError, type HttpClient } from '@/services/http';

export type BranchState = '草稿' | '待核验' | '营业' | '停用';
export interface BranchRow { branch_id: string; code: string; name: string; state: BranchState; version: number; manager_id: string }
export interface BranchDetail extends BranchRow {
  address: string; phone: string; license_no: string; license_valid_to: string | null;
  license_no_expiry_confirmed: boolean; opening_hours: string;
  verified_by: string | null; verified_at: string | null; reauthentication_required: boolean;
}
export interface BranchForm { code: string; name: string; address: string; phone: string; license_no: string; license_valid_to: string; opening_hours: string }
export interface Interval { valid_on: string; starts_at: string; ends_at: string }
export interface Opening { branch_id: string; from_date: string; to_date: string; version: number; intervals: Interval[] }
export type BranchAction = 'submit' | 'verify' | 'disable' | 'reopen';
const states = ['草稿', '待核验', '营业', '停用'];
const invalid = () => new ApiError('INVALID_RESPONSE', 0, '服务器返回的门店数据不完整，请重新读取。');
function record(v: unknown): Record<string, unknown> {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw invalid();
  return v as Record<string, unknown>;
}
function strings(v: Record<string, unknown>, keys: string[]) { for (const k of keys) if (typeof v[k] !== 'string') throw invalid(); }
function version(v: unknown) { if (!Number.isSafeInteger(v) || Number(v) < 1) throw invalid(); }
export function branchRow(value: unknown): BranchRow {
  const v = record(value); strings(v, ['branch_id', 'code', 'name', 'state', 'manager_id']); version(v.version);
  if (!states.includes(v.state as string) || !/^[1-9]\d*$/.test(v.branch_id as string)) throw invalid();
  return v as unknown as BranchRow;
}
export function branchDetail(value: unknown): BranchDetail {
  branchRow(value); const v = record(value);
  strings(v, ['address', 'phone', 'license_no', 'opening_hours']);
  for (const k of ['license_valid_to', 'verified_by', 'verified_at']) if (v[k] !== null && typeof v[k] !== 'string') throw invalid();
  if (typeof v.license_no_expiry_confirmed !== 'boolean' || typeof v.reauthentication_required !== 'boolean') throw invalid();
  return v as unknown as BranchDetail;
}
export function opening(value: unknown): Opening {
  const v = record(value); strings(v, ['branch_id', 'from_date', 'to_date']); version(v.version);
  if (!Array.isArray(v.intervals)) throw invalid();
  for (const item of v.intervals) {
    const i = record(item); strings(i, ['valid_on', 'starts_at', 'ends_at']);
    if (!Number.isFinite(Date.parse(i.starts_at as string)) || !Number.isFinite(Date.parse(i.ends_at as string))) throw invalid();
  }
  return v as unknown as Opening;
}
export const emptyBranchForm = (): BranchForm => ({ code: '', name: '', address: '', phone: '', license_no: '', license_valid_to: '', opening_hours: '' });
export function formOf(d: BranchDetail): BranchForm {
  return { code: d.code, name: d.name, address: d.address, phone: d.phone, license_no: d.license_no, license_valid_to: d.license_valid_to ?? '', opening_hours: d.opening_hours };
}
export function branchFields(f: BranchForm) {
  return { code: f.code.trim(), name: f.name.trim(), address: f.address.trim(), phone: f.phone.trim(), license_no: f.license_no.trim(), license_valid_to: f.license_valid_to || null, opening_hours: f.opening_hours.trim() };
}
export function createBranchApi(http: HttpClient) {
  const path = (id: string) => `/staff/branches/${encodeURIComponent(id)}`;
  return {
    async list(cursor?: string) {
      const page = await http.list<unknown>('/staff/branches', { query: { cursor, limit: 20 } });
      return { items: page.items.map(branchRow), nextCursor: page.nextCursor };
    },
    async detail(id: string) { return branchDetail((await http.get<unknown>(path(id))).data); },
    async create(body: object, key: string) { return branchDetail((await http.post<unknown>('/staff/branches', body, { idempotencyKey: key })).data); },
    async mutate(id: string, action: 'patch' | BranchAction, body: object, key: string) {
      const result = action === 'patch'
        ? await http.patch<unknown>(path(id), body, { idempotencyKey: key })
        : await http.post<unknown>(`${path(id)}/${action}`, body, { idempotencyKey: key });
      const v = record(result.data); version(v.version);
      if (v.branch_id !== id || !states.includes(v.state as string)) throw invalid();
    },
    async getOpening(id: string, from: string, to: string) {
      return opening((await http.get<unknown>(`${path(id)}/opening-intervals`, { query: { from_date: from, to_date: to } })).data);
    },
    async putOpening(id: string, body: object, key: string) {
      return opening((await http.put<unknown>(`${path(id)}/opening-intervals`, body, { idempotencyKey: key })).data);
    },
  };
}
export type BranchApi = ReturnType<typeof createBranchApi>;

/** 日期/时间直接按上海偏移构造，不受浏览器所在时区影响。24:00仅用于次日零点终点。 */
export function intervalFromInputs(day: string, start: string, end: string): Interval {
  const fail = () => { throw new Error('请填写有效日期和起止时间；跨午夜请拆成两天。'); };
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return fail();
  const date = new Date(`${day}T00:00:00Z`);
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== day) return fail();
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(start) || !(/^([01]\d|2[0-3]):[0-5]\d$/.test(end) || end === '24:00')) return fail();
  const next = new Date(date.getTime() + 86400000).toISOString().slice(0, 10);
  const starts_at = `${day}T${start}:00+08:00`;
  const ends_at = end === '24:00' ? `${next}T00:00:00+08:00` : `${day}T${end}:00+08:00`;
  if (Date.parse(ends_at) <= Date.parse(starts_at)) return fail();
  return { valid_on: day, starts_at, ends_at };
}
