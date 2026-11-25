import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import CheckinConfigPanel from '@/components/CheckinConfigPanel.vue';

const mock = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  session: { csrfToken: 'csrf', sessionRevision: 1 },
}));
vi.mock('@/services/http', () => ({
  createHttpClient: () => ({ get: mock.get, put: mock.put }),
  ApiError: class ApiError extends Error {},
}));
vi.mock('@/stores/session', () => ({ useSessionStore: () => mock.session }));

beforeEach(() => {
  vi.clearAllMocks();
  mock.get.mockResolvedValue({ data: {
    configured: true,
    self_checkin_enabled: false,
    early_minutes: null,
    late_minutes: null,
    slot_step_minutes: 30,
    version: 1,
  } });
  mock.put.mockResolvedValue({ data: {
    configured: true,
    self_checkin_enabled: true,
    early_minutes: 30,
    late_minutes: 30,
    slot_step_minutes: 30,
    version: 2,
  } });
});

it('saves numeric window inputs without calling trim on numbers', async () => {
  const wrapper = mount(CheckinConfigPanel, { props: { branchId: '1' } });
  await flushPromises();
  await wrapper.find('input[type="checkbox"]').setValue(true);
  const numbers = wrapper.findAll('input[type="number"]');
  await numbers[0].setValue('30');
  await numbers[1].setValue('30');
  await numbers[2].setValue('30');
  await wrapper.find('form').trigger('submit');
  await flushPromises();
  expect(mock.put).toHaveBeenCalledWith(
    '/staff/branches/1/booking-config',
    expect.objectContaining({ self_checkin_enabled: true, early_minutes: 30, late_minutes: 30, slot_step_minutes: 30 }),
    expect.any(Object),
  );
  expect(wrapper.text()).toContain('签到设置已保存');
  wrapper.unmount();
});
