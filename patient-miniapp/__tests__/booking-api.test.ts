import { it, expect, vi } from 'vitest';
import { createRealBookingApi } from '../miniprogram/services/booking-api';
import { patientSession } from '../miniprogram/services/session';
import type { HttpClient } from '../miniprogram/services/http';

it('keeps creation key after network or malformed success, clears on session change', async () => {
  const post = vi.fn().mockRejectedValue(new Error('network'));
  const client = { post } as unknown as HttpClient;
  const api = createRealBookingApi(client);
  const send = () => api.create('1', '1', '2030-01-02T09:00:00+08:00', '初诊', '测试');
  await expect(send()).rejects.toThrow();
  const first = post.mock.calls[0][2].idempotencyKey;
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[1][2].idempotencyKey).toBe(first);
  post.mockResolvedValueOnce({ data: {} });
  await expect(send()).rejects.toThrow();
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[3][2].idempotencyKey).toBe(first);
  patientSession.clear();
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[4][2].idempotencyKey).not.toBe(first);
});

it('reads availability slots from the backend overview', async () => {
  const get = vi.fn().mockResolvedValueOnce({ data: {
    schedules: [{ schedule_id: '1', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z', branch_id: '1', branch_name: '门店', dentist_id: '1', dentist_name: '医生' }],
    services: [{ item_id: '1', name: '检查', minutes: 30, price: '80.00' }],
    availability: [{ schedule_id: '1', item_id: '1', duration_minutes: 30, slots: ['2030-01-02T01:00:00Z'] }],
    appointments: [],
  } });
  const api = createRealBookingApi({ get } as unknown as HttpClient);
  const overview = await api.overview();
  expect(overview.availability).toEqual([{ schedule_id: '1', item_id: '1', duration_minutes: 30, slots: ['2030-01-02T01:00:00Z'] }]);
  expect(get).toHaveBeenCalledWith('/me/booking');
});

it('includes follow-up plan in the booking request and its idempotency fingerprint', async () => {
  const post = vi.fn().mockRejectedValue(new Error('network'));
  const api = createRealBookingApi({ post } as unknown as HttpClient);
  const send = (planId: string) => api.create('1', '1', '2030-01-02T09:00:00+08:00', '复诊', '复诊检查', planId);
  await expect(send('9')).rejects.toThrow();
  await expect(send('9')).rejects.toThrow();
  await expect(send('10')).rejects.toThrow();
  expect(post.mock.calls[0][1]).toMatchObject({ visit_type: '复诊', followup_plan_id: '9' });
  expect(post.mock.calls[1][2].idempotencyKey).toBe(post.mock.calls[0][2].idempotencyKey);
  expect(post.mock.calls[2][2].idempotencyKey).not.toBe(post.mock.calls[0][2].idempotencyKey);
});

const appointment = { appointment_id: '2', previous_id: '1', patient_name: '合成患者', dentist_name: '医生', branch_name: '门店', starts_at: '2030-01-02T02:00:00Z', ends_at: '2030-01-02T02:30:00Z', visit_type: '复诊', channel: '患者端', state: '已确认', version: 1, purpose: '复诊', item_name: '检查' };
it('reschedule sends version and retains key until both returned records parse', async () => {
  const post=vi.fn().mockRejectedValue(new Error('network'));
  const api=createRealBookingApi({post} as unknown as HttpClient);
  const send=()=>api.reschedule('1','1','1','2030-01-02T10:00:00+08:00',3,'调整时间');
  await expect(send()).rejects.toThrow();
  const key=post.mock.calls[0][2].idempotencyKey;
  expect(post.mock.calls[0][1]).toMatchObject({expected_version:3,reason:'调整时间'});
  post.mockResolvedValueOnce({data:{original:{...appointment,appointment_id:'1',state:'已改约'},replacement:{}}});
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[1][2].idempotencyKey).toBe(key);
  post.mockResolvedValueOnce({data:{original:{...appointment,appointment_id:'1',state:'已改约'},replacement:appointment}});
  const result=await send();
  expect(result.replacement.appointment_id).toBe('2');
  expect(result.replacement.previous_id).toBe('1');
  expect(post.mock.calls[2][2].idempotencyKey).toBe(key);
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[3][2].idempotencyKey).not.toBe(key);
});

it('scan check-in keeps its key after unknown result and validates the returned ticket', async () => {
  const post=vi.fn().mockRejectedValue(new Error('network'));
  const api=createRealBookingApi({post} as unknown as HttpClient);
  const send=()=>api.checkin('2','signed-code',1);
  await expect(send()).rejects.toThrow();
  const key=post.mock.calls[0][2].idempotencyKey;
  expect(post.mock.calls[0][0]).toBe('/me/appointments/2/checkin');
  expect(post.mock.calls[0][1]).toEqual({branch_checkin_token:'signed-code',expected_version:1});
  post.mockResolvedValueOnce({data:{ticket_id:'9',appointment_id:'3',queue_date:'2030-01-02',queue_no:1,state:'候诊',checkin_channel:'小程序'}});
  await expect(send()).rejects.toThrow();
  expect(post.mock.calls[1][2].idempotencyKey).toBe(key);
  post.mockResolvedValueOnce({data:{ticket_id:'9',appointment_id:'2',queue_date:'2030-01-02',queue_no:1,state:'候诊',checkin_channel:'小程序'}});
  expect((await send()).queue_no).toBe(1);
  expect(post.mock.calls[2][2].idempotencyKey).toBe(key);
});

it('patient answer records consent without claiming the candidate was applied', async () => {
  const change={change_id:'8',original_id:'1',proposed_id:'9',state:'已同意',version:2,
    reason:'停诊',starts_at:'2030-01-02T02:00:00Z',ends_at:'2030-01-02T02:30:00Z',
    candidate_state:'待确认',applied:false};
  const get=vi.fn().mockResolvedValue({data:[change]});
  const post=vi.fn().mockResolvedValue({data:change});
  const api=createRealBookingApi({get,post} as unknown as HttpClient);
  expect((await api.changes())[0]?.applied).toBe(false);
  const result=await api.answerChange('8',true,1);
  expect(result.state).toBe('已同意');
  expect(result.applied).toBe(false);
  expect(post).toHaveBeenCalledWith('/me/changes/8/response',
    {accept:true,expected_version:1},{idempotencyKey:expect.any(String)});
});
