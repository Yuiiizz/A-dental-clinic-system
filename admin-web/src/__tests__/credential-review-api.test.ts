import { describe, expect, it, vi } from 'vitest';
import { createCredentialReviewApi, type CredentialFacts } from '@/services/credential-review';
import type { HttpClient } from '@/services/http';

const facts: CredentialFacts = {
  credential_id: '9', staff_id: '5', credential_type: 'dentist_practice',
  certificate_no: 'PRIVATE-123', valid_from: '2026-01-01', valid_to: '2030-01-01',
  no_expiry_confirmed: false, verification_state: 'pending', version: 1,
};

function client(get = vi.fn(), post = vi.fn()): HttpClient {
  return { get, post } as unknown as HttpClient;
}

describe('资格证件事实与审核 API', () => {
  it('追加待审核资格使用指定员工、幂等键，拒绝错配响应', async () => {
    const post = vi.fn().mockResolvedValue({ data: { staff_id: '5', credential_id: '12' }, status: 201 });
    const api = createCredentialReviewApi(client(undefined, post));
    const input = { credential_type: 'dentist_practice', certificate_no: ' PRIVATE-2 ',
      valid_from: '2026-01-01', no_expiry_confirmed: false };
    expect(await api.add('5', input, 'key-1')).toBe('12');
    expect(post).toHaveBeenCalledWith('/staff/staff-members/5/credentials', {
      credential_type: 'dentist_practice', certificate_no: 'PRIVATE-2',
      valid_from: '2026-01-01', no_expiry_confirmed: false,
    }, { idempotencyKey: 'key-1' });
    post.mockResolvedValueOnce({ data: { staff_id: '6', credential_id: '12' }, status: 201 });
    await expect(api.add('5', input, 'key-1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('仅解析指定员工和资格的完整事实，拒绝错配或畸形响应', async () => {
    const get = vi.fn().mockResolvedValue({ data: facts });
    const api = createCredentialReviewApi(client(get));
    expect(await api.facts('5', '9')).toEqual(facts);
    expect(get).toHaveBeenCalledWith('/staff/staff-members/5/credentials/9/facts');
    await expect(api.facts('6', '9')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    get.mockResolvedValueOnce({ data: { ...facts, certificate_no: null } });
    await expect(api.facts('5', '9')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('首次审核与撤销发送当前事实版本和幂等键', async () => {
    const post = vi.fn().mockResolvedValue({ data: {} });
    const api = createCredentialReviewApi(client(undefined, post));
    await api.decide(facts, 'verified', ' 原件核对 ', 'same-key');
    expect(post).toHaveBeenCalledWith('/staff/staff-members/5/credentials/9/review',
      { decision: 'verified', basis: '原件核对', expected_version: 1 },
      { idempotencyKey: 'same-key' });
    await api.decide({ ...facts, verification_state: 'verified', version: 2 }, 'revoke', '资格撤销依据', 'next-key');
    expect(post).toHaveBeenLastCalledWith('/staff/staff-members/5/credentials/9/revoke',
      { basis: '资格撤销依据', expected_version: 2 }, { idempotencyKey: 'next-key' });
  });

  it('本地拒绝空依据和错误状态，不发起请求', async () => {
    const post = vi.fn();
    const api = createCredentialReviewApi(client(undefined, post));
    await expect(api.decide(facts, 'verified', ' ', 'key')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(api.decide(facts, 'revoke', '依据', 'key')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(api.decide({ ...facts, verification_state: 'verified' }, 'rejected', '依据', 'key'))
      .rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    expect(post).not.toHaveBeenCalled();
  });

  it('审核历史按游标读取，并拒绝混入其他资格或畸形历史', async () => {
    const item = { review_id: '12', credential_id: '9', action: 'revoke', from_state: 'verified',
      to_state: 'rejected', actor_staff_id: '5', basis: '核对后撤销',
      occurred_at: '2026-11-20T03:00:00Z', resulting_version: 3 };
    const get = vi.fn().mockResolvedValue({ data: { items: [item], next_cursor: '12' } });
    const api = createCredentialReviewApi(client(get));
    expect((await api.reviews('5', '9')).items[0]).toEqual(item);
    expect(get).toHaveBeenCalledWith('/staff/staff-members/5/credentials/9/reviews?limit=20');
    await api.reviews('5', '9', '12');
    expect(get).toHaveBeenLastCalledWith('/staff/staff-members/5/credentials/9/reviews?limit=20&cursor=12');
    get.mockResolvedValueOnce({ data: { items: [{ ...item, credential_id: '10' }], next_cursor: null } });
    await expect(api.reviews('5', '9')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    await expect(api.reviews('5', '9', 'bad')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  });
});
