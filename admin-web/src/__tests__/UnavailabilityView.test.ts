import { reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UnavailabilityView from '@/views/UnavailabilityView.vue';

const mock = vi.hoisted(() => ({
  api: { branches: vi.fn(), dentists: vi.fn(), doctorList: vi.fn(), resources: vi.fn(), resourceList: vi.fn(),
    createDoctor: vi.fn(), createResource: vi.fn(), resolveDoctor: vi.fn(), resolveResource: vi.fn() },
  session: { sessionRevision: 1, csrfToken: 'csrf' },
}));
const session = reactive(mock.session);
vi.mock('@/stores/session', () => ({ useSessionStore: () => session }));
vi.mock('@/services/unavailability', () => ({ createUnavailabilityApi: () => mock.api }));

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

it('旧账号登记完成后不清除新账号表单或 busy', async () => {
  vi.clearAllMocks();
  session.sessionRevision = 1;
  mock.api.branches.mockResolvedValue([{ branch_id: '1', name: '合成门店' }]);
  mock.api.dentists.mockResolvedValue([{ staff_id: '1', name: '合成医生', branch_name: '合成门店' }]);
  mock.api.doctorList.mockResolvedValue([]);
  mock.api.resources.mockResolvedValue([]);
  mock.api.resourceList.mockResolvedValue([]);
  const old = deferred();
  const current = deferred();
  mock.api.createDoctor.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
  const page = mount(UnavailabilityView);
  await flushPromises();
  await page.get('input[maxlength="300"]').setValue('旧账号停诊原因');
  await page.findAll('button').find((b) => b.text().includes('登记停诊'))!.trigger('click');
  expect(page.get('fieldset').attributes('disabled')).toBeDefined();

  session.sessionRevision++;
  await flushPromises();
  await page.get('input[maxlength="300"]').setValue('新账号停诊原因');
  await page.findAll('button').find((b) => b.text().includes('登记停诊'))!.trigger('click');
  old.resolve();
  await flushPromises();
  expect(page.get('input[maxlength="300"]').element).toHaveProperty('value', '新账号停诊原因');
  expect(page.get('fieldset').attributes('disabled')).toBeDefined();
  expect(page.text()).not.toContain('医生停诊已登记');
  current.resolve();
  await flushPromises();
  expect(page.get('fieldset').attributes('disabled')).toBeUndefined();
  page.unmount();
});
