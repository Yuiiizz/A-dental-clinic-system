import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import FrontdeskScheduleView from '@/views/FrontdeskScheduleView.vue';

const mock = vi.hoisted(() => ({
  overview: vi.fn(),
  session: { sessionRevision: 1, csrfToken: 'csrf' },
}));
vi.mock('@/stores/session', () => ({ useSessionStore: () => mock.session }));
vi.mock('@/services/appointments', () => ({ createAppointmentApi: () => ({ overview: mock.overview }) }));

function shanghaiDay(offset: number): string {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(Date.now() + offset * 86400000)).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

it('shows the third week and its doctor and assistant without extending the booking window', async () => {
  const day = shanghaiDay(14);
  mock.overview.mockResolvedValue({
    branches: [{ branch_id: '1', name: '甲店' }], patient_count: 0, patients: [],
    services: [], availability: [], appointments: [], policies: [],
    schedules: [{ schedule_id: 's1', branch_id: '1', branch_name: '甲店',
      dentist_id: 'd1', dentist_name: '林医生',
      starts_at: `${day}T01:00:00Z`, ends_at: `${day}T04:00:00Z` }],
    assistant_shifts: [{ shift_id: 'a1', branch_id: '1', staff_id: 'h1', name: '小禾',
      job_title: '诊疗助手', starts_at: `${day}T01:00:00Z`, ends_at: `${day}T04:00:00Z` }],
  });
  const page = mount(FrontdeskScheduleView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
  await flushPromises();
  const next = () => page.findAll('button').find((button) => button.text().includes('后七天'))!;
  await next().trigger('click');
  await next().trigger('click');
  expect(page.text()).toContain(day);
  expect(page.text()).toContain('林医生');
  expect(page.text()).toContain('小禾');
  expect(page.get('input[type="date"]').attributes('max')).toBe(shanghaiDay(20));
  expect(next().attributes('disabled')).toBeDefined();
  expect(page.text()).toContain('预约窗口目前为 7 天');
  page.unmount();
});
