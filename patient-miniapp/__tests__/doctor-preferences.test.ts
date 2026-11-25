import { expect, it, vi } from 'vitest';
import { createDoctorPreferencesApi } from '../miniprogram/services/doctor-preferences';
import type { HttpClient } from '../miniprogram/services/http';

it('loads paged preferences and sends only a source when saving', async () => {
  const list = vi.fn().mockResolvedValueOnce({ items: [{ dentist_id: '1', source: '本人选择', created_at: '2030-01-01T00:00:00Z' }], nextCursor: '1' })
    .mockResolvedValueOnce({ items: [{ dentist_id: '2', source: '熟人推荐', created_at: '2030-01-01T00:00:00Z' }], nextCursor: null });
  const put = vi.fn().mockResolvedValue({ data: undefined });
  const remove = vi.fn().mockResolvedValue({ data: undefined });
  const api = createDoctorPreferencesApi({ list, put, delete: remove } as unknown as HttpClient);
  expect((await api.list()).map((p) => p.dentist_id)).toEqual(['1', '2']);
  expect(list.mock.calls[1][1].query.cursor).toBe('1');
  await api.set('1', '既往就诊');
  expect(put).toHaveBeenCalledWith('/me/doctor-preferences/1', { source: '既往就诊' });
  await api.delete('1');
  expect(remove).toHaveBeenCalledWith('/me/doctor-preferences/1');
});

it('rejects malformed preference rows instead of showing a false saved state', async () => {
  const api = createDoctorPreferencesApi({ list: vi.fn().mockResolvedValue({ items: [{ dentist_id: '1', source: 'unverified', created_at: '' }], nextCursor: null }) } as unknown as HttpClient);
  await expect(api.list()).rejects.toThrow('收藏资料格式有误');
});
