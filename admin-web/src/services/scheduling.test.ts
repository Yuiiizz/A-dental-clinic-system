import { expect, it, vi } from 'vitest';
import { createSchedulingApi } from './scheduling';
import type { HttpClient } from './http';

it('sends the complete doctor scope and an explicit review basis', async () => {
  const put = vi.fn().mockResolvedValue({ data: {} });
  const api = createSchedulingApi({ put } as unknown as HttpClient);
  await api.setDoctorServices('911', ['901', '904'], '已核对执业范围与项目资料');
  expect(put).toHaveBeenCalledWith('/staff/scheduling/staff/911/services', {
    item_ids: ['901', '904'], approval_basis: '已核对执业范围与项目资料',
  });
});
