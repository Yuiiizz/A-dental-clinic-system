import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import InternalMessagesView from '@/views/InternalMessagesView.vue';

const mock = vi.hoisted(() => ({
  contacts: vi.fn(), messages: vi.fn(), send: vi.fn(), clearPending: vi.fn(),
}));
vi.mock('@/services/internal-messages', () => ({ createInternalMessagesApi: () => mock }));
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({
  me: { account_id: '2' }, csrfToken: 'test-csrf', sessionRevision: 0, status: 'ready',
}) }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
const contacts = [
  { branch_id: '1', branch_name: '一店', peer_staff_id: '11', peer_name: '林医生', actor_role: 'frontdesk' },
  { branch_id: '1', branch_name: '一店', peer_staff_id: '12', peer_name: '周医生', actor_role: 'frontdesk' },
];
const empty = { items: [], nextCursor: null };

afterEach(() => vi.clearAllMocks());

it('does not let an old send lock the newly selected conversation', async () => {
  const oldSend = deferred<{ message_id: string }>();
  mock.contacts.mockResolvedValue(contacts);
  mock.messages.mockResolvedValue(empty);
  mock.send.mockReturnValueOnce(oldSend.promise).mockResolvedValueOnce({ message_id: '2' });
  const page = mount(InternalMessagesView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  expect(page.text()).toContain('林医生');
  await page.get('textarea').setValue('请核对预约 123');
  await page.get('form').trigger('submit');
  expect(page.get('button[type="submit"]').attributes('disabled')).toBeDefined();
  await page.findAll('button.contact')[1].trigger('click');
  await flushPromises();
  await page.get('textarea').setValue('请核对预约 456');
  expect(page.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
  oldSend.resolve({ message_id: '1' });
  await flushPromises();
  expect(page.get('textarea').element.value).toBe('请核对预约 456');
  expect(page.text()).not.toContain('消息已保存');
  await page.get('form').trigger('submit');
  await flushPromises();
  expect(mock.send).toHaveBeenNthCalledWith(2, '1', '12', '请核对预约 456');
  page.unmount();
});
