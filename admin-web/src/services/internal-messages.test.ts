import { expect, it, vi } from 'vitest';
import { createInternalMessagesApi } from './internal-messages';
import type { HttpClient } from './http';

it('loads contacts and a branch-scoped conversation', async () => {
  const get = vi.fn().mockResolvedValue({ data: [{ branch_id: '1', branch_name: '一店', peer_staff_id: '2', peer_name: '医生', actor_role: 'frontdesk' }] });
  const list = vi.fn().mockResolvedValue({ items: [{ message_id: '7', sender_account_id: '1', body: '排班已确认', sent_at: '2030-01-01T00:00:00Z' }], nextCursor: null });
  const api = createInternalMessagesApi({ get, list } as unknown as HttpClient);
  expect((await api.contacts())[0].peer_name).toBe('医生');
  expect((await api.messages('1', '2')).items[0].body).toBe('排班已确认');
  expect(list).toHaveBeenCalledWith('/staff/internal-messages', { query: { branch_id: '1', peer_staff_id: '2', cursor: undefined, limit: 50 } });
});

it('retains the same idempotency key after an unknown send result', async () => {
  const post = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ data: {
    message_id: '7', sender_account_id: '1', body: '请核对预约 123', sent_at: '2030-01-01T00:00:00Z',
  } });
  const api = createInternalMessagesApi({ post } as unknown as HttpClient);
  await expect(api.send('1', '2', '请核对预约 123')).rejects.toThrow();
  await api.send('1', '2', '请核对预约 123');
  expect(post.mock.calls[1][2].idempotencyKey).toBe(post.mock.calls[0][2].idempotencyKey);
});
