import { createHttpClient } from './http';
import type { HttpClient } from './http';
import { API_BASE_URL } from './config';
import { patientSession } from './session';

export interface PatientVisit {
  visit_id: string;
  appointment_id: string;
  patient_name: string;
  dentist_name: string;
  allergy_result: string;
  allergy_snapshot: string | null;
  clinical_notes: string;
  started_at: string;
  completed_at: string;
}
export interface PatientPlan {
  plan_id: string;
  source_visit_id: string;
  dentist_id: string;
  patient_name: string;
  dentist_name: string;
  suggested_from: string;
  suggested_to: string;
  duration_minutes: number;
  service_item_id: string | null;
  service_item_name: string | null;
  purpose: string;
  state: string;
}
export interface PatientRecords {
  visits: PatientVisit[];
  plans: PatientPlan[];
}
function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('就诊记录无法解析。');
  return value as Record<string, unknown>;
}
const str = (v: unknown): string => { if (typeof v !== 'string') throw new Error('就诊记录无法解析。'); return v; };
const num = (v: unknown): number => { if (typeof v !== 'number') throw new Error('就诊记录无法解析。'); return v; };
const arr = (v: unknown): unknown[] => { if (!Array.isArray(v)) throw new Error('就诊记录无法解析。'); return v; };
export function parseRecords(value: unknown): PatientRecords {
  const r = record(value);
  return {
    visits: arr(r['visits']).map((x) => { const v = record(x); return { visit_id: str(v['visit_id']), appointment_id: str(v['appointment_id']), patient_name: str(v['patient_name']), dentist_name: str(v['dentist_name']), allergy_result: str(v['allergy_result']), allergy_snapshot: v['allergy_snapshot'] == null ? null : str(v['allergy_snapshot']), clinical_notes: str(v['clinical_notes'] ?? ''), started_at: str(v['started_at']), completed_at: str(v['completed_at']) }; }),
    plans: arr(r['plans']).map((x) => { const p = record(x); return { plan_id: str(p['plan_id']), source_visit_id: str(p['source_visit_id']), dentist_id: str(p['dentist_id']), patient_name: str(p['patient_name']), dentist_name: str(p['dentist_name']), suggested_from: str(p['suggested_from']), suggested_to: str(p['suggested_to']), duration_minutes: num(p['duration_minutes']), service_item_id: p['service_item_id'] == null ? null : str(p['service_item_id']), service_item_name: p['service_item_name'] == null ? null : str(p['service_item_name']), purpose: str(p['purpose']), state: str(p['state']) }; }),
  };
}

export interface TreatmentItem {
  item_id: string;
  name: string;
  tooth_position: string;
  phase: string;
  quantity: number;
  unit_price_cents: number;
  amount_cents: number;
  status: string;
}
export interface TreatmentPlan {
  plan_id: string;
  title: string;
  diagnosis: string | null;
  state: string;
  estimated_total_cents: number;
  dentist_name: string;
  accepted_at: string | null;
  acceptance_basis: string | null;
  rejected_reason: string | null;
  items: TreatmentItem[];
}
function parsePlan(v: unknown): TreatmentPlan {
  const r = record(v);
  return {
    plan_id: str(r['plan_id']), title: str(r['title']), diagnosis: r['diagnosis'] == null ? null : str(r['diagnosis']),
    state: str(r['state']), estimated_total_cents: num(r['estimated_total_cents']), dentist_name: str(r['dentist_name']),
    accepted_at: r['accepted_at'] == null ? null : str(r['accepted_at']),
    acceptance_basis: r['acceptance_basis'] == null ? null : str(r['acceptance_basis']),
    rejected_reason: r['rejected_reason'] == null ? null : str(r['rejected_reason']),
    items: arr(r['items']).map((x) => { const i = record(x); return { item_id: str(i['item_id']), name: str(i['name']), tooth_position: str(i['tooth_position'] ?? ''), phase: str(i['phase'] ?? ''), quantity: num(i['quantity']), unit_price_cents: num(i['unit_price_cents']), amount_cents: num(i['amount_cents']), status: str(i['status']) }; }),
  };
}

export function createRecordsApi(client?: HttpClient) {
  const httpClient = client ?? createHttpClient({
    baseUrl: API_BASE_URL, timeoutMs: 15000, getBearerToken: () => patientSession.token,
  });
  return {
    async load(): Promise<PatientRecords> { const r = await httpClient.get<unknown>('/me/records'); return parseRecords(r.data); },
    async loadTreatmentPlans(): Promise<TreatmentPlan[]> {
      const r = await httpClient.get<unknown>('/me/treatment-plans');
      return arr(record(r.data)['plans']).map(parsePlan);
    },
    async acceptPlan(id: string): Promise<TreatmentPlan> {
      const r = await httpClient.post<unknown>(`/me/treatment-plans/${id}/accept`, { basis: '患者端确认治疗计划' });
      return parsePlan(r.data);
    },
    async rejectPlan(id: string): Promise<TreatmentPlan> {
      const r = await httpClient.post<unknown>(`/me/treatment-plans/${id}/reject`, { reason: '患者端暂时不接受' });
      return parsePlan(r.data);
    },
  };
}
