import { ApiError, type HttpClient } from './http';

export interface PlanItem {
  item_id: string; service_item_id: string | null; name: string; tooth_position: string; phase: string;
  quantity: number; unit_price_cents: number; amount_cents: number; status: string;
}
export interface PlanRow {
  plan_id: string; patient_name: string; dentist_name: string; title: string; diagnosis: string | null;
  state: string; estimated_total_cents: number; version: number; accepted_at: string | null;
  acceptance_basis: string | null; rejected_reason: string | null; item_count: number; completed_count: number;
  items: PlanItem[];
}
export interface PlanOverview {
  plans: PlanRow[];
  visits: Array<{ visit_id: string; patient_name: string; branch_id: string; dentist_name: string; completed_at: string }>;
  services: Array<{ item_id: string; name: string; minutes: number; price: string }>;
}
function rec(v: unknown): Record<string, unknown> {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new ApiError('INVALID_RESPONSE', 0, '治疗计划数据无法解析。');
  return v as Record<string, unknown>;
}
const s = (v: unknown): string => { if (typeof v !== 'string') throw new ApiError('INVALID_RESPONSE', 0, '治疗计划数据无法解析。'); return v; };
const n = (v: unknown): number => { if (typeof v !== 'number') throw new ApiError('INVALID_RESPONSE', 0, '治疗计划数据无法解析。'); return v; };
const a = (v: unknown): unknown[] => { if (!Array.isArray(v)) throw new ApiError('INVALID_RESPONSE', 0, '治疗计划数据无法解析。'); return v; };
function item(v: unknown): PlanItem {
  const r = rec(v);
  return { item_id: s(r['item_id']), service_item_id: r['service_item_id'] == null ? null : s(r['service_item_id']), name: s(r['name']), tooth_position: s(r['tooth_position'] ?? ''), phase: s(r['phase'] ?? ''), quantity: n(r['quantity']), unit_price_cents: n(r['unit_price_cents']), amount_cents: n(r['amount_cents']), status: s(r['status']) };
}
function plan(v: unknown): PlanRow {
  const r = rec(v);
  return { plan_id: s(r['plan_id']), patient_name: s(r['patient_name']), dentist_name: s(r['dentist_name']), title: s(r['title']), diagnosis: r['diagnosis'] == null ? null : s(r['diagnosis']), state: s(r['state']), estimated_total_cents: n(r['estimated_total_cents']), version: n(r['version']), accepted_at: r['accepted_at'] == null ? null : s(r['accepted_at']), acceptance_basis: r['acceptance_basis'] == null ? null : s(r['acceptance_basis']), rejected_reason: r['rejected_reason'] == null ? null : s(r['rejected_reason']), item_count: n(r['item_count']), completed_count: n(r['completed_count']), items: a(r['items']).map(item) };
}
export function createTreatmentPlanApi(client: HttpClient) {
  return {
    async overview(): Promise<PlanOverview> {
      const r = await client.get<unknown>('/staff/treatment-plans'); const v = rec(r.data);
      return { plans: a(v['plans']).map(plan), visits: a(v['visits']).map((x) => { const q = rec(x); return { visit_id: s(q['visit_id']), patient_name: s(q['patient_name']), branch_id: s(q['branch_id']), dentist_name: s(q['dentist_name']), completed_at: s(q['completed_at']) }; }), services: a(v['services']).map((x) => { const q = rec(x); return { item_id: s(q['item_id']), name: s(q['name']), minutes: n(q['minutes']), price: s(q['price']) }; }) };
    },
    async create(sourceVisitId: string, title: string, diagnosis: string, items: Array<{ serviceItemId: string; name: string; toothPosition: string; phase: string; quantity: number; unitPriceCents: number }>): Promise<PlanRow> {
      const r = await client.post<unknown>('/staff/treatment-plans', { source_visit_id: sourceVisitId, title, diagnosis, items: items.map((i) => ({ service_item_id: Number(i.serviceItemId), name: i.name, tooth_position: i.toothPosition, phase: i.phase, quantity: i.quantity, unit_price_cents: i.unitPriceCents })) });
      return plan(r.data);
    },
    async submit(id: string): Promise<PlanRow> { const r = await client.post<unknown>(`/staff/treatment-plans/${id}/submit`, {}); return plan(r.data); },
    async createBill(id: string): Promise<string> { const r = await client.post<{ bill_id: string }>(`/staff/treatment-plans/${id}/bill`, {}); return r.data.bill_id; },
    async setItemStatus(planId: string, itemId: string, status: string): Promise<PlanRow> { const r = await client.post<unknown>(`/staff/treatment-plans/${planId}/items/${itemId}/status`, { status }); return plan(r.data); },
  };
}
