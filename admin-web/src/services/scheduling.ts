import { ApiError, type HttpClient } from './http';

export interface SchedulingOverview {
  branches: Array<{ branch_id: string; name: string; code: string; state: string }>;
  dentists: Array<{ staff_id: string; name: string; job_title: string; branch_id: string; branch_name: string }>;
  service_items: Array<{ item_id: string; name: string; price: string; minutes: number; appointment_enabled: boolean; category_name: string }>;
  resources: Array<{ resource_id: string; name: string; type: string; state: string; branch_name: string }>;
  scopes: Array<{ staff_id: string; item_id: string; name: string }>;
  schedules: Array<{ schedule_id: string; dentist_id: string; dentist_name: string; branch_id: string; branch_name: string; starts_at: string; ends_at: string; service_type: string; state: string; version: number; item_ids: string[] }>;
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的排班配置。');
  return value as Record<string, unknown>;
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的排班配置。');
  return value;
}
function num(value: unknown): number {
  if (typeof value !== 'number') throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的排班配置。');
  return value;
}
function arr(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的排班配置。');
  return value;
}
function parseOverview(value: unknown): SchedulingOverview {
  const v = record(value);
  return {
    branches: arr(v['branches']).map((x) => { const r = record(x); return { branch_id: text(r['branch_id']), name: text(r['name']), code: text(r['code']), state: text(r['state']) }; }),
    dentists: arr(v['dentists']).map((x) => { const r = record(x); return { staff_id: text(r['staff_id']), name: text(r['name']), job_title: text(r['job_title']), branch_id: text(r['branch_id']), branch_name: text(r['branch_name']) }; }),
    service_items: arr(v['service_items']).map((x) => { const r = record(x); return { item_id: text(r['item_id']), name: text(r['name']), price: text(r['price']), minutes: num(r['minutes']), appointment_enabled: Boolean(r['appointment_enabled']), category_name: text(r['category_name']) }; }),
    resources: arr(v['resources']).map((x) => { const r = record(x); return { resource_id: text(r['resource_id']), name: text(r['name']), type: text(r['type']), state: text(r['state']), branch_name: text(r['branch_name']) }; }),
    scopes: arr(v['scopes']).map((x) => { const r = record(x); return { staff_id: text(r['staff_id']), item_id: text(r['item_id']), name: text(r['name']) }; }),
    schedules: arr(v['schedules']).map((x) => { const r = record(x); return { schedule_id: text(r['schedule_id']), dentist_id: text(r['dentist_id']), dentist_name: text(r['dentist_name']), branch_id: text(r['branch_id']), branch_name: text(r['branch_name']), starts_at: text(r['starts_at']), ends_at: text(r['ends_at']), service_type: text(r['service_type']), state: text(r['state']), version: num(r['version']), item_ids: arr(r['item_ids']).map(String) }; }),
  };
}

export interface ScheduleInput {
  dentistId: string;
  branchId: string;
  date: string;
  start: string;
  end: string;
  itemIds: string[];
}
export interface SchedulingApi {
  overview(): Promise<SchedulingOverview>;
  createSchedule(input: ScheduleInput): Promise<void>;
  publish(scheduleId: string): Promise<void>;
  createService(input: { name: string; price: string; minutes: number; branchId: string }): Promise<void>;
  createResource(input: { name: string; branchId: string }): Promise<void>;
  setDoctorServices(staffId: string, itemIds: string[], approvalBasis: string): Promise<void>;
}

export function createSchedulingApi(client: HttpClient): SchedulingApi {
  return {
    async overview() {
      const r = await client.get<unknown>('/staff/scheduling');
      return parseOverview(r.data);
    },
    async createSchedule(input) {
      await client.post('/staff/scheduling/schedules', {
        dentist_id: input.dentistId,
        branch_id: input.branchId,
        starts_at: `${input.date}T${input.start}:00+08:00`,
        ends_at: `${input.date}T${input.end}:00+08:00`,
        item_ids: input.itemIds,
      });
    },
    async publish(scheduleId) {
      await client.post(`/staff/scheduling/schedules/${scheduleId}/publish`, {});
    },
    async createService(input) {
      await client.post('/staff/scheduling/service-items', {
        name: input.name, price: input.price, minutes: input.minutes, branch_id: input.branchId,
      });
    },
    async createResource(input) {
      await client.post('/staff/scheduling/resources', { name: input.name, branch_id: input.branchId });
    },
    async setDoctorServices(staffId, itemIds, approvalBasis) {
      await client.put(`/staff/scheduling/staff/${encodeURIComponent(staffId)}/services`, { item_ids: itemIds, approval_basis: approvalBasis });
    },
  };
}
