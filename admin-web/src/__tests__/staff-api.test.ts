import { describe, expect, it, vi } from 'vitest';
import { ApiError, createHttpClient, type HttpClient } from '@/services/http';
import { createStaffApi, parseStaffDetailForTest } from '@/services/staff';

const validDetail = {
  staff_id: '5',
  name: '合成员工',
  staff_type: '其他人员',
  job_title: '员工',
  practice_no: null,
  employment_state: '在岗',
  version: 1,
  branches: [],
  credentials: [{
    credential_id: '9',
    credential_type: 'other',
    verification_state: 'verified',
    valid_from: '2026-01-01',
    valid_to: null,
    no_expiry_confirmed: true,
    verified_at: null,
    version: 2,
    effective: true,
  }],
};

function client(overrides: Partial<HttpClient> = {}): HttpClient {
  return {
    request: vi.fn(),
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    list: vi.fn(),
    ...overrides,
  } as unknown as HttpClient;
}

describe('员工 API 契约解析', () => {
  it('解析可空执业编号和资格摘要，不要求证书原文', () => {
    const d = parseStaffDetailForTest(validDetail);
    expect(d.practice_no).toBeNull();
    expect(d.credentials[0].version).toBe(2);
    expect(d.credentials[0].effective).toBe(true);
  });

  it('拒绝缺少字段或类型错误的详情', () => {
    expect(() => parseStaffDetailForTest({ ...validDetail, staff_id: undefined })).toThrow(ApiError);
    expect(() => parseStaffDetailForTest({ ...validDetail, staff_type: '医生' })).toThrow(ApiError);
    expect(() => parseStaffDetailForTest({ ...validDetail, credentials: [{ ...validDetail.credentials[0], version: 0 }] })).toThrow(ApiError);
  });

  it('list 解析真实分页，不把 next_cursor 当 data', async () => {
    const list = vi.fn().mockResolvedValue({
      items: [{ staff_id: '1', name: '甲', staff_type: '牙医', job_title: '牙医', employment_state: '在岗', version: 1 }],
      nextCursor: '1',
    });
    const api = createStaffApi(client({ list }));
    const page = await api.list();
    expect(page.items[0].staff_id).toBe('1');
    expect(page.nextCursor).toBe('1');
  });

  it('建档不发送空 credential，并在启用时发送规范化字段', async () => {
    const post = vi.fn().mockResolvedValue({ data: validDetail });
    const api = createStaffApi(client({ post }));
    await api.create({ name: ' 甲 ', staff_type: '其他人员', job_title: ' 员工 ' }, 'key-1');
    expect(post.mock.calls[0][1]).toEqual({ name: '甲', staff_type: '其他人员', job_title: '员工' });
    await api.create({
      name: '乙', staff_type: '牙医', job_title: '牙医', practice_no: 'P-1',
      credential: { credential_type: 'dentist_practice', certificate_no: 'C-1', valid_from: '2026-01-01', no_expiry_confirmed: false },
    }, 'key-2');
    expect(post.mock.calls[1][1]).toMatchObject({
      practice_no: 'P-1',
      credential: { credential_type: 'dentist_practice', certificate_no: 'C-1', valid_from: '2026-01-01', no_expiry_confirmed: false },
    });
    expect(post.mock.calls[1][1]).not.toHaveProperty('valid_to');
  });

  it('拒绝非法日期和无期限与截止日期冲突，不调用网络', async () => {
    const post = vi.fn();
    const api = createStaffApi(client({ post }));
    await expect(api.create({
      name: '乙', staff_type: '牙医', job_title: '牙医', practice_no: 'P-1',
      credential: { credential_type: 'dentist_practice', certificate_no: 'C-1', valid_from: '2026-02-30', no_expiry_confirmed: false },
    }, 'key')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(api.create({
      name: '乙', staff_type: '牙医', job_title: '牙医', practice_no: 'P-1',
      credential: { credential_type: 'dentist_practice', certificate_no: 'C-1', valid_from: '2026-13-01', no_expiry_confirmed: false },
    }, 'key')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(api.create({
      name: '乙', staff_type: '牙医', job_title: '牙医', practice_no: 'P-1',
      credential: { credential_type: 'dentist_practice', certificate_no: 'C-1', valid_to: '2030-01-01', no_expiry_confirmed: true },
    }, 'key')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    expect(post).not.toHaveBeenCalled();
  });

  it('真实 HTTP 客户端畸形201保留 INVALID_RESPONSE 与 status=201', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 201,
      ok: true,
      headers: new Headers(),
      text: async () => JSON.stringify({ data: { staff_id: '1' } }),
    });
    vi.stubGlobal('fetch', fetchMock);
    try {
      const api = createStaffApi(createHttpClient({ baseUrl: '/api/v1' }));
      await expect(api.create({ name: '乙', staff_type: '其他人员', job_title: '员工' }, 'key')).rejects
        .toMatchObject({ code: 'INVALID_RESPONSE', status: 201 });
      expect(fetchMock).toHaveBeenCalledTimes(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
