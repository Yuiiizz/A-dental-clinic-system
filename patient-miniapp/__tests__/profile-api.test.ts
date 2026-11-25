import { expect, it, vi } from 'vitest';
import { createProfileApi } from '../miniprogram/services/profile-api';
import type { HttpClient } from '../miniprogram/services/http';

const profile = { patient_id:'1', name:'合成用户', birth_date:null, phone:'00000000001', email:null,
  emergency_name:null, emergency_phone:null, primary_dentist_id:null, allergy_status:'有',
  allergy_notes:'合成过敏说明', version:2 };
const change = { phone:'00000000001', email:'', emergency_name:'', emergency_phone:'',
  allergy_status:'有' as const, allergy_notes:'合成过敏说明', expected_version:1 };

it('reads only the confirmed profile and keeps the update key after unknown result', async () => {
  const get=vi.fn().mockResolvedValue({data:profile});
  const post=vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue({data:profile});
  const api=createProfileApi({get,post} as unknown as HttpClient);
  expect((await api.get()).name).toBe('合成用户');
  await expect(api.update(change)).rejects.toThrow('network');
  const first=post.mock.calls[0][2].idempotencyKey;
  expect((await api.update(change)).version).toBe(2);
  expect(post.mock.calls[1][2].idempotencyKey).toBe(first);
});

it('rejects a malformed profile response', async () => {
  const api=createProfileApi({get:vi.fn().mockResolvedValue({data:{...profile,version:'2'}})} as unknown as HttpClient);
  await expect(api.get()).rejects.toThrow('个人信息格式有误');
});
