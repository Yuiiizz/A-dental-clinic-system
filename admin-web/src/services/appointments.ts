import { ApiError, type HttpClient } from './http';

export interface BookingOverview {
  branches: Array<{ branch_id: string; name: string }>;
  patient_count: number;
  schedules: Array<{ schedule_id: string; starts_at: string; ends_at: string; branch_id: string; branch_name: string; dentist_id: string; dentist_name: string }>;
  assistant_shifts: Array<{ shift_id: string; branch_id: string; staff_id: string; name: string; job_title: string; starts_at: string; ends_at: string }>;
  services: Array<{ item_id: string; name: string; minutes: number; price: string }>;
  availability: Array<{ schedule_id: string; item_id: string; slots: string[] }>;
  policies: Array<{ policy_id: string; branch_id: string }>;
  appointments: AppointmentRow[];
}
export interface AppointmentRow {
  appointment_id: string;
  patient_id: string;
  patient_name: string;
  schedule_id: string | null;
  dentist_name: string;
  branch_id: string;
  branch_name: string;
  starts_at: string;
  ends_at: string;
  visit_type: string;
  channel: string;
  state: string;
  version: number;
  purpose: string;
  item_name: string;
  item_id?: string | null;
  previous_id?: string | null;
  coordination_state?: string;
  unavailability_alert?: boolean;
}
export interface QueueTicket {
  ticket_id: string;
  appointment_id: string;
  queue_date: string;
  queue_no: number;
  state: string;
  checkin_channel: string;
}
export interface CheckinInput { id: string; expectedVersion: number; verificationBasis: string; exceptionReason?: string }
export interface ManualContact {
  contact_id: string; appointment_id: string; kind: '人工联系'; channel: string;
  state: string; result: string; contacted_at: string; acknowledged: boolean;
  next_contact_at: string | null; staff_id: string;
}
export interface ManualContactInput {
  appointmentId: string; channel: '电话' | '微信登记' | '当面'; result: string;
  acknowledgmentBasis?: string; nextContactAt?: string;
}
export interface ContactTodo {
  appointment_id: string; starts_at: string; last_contact_id: string | null;
  last_contacted_at: string | null; next_contact_at: string | null; acknowledged: boolean;
}
function parseContactTodo(value: unknown): ContactTodo {
  const r=record(value);
  return {
    appointment_id:text(r['appointment_id']), starts_at:text(r['starts_at']),
    last_contact_id:r['last_contact_id']==null?null:text(r['last_contact_id']),
    last_contacted_at:r['last_contacted_at']==null?null:text(r['last_contacted_at']),
    next_contact_at:r['next_contact_at']==null?null:text(r['next_contact_at']),
    acknowledged:bool(r['acknowledged']),
  };
}
export interface AppointmentChange {
  change_id: string; original_id: string; proposed_id: string; state: string; version: number;
  reason: string; branch_id: string; dentist_id: string; starts_at: string; ends_at: string;
  candidate_state: string; applied: boolean;
}
function parseChange(value: unknown): AppointmentChange {
  const r=record(value);
  return { change_id:text(r['change_id']), original_id:text(r['original_id']),
    proposed_id:text(r['proposed_id']), state:text(r['state']), version:num(r['version']),
    reason:text(r['reason']), branch_id:text(r['branch_id']), dentist_id:text(r['dentist_id']),
    starts_at:text(r['starts_at']), ends_at:text(r['ends_at']),
    candidate_state:text(r['candidate_state']), applied:bool(r['applied']) };
}
function parseContact(value: unknown): ManualContact {
  const r = record(value);
  return {
    contact_id: text(r['contact_id']), appointment_id: text(r['appointment_id']),
    kind: text(r['kind']) as '人工联系', channel: text(r['channel']), state: text(r['state']),
    result: text(r['result']), contacted_at: text(r['contacted_at']),
    acknowledged: bool(r['acknowledged']),
    next_contact_at: r['next_contact_at'] == null ? null : text(r['next_contact_at']),
    staff_id: text(r['staff_id']),
  };
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的预约数据。');
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string') throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的预约数据。');
  return value;
}
function num(value: unknown): number {
  if (typeof value !== 'number') throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的预约数据。');
  return value;
}
function bool(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的预约数据。');
  return value;
}
function arr(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的预约数据。');
  return value;
}
function parseAppointment(value: unknown): AppointmentRow {
  const r = record(value);
  return {
    appointment_id: text(r['appointment_id']), patient_id: text(r['patient_id']), patient_name: text(r['patient_name']),
    schedule_id: r['schedule_id'] == null ? null : text(r['schedule_id']), dentist_name: r['dentist_name'] == null ? '' : text(r['dentist_name']),
    branch_id: text(r['branch_id']), branch_name: text(r['branch_name']), starts_at: text(r['starts_at']),
    ends_at: text(r['ends_at']), visit_type: text(r['visit_type']), channel: text(r['channel']),
    state: text(r['state']), version: num(r['version']), purpose: text(r['purpose']), item_name: r['item_name'] == null ? '' : text(r['item_name']),
    item_id: r['item_id'] == null ? null : text(r['item_id']),
    previous_id: r['previous_id'] == null ? null : text(r['previous_id']),
    coordination_state: r['coordination_state'] == null ? undefined : text(r['coordination_state']),
    unavailability_alert: r['unavailability_alert'] == null ? false : bool(r['unavailability_alert']),
  };
}
function parseTicket(value: unknown): QueueTicket {
  const r = record(value);
  return {
    ticket_id: text(r['ticket_id']), appointment_id: text(r['appointment_id']),
    queue_date: text(r['queue_date']), queue_no: num(r['queue_no']),
    state: text(r['state']), checkin_channel: text(r['checkin_channel']),
  };
}
function parseOverview(value: unknown): BookingOverview {
  const v = record(value);
  return {
    branches: arr(v['branches']).map((x) => { const r = record(x); return { branch_id: text(r['branch_id']), name: text(r['name']) }; }),
    patient_count: v['patient_count'] == null ? arr(v['patients'] ?? []).length : num(v['patient_count']),
    schedules: arr(v['schedules']).map((x) => { const r = record(x); return { schedule_id: text(r['schedule_id']), starts_at: text(r['starts_at']), ends_at: text(r['ends_at']), branch_id: text(r['branch_id']), branch_name: text(r['branch_name']), dentist_id: text(r['dentist_id']), dentist_name: text(r['dentist_name']) }; }),
    assistant_shifts: arr(v['assistant_shifts'] ?? []).map((x) => { const r = record(x); return { shift_id: text(r['shift_id']), branch_id: text(r['branch_id']), staff_id: text(r['staff_id']), name: text(r['name']), job_title: text(r['job_title']), starts_at: text(r['starts_at']), ends_at: text(r['ends_at']) }; }),
    services: arr(v['services']).map((x) => { const r = record(x); return { item_id: text(r['item_id']), name: text(r['name']), minutes: num(r['minutes']), price: text(r['price']) }; }),
    availability: arr(v['availability'] ?? []).map((x) => { const r = record(x); return { schedule_id: text(r['schedule_id']), item_id: text(r['item_id']), slots: arr(r['slots']).map(text) }; }),
    policies: arr(v['policies'] ?? []).map((x) => { const r=record(x); return { policy_id:text(r['policy_id']), branch_id:text(r['branch_id']) }; }),
    appointments: arr(v['appointments']).map(parseAppointment),
  };
}

export interface CreateAppointmentInput {
  patientId: string;
  scheduleId: string;
  itemId: string;
  startsAt: string;
  visitType: string;
  channel: string;
  purpose: string;
  consentBasis?: string;
  patientSelectionToken: string;
}
export interface PatientCandidate { patient_id: string; display_name: string; phone_suffix: string; birth_year: number | null; match_type: string }
export interface PatientSearchResult { matches: PatientCandidate[]; requires_manual_verification: boolean; can_create: boolean; reason: string | null }
export interface PatientSelectionResult { patient_id?: string; selection_token?: string; token_shown_once?: boolean; expires_at?: string }
export interface PatientSummary { patient_id: string; name: string; birth_date: string | null; allergy_status: string; source_channel: string | null; last_visit_dentist: string | null; last_visit_at: string | null }
export interface ReceptionSummary {
  patient_id: string; name: string; phone: string;
  appointments: Array<{ appointment_id: string; starts_at: string; state: string; visit_type: string; dentist_name: string | null; item_name: string | null }>;
  visits: Array<{ completed_at: string; dentist_name: string; item_name: string | null }>;
}
export interface PatientVerification { branch_id: string; name: string; phone: string; verification_method: 'visit_evidence' | 'id_document'; verification_result: 'confirmed'; in_person: true }
export interface AppointmentApi {
  overview(): Promise<BookingOverview>;
  listForDate(date: string): Promise<AppointmentRow[]>;
  searchPatient(branchId: string, name: string, phone: string): Promise<PatientSearchResult>;
  patientSummary(branchId: string, patientId: string): Promise<PatientSummary>;
  receptionSummary(branchId: string, patientId: string): Promise<ReceptionSummary>;
  selectPatient(patientId: string, input: PatientVerification & { resolution: string }): Promise<PatientSelectionResult>;
  createPatient(input: PatientVerification & { birth_date?: string; allergy_status: string; allergy_notes?: string; duplicate_resolution?: string; source_channel?: string }, key: string): Promise<PatientSelectionResult>;
  create(input: CreateAppointmentInput): Promise<AppointmentRow>;
  cancel(id: string, reason: string): Promise<AppointmentRow>;
  checkin(input: CheckinInput): Promise<QueueTicket>;
  queue(branchId: string, date: string): Promise<QueueTicket[]>;
  issueCheckinCode(branchId: string): Promise<{ code: string; expires_at: string }>;
  contacts(branchId: string): Promise<ManualContact[]>;
  contactTodos(branchId: string, cursor?: string): Promise<{ items: ContactTodo[]; nextCursor: string | null }>;
  recordContact(input: ManualContactInput): Promise<ManualContact>;
  changes(branchId: string): Promise<AppointmentChange[]>;
  proposeChange(originalId: string, candidate: { scheduleId: string; itemId: string; startsAt: string; policyId: string }, reason: string): Promise<AppointmentChange>;
  answerChange(id: string, accept: boolean, version: number, basis: string): Promise<AppointmentChange>;
  applyChange(id: string, version: number): Promise<AppointmentChange>;
  withdrawChange(id: string, version: number, reason: string): Promise<AppointmentChange>;
}
export function createAppointmentApi(client: HttpClient): AppointmentApi {
  const pending = new Map<string,string>();
  return {
    async overview() {
      const r = await client.get<unknown>('/staff/booking');
      return parseOverview(r.data);
    },
    async listForDate(date) {
      const r = await client.get<unknown>('/staff/appointments', { query: { date } });
      return arr(r.data).map(parseAppointment);
    },
    async searchPatient(branchId, name, phone) {
      const r = await client.get<PatientSearchResult>('/staff/patients', { query: { branch_id: branchId, name, phone } });
      return r.data;
    },
    async patientSummary(branchId, patientId) {
      const response = await client.get<unknown>(`/staff/patients/${encodeURIComponent(patientId)}/summary`, { query: { branch_id: branchId } });
      const r = record(response.data);
      const optional = (value: unknown): string | null => value == null ? null : text(value);
      return {
        patient_id: text(r['patient_id']), name: text(r['name']), birth_date: optional(r['birth_date']),
        allergy_status: text(r['allergy_status']), source_channel: optional(r['source_channel']),
        last_visit_dentist: optional(r['last_visit_dentist']), last_visit_at: optional(r['last_visit_at']),
      };
    },
    async receptionSummary(branchId, patientId) {
      const response = await client.get<unknown>(`/staff/patients/${encodeURIComponent(patientId)}/reception-summary`, { query: { branch_id: branchId } });
      const r = record(response.data);
      const optional = (value: unknown): string | null => value == null ? null : text(value);
      return {
        patient_id: text(r['patient_id']), name: text(r['name']), phone: text(r['phone']),
        appointments: arr(r['appointments']).map((value) => {
          const row = record(value);
          return { appointment_id: text(row['appointment_id']), starts_at: text(row['starts_at']), state: text(row['state']),
            visit_type: text(row['visit_type']), dentist_name: optional(row['dentist_name']), item_name: optional(row['item_name']) };
        }),
        visits: arr(r['visits']).map((value) => {
          const row = record(value);
          return { completed_at: text(row['completed_at']), dentist_name: text(row['dentist_name']), item_name: optional(row['item_name']) };
        }),
      };
    },
    async selectPatient(patientId, input) {
      const r = await client.post<PatientSelectionResult>(`/staff/patients/${encodeURIComponent(patientId)}/selection`, input);
      return r.data;
    },
    async createPatient(input, key) {
      const r = await client.post<PatientSelectionResult>('/staff/patients', input, { idempotencyKey: key });
      return r.data;
    },
    async create(input) {
      const fingerprint=JSON.stringify(input);
      const key=pending.get(fingerprint) ?? crypto.randomUUID();
      pending.set(fingerprint,key);
      const r = await client.post<unknown>('/staff/appointments', {
        patient_id: input.patientId, schedule_id: input.scheduleId, item_id: input.itemId,
        starts_at: input.startsAt, visit_type: input.visitType, channel: input.channel, purpose: input.purpose, consent_basis: input.consentBasis,
        patient_selection_token: input.patientSelectionToken,
      }, { idempotencyKey:key });
      const result=parseAppointment(r.data);
      pending.delete(fingerprint);
      return result;
    },
    async cancel(id, reason) {
      const r = await client.post<unknown>(`/staff/appointments/${id}/cancel`, { reason });
      return parseAppointment(r.data);
    },
    async checkin(input) {
      const body = { expected_version: input.expectedVersion,
        verification_basis: input.verificationBasis.trim(),
        ...(input.exceptionReason?.trim() ? { exception_reason: input.exceptionReason.trim() } : {}) };
      const fingerprint = JSON.stringify({ id: input.id, ...body });
      const key = pending.get(fingerprint) ?? crypto.randomUUID();
      pending.set(fingerprint, key);
      const r = await client.post<unknown>(`/staff/appointments/${encodeURIComponent(input.id)}/checkin`, body, { idempotencyKey: key });
      const ticket = parseTicket(r.data);
      pending.delete(fingerprint);
      return ticket;
    },
    async queue(branchId, date) {
      const r = await client.get<unknown>('/staff/queue', { query: { branch_id: branchId, date } });
      return arr(r.data).map(parseTicket);
    },
    async issueCheckinCode(branchId) {
      const r = await client.post<unknown>(`/staff/branches/${encodeURIComponent(branchId)}/checkin-code`, undefined,
        { idempotencyKey: crypto.randomUUID() });
      const value = record(r.data);
      return { code: text(value['code']), expires_at: text(value['expires_at']) };
    },
    async contacts(branchId) {
      const page = await client.list<unknown>('/staff/contacts', { query: { branch_id: branchId, limit: '100' } });
      return page.items.map(parseContact);
    },
    async contactTodos(branchId,cursor) {
      const page=await client.list<unknown>('/staff/contacts/due',
        {query:{branch_id:branchId,limit:'20',...(cursor?{cursor}:{})}});
      return {items:page.items.map(parseContactTodo),nextCursor:page.nextCursor};
    },
    async recordContact(input) {
      const body = {
        kind: '人工联系', channel: input.channel, appointment_id: input.appointmentId,
        result: input.result.trim(),
        ...(input.acknowledgmentBasis?.trim() ? { acknowledgment_basis: input.acknowledgmentBasis.trim() } : {}),
        ...(input.nextContactAt ? { next_contact_at: input.nextContactAt } : {}),
      };
      const fingerprint = JSON.stringify(body);
      const key = pending.get(fingerprint) ?? crypto.randomUUID();
      pending.set(fingerprint, key);
      const response = await client.post<unknown>('/staff/contacts', body, { idempotencyKey: key });
      const contact = parseContact(response.data);
      pending.delete(fingerprint);
      return contact;
    },
    async changes(branchId) {
      const r=await client.get<unknown>('/staff/changes',{query:{branch_id:branchId}});
      return arr(r.data).map(parseChange);
    },
    async proposeChange(originalId,candidate,reason) {
      const body={candidate:{schedule_id:candidate.scheduleId,item_id:candidate.itemId,
        starts_at:candidate.startsAt,policy_id:candidate.policyId},reason:reason.trim()};
      const fingerprint=JSON.stringify({operation:'propose-change',originalId,...body});
      const key=pending.get(fingerprint)??crypto.randomUUID(); pending.set(fingerprint,key);
      const r=await client.post<unknown>(`/staff/appointments/${encodeURIComponent(originalId)}/changes`,body,{idempotencyKey:key});
      const parsed=parseChange(r.data); pending.delete(fingerprint); return parsed;
    },
    async answerChange(id,accept,version,basis) {
      const body={accept,expected_version:version,confirmation_basis:basis.trim()};
      const fingerprint=JSON.stringify({operation:'answer-change',id,...body});
      const key=pending.get(fingerprint)??crypto.randomUUID(); pending.set(fingerprint,key);
      const r=await client.post<unknown>(`/staff/changes/${encodeURIComponent(id)}/response`,body,{idempotencyKey:key});
      const parsed=parseChange(r.data); pending.delete(fingerprint); return parsed;
    },
    async applyChange(id,version) {
      const body={expected_version:version};
      const fingerprint=JSON.stringify({operation:'apply-change',id,...body});
      const key=pending.get(fingerprint)??crypto.randomUUID(); pending.set(fingerprint,key);
      const r=await client.post<unknown>(`/staff/changes/${encodeURIComponent(id)}/apply`,body,{idempotencyKey:key});
      const parsed=parseChange(r.data); pending.delete(fingerprint); return parsed;
    },
    async withdrawChange(id,version,reason) {
      const body={expected_version:version,reason:reason.trim()};
      const fingerprint=JSON.stringify({operation:'withdraw-change',id,...body});
      const key=pending.get(fingerprint)??crypto.randomUUID(); pending.set(fingerprint,key);
      const r=await client.post<unknown>(`/staff/changes/${encodeURIComponent(id)}/withdraw`,body,{idempotencyKey:key});
      const parsed=parseChange(r.data); pending.delete(fingerprint); return parsed;
    },
  };
}
