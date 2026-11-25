import { describe, expect, it, vi } from 'vitest';
import { ApiError, type HttpClient } from '@/services/http';
import { createSuppliersApi } from '@/services/suppliers';

const supplier = {
  supplier_id: '901', name: '【教学虚拟】器材供应商', credit_code: 'DEMO-SUP-901',
  valid_to: '2031-12-31', enabled: true, verified_at: '2026-11-09T00:00:00Z',
  qualification_ref: '教学虚拟核验依据', verified_by: '901',
};

describe('供应商真实接口封装', () => {
  it('读取顶层分页并将原幂等键交给 HTTP 客户端', async () => {
    const list = vi.fn().mockResolvedValue({ items: [supplier], nextCursor: '901' });
    const post = vi.fn().mockResolvedValue({ data: supplier });
    const api = createSuppliersApi({ list, post } as unknown as HttpClient);
    expect((await api.list()).nextCursor).toBe('901');
    const input = { name: supplier.name, credit_code: supplier.credit_code,
      qualification_ref: supplier.qualification_ref, valid_to: supplier.valid_to };
    expect((await api.create(input, 'stable-key')).supplier_id).toBe('901');
    expect(post).toHaveBeenCalledWith('/staff/suppliers', input, { idempotencyKey: 'stable-key' });
  });

  it('拒绝畸形列表，不把缺失字段展示为真实资料', async () => {
    const list = vi.fn().mockResolvedValue({ items: [{ ...supplier, enabled: 'true' }], nextCursor: null });
    const api = createSuppliersApi({ list } as unknown as HttpClient);
    await expect(api.list()).rejects.toBeInstanceOf(ApiError);
  });
});
