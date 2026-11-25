import { createHttpClient } from './http';
import type { HttpClient, ApiError } from './http';
import { API_BASE_URL } from './config';
import { patientSession } from './session';

export interface RealBookingOverview {
  schedules: Array<{ schedule_id: string; starts_at: string; ends_at: string; branch_id: string; branch_name: string; dentist_id: string; dentist_name: string }>;
  services: Array<{ item_id: string; name: string; minutes: number; price: string }>;
  availability: Array<{ schedule_id: string; item_id: string; duration_minutes: number; slots: string[] }>;
  appointments: RealAppointment[];
}
export interface RealAppointment {
  appointment_id: string;
  previous_id?: string | null;
  followup_plan_id?: string | null;
  patient_name: string;
  dentist_name: string;
  branch_name: string;
  starts_at: string;
  ends_at: string;
  visit_type: string;
  channel: string;
  state: string;
  version: number;
  purpose: string;
  item_name: string;
  ticket_id?: string | null;
  queue_no?: number | null;
}
export interface QueueTicket {
  ticket_id: string;
  appointment_id: string;
  queue_date: string;
  queue_no: number;
  state: string;
  checkin_channel: string;
}
export interface ChangeProposal {
  change_id: string; original_id: string; proposed_id: string; state: string;
  version: number; reason: string; starts_at: string; ends_at: string;
  candidate_state: string; applied: boolean;
}
function parseChange(value: unknown): ChangeProposal {
  const r=record(value);
  return { change_id:str(r['change_id']),original_id:str(r['original_id']),
    proposed_id:str(r['proposed_id']),state:str(r['state']),version:num(r['version']),
    reason:str(r['reason']),starts_at:str(r['starts_at']),ends_at:str(r['ends_at']),
    candidate_state:str(r['candidate_state']),applied:r['applied'] === true };
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('服务器返回了无法解析的预约数据。');
  return value as Record<string, unknown>;
}
function str(value: unknown): string {
  if (typeof value !== 'string') throw new Error('服务器返回了无法解析的预约数据。');
  return value;
}
function num(value: unknown): number {
  if (typeof value !== 'number') throw new Error('服务器返回了无法解析的预约数据。');
  return value;
}
function arr(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('服务器返回了无法解析的预约数据。');
  return value;
}
function parseRow(value: unknown): RealAppointment {
  const r = record(value);
  return {
    appointment_id: str(r['appointment_id']), previous_id: r['previous_id'] == null ? null : str(r['previous_id']),
    followup_plan_id: r['followup_plan_id'] == null ? null : str(r['followup_plan_id']), patient_name: str(r['patient_name']),
    dentist_name: r['dentist_name'] == null ? '' : str(r['dentist_name']), branch_name: str(r['branch_name']),
    starts_at: str(r['starts_at']), ends_at: str(r['ends_at']), visit_type: str(r['visit_type']),
    channel: str(r['channel']), state: str(r['state']), version: num(r['version']),
    purpose: str(r['purpose']), item_name: r['item_name'] == null ? '' : str(r['item_name']),
    ticket_id: r['ticket_id'] == null ? null : str(r['ticket_id']),
    queue_no: r['queue_no'] == null ? null : num(r['queue_no']),
  };
}
function parseTicket(value: unknown): QueueTicket {
  const r = record(value);
  return { ticket_id: str(r['ticket_id']), appointment_id: str(r['appointment_id']),
    queue_date: str(r['queue_date']), queue_no: num(r['queue_no']),
    state: str(r['state']), checkin_channel: str(r['checkin_channel']) };
}
function parseOverview(value: unknown): RealBookingOverview {
  const v = record(value);
  return {
    schedules: arr(v['schedules']).map((x) => { const r = record(x); return { schedule_id: str(r['schedule_id']), starts_at: str(r['starts_at']), ends_at: str(r['ends_at']), branch_id: str(r['branch_id']), branch_name: str(r['branch_name']), dentist_id: str(r['dentist_id']), dentist_name: str(r['dentist_name']) }; }),
    services: arr(v['services']).map((x) => { const r = record(x); return { item_id: str(r['item_id']), name: str(r['name']), minutes: num(r['minutes']), price: str(r['price']) }; }),
    availability: arr(v['availability'] ?? []).map((x) => { const r = record(x); return { schedule_id: str(r['schedule_id']), item_id: str(r['item_id']), duration_minutes: num(r['duration_minutes']), slots: arr(r['slots']).map(str) }; }),
    appointments: arr(v['appointments']).map(parseRow),
  };
}

export function createRealBookingApi(client?: HttpClient) {
  const httpClient = client ?? createHttpClient({
    baseUrl: API_BASE_URL,
    timeoutMs: 15000,
    getBearerToken: () => patientSession.token,
  });
  const pending = new Map<string, string>();
  let sessionRevision = patientSession.revision;
  let sequence = 0;
  function keyFor(body: unknown): { fingerprint: string; key: string; revision: number } {
    if (sessionRevision !== patientSession.revision) {
      pending.clear(); sessionRevision = patientSession.revision;
    }
    const fingerprint = JSON.stringify(body);
    let key = pending.get(fingerprint);
    if (!key) {
      key = `patient-${Date.now().toString(36)}-${(++sequence).toString(36)}-${Math.random().toString(36).slice(2)}`;
      pending.set(fingerprint, key);
    }
    return { fingerprint, key, revision: sessionRevision };
  }
  return {
    async changes(): Promise<ChangeProposal[]> {
      const r=await httpClient.get<unknown>('/me/changes');
      return arr(r.data).map(parseChange);
    },
    async answerChange(id: string,accept: boolean,expectedVersion: number): Promise<ChangeProposal> {
      const body={accept,expected_version:expectedVersion};
      const request=keyFor({operation:'change-response',id,...body});
      const r=await httpClient.post<unknown>(`/me/changes/${id}/response`,body,{idempotencyKey:request.key});
      const result=parseChange(r.data);
      if(patientSession.revision===request.revision && pending.get(request.fingerprint)===request.key)
        pending.delete(request.fingerprint);
      return result;
    },
    async overview(): Promise<RealBookingOverview> {
      const r = await httpClient.get<unknown>('/me/booking');
      return parseOverview(r.data);
    },
    async create(scheduleId: string, itemId: string, startsAt: string, visitType: string, purpose: string,
      followupPlanId?: string): Promise<RealAppointment> {
      const body = { schedule_id: scheduleId, item_id: itemId, starts_at: startsAt, visit_type: visitType, purpose,
        ...(followupPlanId ? { followup_plan_id: followupPlanId } : {}) };
      const request = keyFor(body);
      const r = await httpClient.post<unknown>('/me/appointments', body, { idempotencyKey: request.key });
      const result = parseRow(r.data);
      if (patientSession.revision === request.revision && pending.get(request.fingerprint) === request.key)
        pending.delete(request.fingerprint);
      return result;
    },
    async reschedule(id: string, scheduleId: string, itemId: string, startsAt: string,
      expectedVersion: number, reason: string): Promise<{ original: RealAppointment; replacement: RealAppointment }> {
      const body = { schedule_id: scheduleId, item_id: itemId, starts_at: startsAt, expected_version: expectedVersion, reason };
      const request = keyFor({ operation: 'reschedule', id, ...body });
      const r = await httpClient.post<unknown>(`/me/appointments/${id}/reschedule`, body, { idempotencyKey: request.key });
      const pair = record(r.data);
      const result = { original: parseRow(pair['original']), replacement: parseRow(pair['replacement']) };
      if (patientSession.revision === request.revision && pending.get(request.fingerprint) === request.key)
        pending.delete(request.fingerprint);
      return result;
    },
    async cancel(id: string, reason: string): Promise<RealAppointment> {
      const r = await httpClient.post<unknown>(`/me/appointments/${id}/cancel`, { reason });
      return parseRow(r.data);
    },
    async checkin(id: string, code: string, expectedVersion: number): Promise<QueueTicket> {
      const body = { branch_checkin_token: code, expected_version: expectedVersion };
      const request = keyFor({ operation: 'checkin', id, ...body });
      const r = await httpClient.post<unknown>(`/me/appointments/${id}/checkin`, body, { idempotencyKey: request.key });
      const result = parseTicket(r.data);
      if (result.appointment_id !== id || result.queue_no < 1) throw new Error('服务器返回了无法解析的候诊凭证。');
      if (patientSession.revision === request.revision && pending.get(request.fingerprint) === request.key)
        pending.delete(request.fingerprint);
      return result;
    },
  };
}
export type RealBookingApi = ReturnType<typeof createRealBookingApi>;
export function isApiError(value: unknown): value is ApiError {
  return typeof value === 'object' && value !== null && 'message' in value && 'status' in value;
}
