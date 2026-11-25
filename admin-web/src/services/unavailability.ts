import { ApiError, type HttpClient } from './http';

export interface AffectedAppointment {
  appointment_id: string;
  starts_at: string;
  ends_at: string;
  state: string;
  coordination_state: string;
  item_name: string;
  branch_id: string;
  branch_name: string;
}

export interface DentistUnavailability {
  absence_id: string;
  doctor_id: string;
  doctor_name: string;
  starts_at: string;
  ends_at: string;
  reason: string;
  state: '有效' | '已撤销';
  recorded_by: string;
  recorded_at: string;
  resolved_at: string | null;
  resolved_by: string | null;
  resolve_basis: string | null;
  affected_count: number;
  affected_appointments: AffectedAppointment[];
}

export interface ResourceUnavailability {
  unavailable_id: string;
  resource_id: string;
  resource_name: string;
  resource_type: string;
  branch_id: string;
  branch_name: string;
  kind: '维护' | '故障';
  starts_at: string;
  ends_at: string;
  reason: string;
  state: '有效' | '已撤销';
  recorded_by: string;
  recorded_at: string;
  resolved_at: string | null;
  resolved_by: string | null;
  resolve_type: string | null;
  resolve_basis: string | null;
  affected_count: number;
  affected_appointments: AffectedAppointment[];
}

export interface UnavailabilityBranch {
  branch_id: string;
  name: string;
}

export interface UnavailabilityDentist {
  staff_id: string;
  name: string;
  branch_name: string;
}

export interface UnavailabilityResource {
  resource_id: string;
  name: string;
  resource_type: string;
  operating_state: string;
  branch_id: string;
  branch_name: string;
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护数据。');
  }
  return value as Record<string, unknown>;
}
function arr(value: unknown): unknown[] {
  if (!Array.isArray(value)) {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护数据。');
  }
  return value;
}
function text(value: unknown): string {
  if (typeof value !== 'string' || value === '') {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护数据。');
  }
  return value;
}
function nullableText(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护数据。');
  }
  return value;
}
function int(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护数据。');
  }
  return value;
}
function state(value: unknown): '有效' | '已撤销' {
  const normalized = text(value);
  if (normalized !== '有效' && normalized !== '已撤销') {
    throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的停诊/资源维护状态。');
  }
  return normalized;
}
function affected(value: unknown): AffectedAppointment {
  const r = record(value);
  return {
    appointment_id: text(r['appointment_id']),
    starts_at: text(r['starts_at']),
    ends_at: text(r['ends_at']),
    state: text(r['state']),
    coordination_state: text(r['coordination_state']),
    item_name: text(r['item_name']),
    branch_id: text(r['branch_id']),
    branch_name: text(r['branch_name']),
  };
}
function affectedList(value: unknown): AffectedAppointment[] {
  return arr(value).map(affected);
}
function doctor(value: unknown): DentistUnavailability {
  const r = record(value);
  return {
    absence_id: text(r['absence_id']),
    doctor_id: text(r['doctor_id']),
    doctor_name: text(r['doctor_name']),
    starts_at: text(r['starts_at']),
    ends_at: text(r['ends_at']),
    reason: text(r['reason']),
    state: state(r['state']),
    recorded_by: text(r['recorded_by']),
    recorded_at: text(r['recorded_at']),
    resolved_at: nullableText(r['resolved_at']),
    resolved_by: nullableText(r['resolved_by']),
    resolve_basis: nullableText(r['resolve_basis']),
    affected_count: int(r['affected_count']),
    affected_appointments: affectedList(r['affected_appointments']),
  };
}
function resource(value: unknown): ResourceUnavailability {
  const r = record(value);
  return {
    unavailable_id: text(r['unavailable_id']),
    resource_id: text(r['resource_id']),
    resource_name: text(r['resource_name']),
    resource_type: text(r['resource_type']),
    branch_id: text(r['branch_id']),
    branch_name: text(r['branch_name']),
    kind: text(r['kind']) as '维护' | '故障',
    starts_at: text(r['starts_at']),
    ends_at: text(r['ends_at']),
    reason: text(r['reason']),
    state: state(r['state']),
    recorded_by: text(r['recorded_by']),
    recorded_at: text(r['recorded_at']),
    resolved_at: nullableText(r['resolved_at']),
    resolved_by: nullableText(r['resolved_by']),
    resolve_type: nullableText(r['resolve_type']),
    resolve_basis: nullableText(r['resolve_basis']),
    affected_count: int(r['affected_count']),
    affected_appointments: affectedList(r['affected_appointments']),
  };
}
export interface UnavailabilityApi {
  branches(): Promise<UnavailabilityBranch[]>;
  dentists(): Promise<UnavailabilityDentist[]>;
  doctorList(state?: '有效' | '已撤销'): Promise<DentistUnavailability[]>;
  resourceList(branchId: string, state?: '有效' | '已撤销'): Promise<ResourceUnavailability[]>;
  resources(branchId: string): Promise<UnavailabilityResource[]>;
  createDoctor(input: { doctorId: string; startsAt: string; endsAt: string; reason: string }, key: string): Promise<void>;
  resolveDoctor(id: string, basis: string, key: string): Promise<void>;
  createResource(input: { branchId: string; resourceId: string; kind: '维护' | '故障'; startsAt: string; endsAt: string; reason: string }, key: string): Promise<void>;
  resolveResource(id: string, resolveType: '误登记撤销' | '已修复', basis: string, key: string): Promise<void>;
}

export function createUnavailabilityApi(client: HttpClient): UnavailabilityApi {
  return {
    async branches() {
      const result = await client.list<unknown>('/staff/branches', { query: { limit: 100 } });
      return result.items.map((value) => {
        const r = record(value);
        return { branch_id: text(r['branch_id']), name: text(r['name']) };
      });
    },
    async dentists() {
      const result = await client.get<unknown>('/staff/scheduling');
      const r = record(result.data);
      return arr(r['dentists']).map((value) => {
        const d = record(value);
        return { staff_id: text(d['staff_id']), name: text(d['name']), branch_name: text(d['branch_name']) };
      });
    },
    async doctorList(state) {
      const result = await client.get<unknown>('/staff/dentist-unavailability', {
        query: { state, limit: 100 },
      });
      return arr(record(result.data)['items']).map(doctor);
    },
    async resourceList(branchId, state) {
      const result = await client.get<unknown>('/staff/resource-unavailability', {
        query: { branch_id: branchId, state, limit: 100 },
      });
      return arr(record(result.data)['items']).map(resource);
    },
    async resources(branchId) {
      const result = await client.get<unknown>('/staff/resources', { query: { branch_id: branchId } });
      return arr(record(result.data)['items']).map((value) => {
        const r = record(value);
        return {
          resource_id: text(r['resource_id']),
          name: text(r['name']),
          resource_type: text(r['resource_type']),
          operating_state: text(r['operating_state']),
          branch_id: text(r['branch_id']),
          branch_name: text(r['branch_name']),
        };
      });
    },
    async createDoctor(input, key) {
      await client.post('/staff/dentist-unavailability', {
        doctor_id: input.doctorId,
        starts_at: input.startsAt,
        ends_at: input.endsAt,
        reason: input.reason,
      }, { idempotencyKey: key });
    },
    async resolveDoctor(id, basis, key) {
      await client.post(`/staff/dentist-unavailability/${encodeURIComponent(id)}/resolve`,
        { resolve_basis: basis }, { idempotencyKey: key });
    },
    async createResource(input, key) {
      await client.post('/staff/resource-unavailability', {
        resource_id: input.resourceId,
        kind: input.kind,
        starts_at: input.startsAt,
        ends_at: input.endsAt,
        reason: input.reason,
      }, { idempotencyKey: key });
    },
    async resolveResource(id, resolveType, basis, key) {
      await client.post(`/staff/resource-unavailability/${encodeURIComponent(id)}/resolve`, {
        resolve_type: resolveType,
        resolve_basis: basis,
      }, { idempotencyKey: key });
    },
  };
}
