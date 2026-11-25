import { ApiError, type HttpClient } from './http';

export interface ClinicalOverview {
  appointments: Array<{ appointment_id: string; patient_id: string; patient_name: string; dentist_name: string; branch_name: string; starts_at: string; state: string; version: number; followup_plan_id: string | null }>;
  visits: VisitRow[];
  plan_source_visits: VisitRow[];
  plans: PlanRow[];
  plan_services: Array<{ item_id: string; name: string }>;
}
export interface VisitRow {
  visit_id: string;
  appointment_id: string;
  patient_id: string;
  patient_name: string;
  dentist_name: string;
  allergy_result: string;
  allergy_snapshot: string | null;
  clinical_notes: string;
  started_at: string;
  completed_at: string;
}
export interface PlanRow {
  plan_id: string;
  source_visit_id: string;
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
function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '临床数据无法解析。');
  return value as Record<string, unknown>;
}
const text = (v: unknown): string => { if (typeof v !== 'string') throw new ApiError('INVALID_RESPONSE', 0, '临床数据无法解析。'); return v; };
const num = (v: unknown): number => { if (typeof v !== 'number') throw new ApiError('INVALID_RESPONSE', 0, '临床数据无法解析。'); return v; };
const arr = (v: unknown): unknown[] => { if (!Array.isArray(v)) throw new ApiError('INVALID_RESPONSE', 0, '临床数据无法解析。'); return v; };
function parseVisit(v: unknown): VisitRow {
  const r = record(v);
  return { visit_id: text(r['visit_id']), appointment_id: text(r['appointment_id']), patient_id: text(r['patient_id']), patient_name: text(r['patient_name']), dentist_name: text(r['dentist_name']), allergy_result: text(r['allergy_result']), allergy_snapshot: r['allergy_snapshot'] == null ? null : text(r['allergy_snapshot']), clinical_notes: text(r['clinical_notes'] ?? ''), started_at: text(r['started_at']), completed_at: text(r['completed_at']) };
}
function parsePlan(v: unknown): PlanRow {
  const r = record(v);
  return { plan_id: text(r['plan_id']), source_visit_id: text(r['source_visit_id']), patient_name: text(r['patient_name']), dentist_name: text(r['dentist_name']), suggested_from: text(r['suggested_from']), suggested_to: text(r['suggested_to']), duration_minutes: num(r['duration_minutes']), service_item_id: r['service_item_id'] == null ? null : text(r['service_item_id']), service_item_name: r['service_item_name'] == null ? null : text(r['service_item_name']), purpose: text(r['purpose']), state: text(r['state']) };
}
function parseOverview(v: unknown): ClinicalOverview {
  const r = record(v);
  return {
    appointments: arr(r['appointments']).map((x) => { const a = record(x); return { appointment_id: text(a['appointment_id']), patient_id: text(a['patient_id']), patient_name: text(a['patient_name']), dentist_name: text(a['dentist_name']), branch_name: text(a['branch_name']), starts_at: text(a['starts_at']), state: text(a['state']), version: num(a['version']), followup_plan_id: a['followup_plan_id'] == null ? null : text(a['followup_plan_id']) }; }),
    visits: arr(r['visits']).map(parseVisit),
    plan_source_visits: arr(r['plan_source_visits']).map(parseVisit),
    plans: arr(r['plans']).map(parsePlan),
    plan_services: arr(r['plan_services']).map((x) => { const a = record(x); return { item_id: text(a['item_id']), name: text(a['name']) }; }),
  };
}
export interface ClinicalApi {
  overview(): Promise<ClinicalOverview>;
  completeVisit(appointmentId: string, allergy: string, snapshot: string, notes: string, followupGoalAchieved?: boolean): Promise<VisitRow>;
  createPlan(visitId: string, from: string, to: string, duration: number, purpose: string, serviceItemId: string): Promise<PlanRow>;
}
export function createClinicalApi(client: HttpClient): ClinicalApi {
  return {
    async overview() { const r = await client.get<unknown>('/staff/clinical'); return parseOverview(r.data); },
    async completeVisit(appointmentId, allergy, snapshot, notes, followupGoalAchieved) {
      const r = await client.post<unknown>('/staff/visits', { appointment_id: appointmentId, allergy_result: allergy, allergy_snapshot: snapshot, clinical_notes: notes,
        ...(followupGoalAchieved === undefined ? {} : { followup_goal_achieved: followupGoalAchieved }) });
      return parseVisit(r.data);
    },
    async createPlan(visitId, from, to, duration, purpose, serviceItemId) {
      const r = await client.post<unknown>('/staff/followup-plans', { visit_id: visitId, suggested_from: from, suggested_to: to, duration_minutes: duration, purpose,
        ...(serviceItemId ? { service_item_id: Number(serviceItemId) } : {}) });
      return parsePlan(r.data);
    },
  };
}
