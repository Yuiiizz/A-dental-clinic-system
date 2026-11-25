import { ApiError, type HttpClient, type PageResult } from './http';

export interface InternalContact {
  branch_id: string;
  branch_name: string;
  peer_staff_id: string;
  peer_name: string;
  actor_role: 'frontdesk' | 'dentist';
}
export interface InternalMessage {
  message_id: string;
  sender_account_id: string;
  body: string;
  sent_at: string;
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new ApiError('INVALID_RESPONSE', 0, '内部消息数据无法解析。');
  return value as Record<string, unknown>;
}
function str(value: unknown): string {
  if (typeof value !== 'string') throw new ApiError('INVALID_RESPONSE', 0, '内部消息数据无法解析。');
  return value;
}
function contact(value: unknown): InternalContact {
  const row = record(value);
  const role = str(row['actor_role']);
  if (role !== 'frontdesk' && role !== 'dentist') throw new ApiError('INVALID_RESPONSE', 0, '内部消息数据无法解析。');
  return { branch_id: str(row['branch_id']), branch_name: str(row['branch_name']),
    peer_staff_id: str(row['peer_staff_id']), peer_name: str(row['peer_name']), actor_role: role };
}
function message(value: unknown): InternalMessage {
  const row = record(value);
  return { message_id: str(row['message_id']), sender_account_id: str(row['sender_account_id']),
    body: str(row['body']), sent_at: str(row['sent_at']) };
}

export function createInternalMessagesApi(client: HttpClient) {
  const pending = new Map<string, string>();
  return {
    async contacts(): Promise<InternalContact[]> {
      const result = await client.get<unknown>('/staff/internal-messages/contacts');
      if (!Array.isArray(result.data)) throw new ApiError('INVALID_RESPONSE', 0, '内部消息数据无法解析。');
      return result.data.map(contact);
    },
    async messages(branchId: string, peerStaffId: string, cursor?: string): Promise<PageResult<InternalMessage>> {
      const result = await client.list<unknown>('/staff/internal-messages', {
        query: { branch_id: branchId, peer_staff_id: peerStaffId, cursor, limit: 50 },
      });
      return { items: result.items.map(message), nextCursor: result.nextCursor };
    },
    async send(branchId: string, peerStaffId: string, body: string): Promise<InternalMessage> {
      const content = body.trim();
      if (!content || content.length > 500) throw new ApiError('VALIDATION_FAILED', 422, '请输入 1–500 字的消息。');
      const fingerprint = JSON.stringify([branchId, peerStaffId, content]);
      let key = pending.get(fingerprint);
      if (!key) {
        key = crypto.randomUUID();
        pending.set(fingerprint, key);
      }
      const result = await client.post<unknown>('/staff/internal-messages',
        { branch_id: branchId, peer_staff_id: peerStaffId, body: content }, { idempotencyKey: key });
      const sent = message(result.data);
      pending.delete(fingerprint);
      return sent;
    },
    clearPending() { pending.clear(); },
  };
}
