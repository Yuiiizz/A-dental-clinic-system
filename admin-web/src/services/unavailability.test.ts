import { expect, it, vi } from 'vitest';
import { createUnavailabilityApi } from './unavailability';
import type { HttpClient } from './http';

const affected = {
  appointment_id: '41', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T01:30:00Z',
  state: '已确认', coordination_state: '无需协调', item_name: '初诊检查',
  branch_id: '1', branch_name: '一店',
};

it('loads doctor and resource records with affected appointment summaries', async () => {
  const get = vi.fn()
    .mockResolvedValueOnce({ data: { items: [{
      absence_id: '7', doctor_id: '1', doctor_name: '示例医生',
      starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T02:00:00Z',
      reason: '临时停诊', state: '有效', recorded_by: '3', recorded_at: '2030-01-02T00:00:00Z',
      resolved_at: null, resolved_by: null, resolve_basis: null,
      affected_count: 1, affected_appointments: [affected],
    }] } })
    .mockResolvedValueOnce({ data: { items: [{
      unavailable_id: '8', resource_id: '1', resource_name: '一店牙椅', resource_type: '牙椅',
      branch_id: '1', branch_name: '一店', kind: '维护',
      starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T02:00:00Z',
      reason: '消毒', state: '有效', recorded_by: '3', recorded_at: '2030-01-02T00:00:00Z',
      resolved_at: null, resolved_by: null, resolve_type: null, resolve_basis: null,
      affected_count: 1, affected_appointments: [affected],
    }] } });
  const api = createUnavailabilityApi({ get } as unknown as HttpClient);
  const doctors = await api.doctorList('有效');
  expect(doctors[0].affected_appointments[0].appointment_id).toBe('41');
  expect(doctors[0].affected_appointments[0].coordination_state).toBe('无需协调');
  expect(doctors[0]).not.toHaveProperty('patient_name');
  const resources = await api.resourceList('1', '有效');
  expect(resources[0].resource_name).toBe('一店牙椅');
  expect(resources[0].affected_count).toBe(1);
  expect(get.mock.calls[0][1]).toEqual({ query: { state: '有效', limit: 100 } });
  expect(get.mock.calls[1][1]).toEqual({ query: { branch_id: '1', state: '有效', limit: 100 } });
});

it('loads branch/resource options and preserves idempotency keys on write retries', async () => {
  const list = vi.fn().mockResolvedValue({ items: [{ branch_id: '1', name: '一店' }], nextCursor: null });
  const get = vi.fn()
    .mockResolvedValueOnce({ data: { dentists: [{ staff_id: '1', name: '示例医生', branch_name: '一店' }] } })
    .mockResolvedValueOnce({ data: { items: [{
      resource_id: '9', name: '一店牙椅', resource_type: '牙椅', operating_state: '可用',
      branch_id: '1', branch_name: '一店',
    }] } });
  const post = vi.fn().mockResolvedValue({ data: {} });
  const api = createUnavailabilityApi({ list, get, post } as unknown as HttpClient);
  expect((await api.branches())[0].name).toBe('一店');
  expect((await api.dentists())[0].staff_id).toBe('1');
  expect((await api.resources('1'))[0].operating_state).toBe('可用');
  await api.createDoctor({ doctorId: '1', startsAt: '2030-01-02T01:00:00+08:00', endsAt: '2030-01-02T02:00:00+08:00', reason: '临时停诊' }, 'doctor-key');
  expect(post.mock.calls[0][0]).toBe('/staff/dentist-unavailability');
  expect(post.mock.calls[0][2]).toEqual({ idempotencyKey: 'doctor-key' });
  await api.resolveDoctor('7', '已核实', 'resolve-key');
  expect(post.mock.calls[1][0]).toBe('/staff/dentist-unavailability/7/resolve');
  expect(post.mock.calls[1][2]).toEqual({ idempotencyKey: 'resolve-key' });
});

it('rejects a malformed record instead of fabricating an affected appointment', async () => {
  const get = vi.fn().mockResolvedValue({ data: { items: [{
    absence_id: '7', doctor_id: '1', doctor_name: '示例医生',
    starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T02:00:00Z',
    reason: '临时停诊', state: '有效', recorded_by: '3', recorded_at: '2030-01-02T00:00:00Z',
    resolved_at: null, resolved_by: null, resolve_basis: null,
    affected_count: 1, affected_appointments: [{ appointment_id: '41' }],
  }] } });
  const api = createUnavailabilityApi({ get } as unknown as HttpClient);
  await expect(api.doctorList()).rejects.toThrow(/无法解析/);
});
