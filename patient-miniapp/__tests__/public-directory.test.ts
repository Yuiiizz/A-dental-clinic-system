import { expect, it, vi } from 'vitest';
import { createPublicDirectoryApi } from '../miniprogram/services/public-directory';
import type { HttpClient } from '../miniprogram/services/http';

it('reads public branches and doctors without patient bearer authentication', async () => {
  const list = vi.fn()
    .mockResolvedValueOnce({ items: [{ branch_id: '901', name: '【教学虚拟】一店', address: '虚拟地址', phone: '00000000001', opening_hours: '09:00–17:00', public_intro: '虚构门店介绍' }], nextCursor: null })
    .mockResolvedValueOnce({ items: [{ doctor_id: '911', name: '示例·林', job_title: '医师', public_intro: '虚构医生介绍', branches: [{ branch_id: '901', branch_name: '【教学虚拟】一店' }] }], nextCursor: null });
  const api = createPublicDirectoryApi({ list } as unknown as HttpClient);
  expect((await api.branches())[0]).toMatchObject({ branch_id: '901', name: '【示例】一店', public_intro: '虚构门店介绍' });
  expect((await api.doctors())[0]).toMatchObject({ public_intro: '虚构医生介绍', branches: [{ branch_name: '【示例】一店' }] });
  expect(list).toHaveBeenNthCalledWith(1, '/public/branches', { auth: false, query: { limit: 100 } });
  expect(list).toHaveBeenNthCalledWith(2, '/public/doctors', { auth: false, query: { limit: 100 } });
});

it('rejects malformed public directory data instead of showing incomplete facts', async () => {
  const list = vi.fn().mockResolvedValue({ items: [{ doctor_id: '911', name: '示例', branches: [] }], nextCursor: null });
  await expect(createPublicDirectoryApi({ list } as unknown as HttpClient).doctors()).rejects.toThrow('公开资料格式有误');
});
