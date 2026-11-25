import { reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import AppointmentWorkbenchView from '@/views/AppointmentWorkbenchView.vue';
import { ApiError } from '@/services/http';

const mock = vi.hoisted(() => ({
  api: { overview: vi.fn(), searchPatient: vi.fn(), selectPatient: vi.fn(), createPatient: vi.fn(),
    create: vi.fn(), checkin: vi.fn(), queue: vi.fn(), changes: vi.fn(), contactTodos: vi.fn(), receptionSummary: vi.fn() },
  session: { sessionRevision: 1, csrfToken: 'csrf' },
}));
const session = reactive(mock.session);
vi.mock('@/stores/session', () => ({ useSessionStore: () => session }));
vi.mock('@/services/appointments', () => ({ createAppointmentApi: () => mock.api }));

const overview = {
  branches: [{ branch_id: '1', name: '教学门店' }], patients: [],
  schedules: [{ schedule_id: '1', branch_id: '1', branch_name: '教学门店',
    dentist_id: '1', dentist_name: '示例医生', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T03:00:00Z' }],
  services: [{ item_id: '1', name: '初诊检查', minutes: 30, price: '80.00' }],
  availability: [{ schedule_id: '1', item_id: '1', slots: ['2030-01-02T01:00:00Z'] }],
  policies: [], appointments: [],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function button(page: ReturnType<typeof mount>, label: string) {
  const found = page.findAll('button').find((item) => item.text().includes(label));
  if (!found) throw new Error(`Missing button: ${label}`);
  return found;
}
beforeEach(() => {
  vi.clearAllMocks();
  session.sessionRevision = 1;
  mock.api.overview.mockResolvedValue(overview);
  mock.api.queue.mockResolvedValue([]);
  mock.api.changes.mockResolvedValue([]);
  mock.api.contactTodos.mockResolvedValue({ items: [], nextCursor: null });
});

it('maps an unconfigured check-in window to an actionable reason prompt', async () => {
  const today = new Date().toISOString();
  mock.api.overview.mockResolvedValueOnce({
    ...overview,
    appointments: [{
      appointment_id: 'a1', patient_id: '1', patient_name: '虚构患者', dentist_name: '示例医生',
      branch_name: '教学门店', starts_at: today, ends_at: today, state: '已确认', version: 1,
      item_name: '初诊检查', channel: '电话', purpose: '测试', followup_plan_id: null,
    }],
  });
  mock.api.checkin.mockRejectedValueOnce(new ApiError('BUSINESS_BASIS_REQUIRED', 422, '请提供可核验的线下办理依据'));
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  await button(page, '签到').trigger('click');
  await page.get('input[placeholder="例如：现场核对就诊凭据"]').setValue('虚构现场核对就诊凭据');
  await button(page, '确认签到并领取候诊号').trigger('click');
  await flushPromises();
  expect(page.text()).toContain('未配置签到窗口或预约已超窗');
  page.unmount();
});

it('opens one appointment contact and visit summary without showing clinical notes', async () => {
  mock.api.overview.mockResolvedValueOnce({ ...overview, patient_count: 1, appointments: [{
    appointment_id: '9', patient_id: '3', patient_name: '合成患者', schedule_id: '1', dentist_name: '示例医生',
    branch_id: '1', branch_name: '合成门店', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T01:30:00Z',
    visit_type: '初诊', state: '已确认', version: 1, item_name: '初诊检查', channel: '电话', purpose: '预约',
  }] });
  mock.api.receptionSummary.mockResolvedValueOnce({ patient_id: '3', name: '合成患者', phone: '00000000003',
    appointments: [{ appointment_id: '9', starts_at: '2030-01-02T01:00:00Z', state: '已确认',
      visit_type: '初诊', dentist_name: '示例医生', item_name: '初诊检查' }], visits: [] });
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  expect(page.text()).not.toContain('00000000003');
  await button(page, '接待资料').trigger('click');
  await flushPromises();
  expect(mock.api.receptionSummary).toHaveBeenCalledWith('1', '3');
  expect(page.text()).toContain('00000000003');
  expect(page.text()).toContain('暂无本店已完成就诊记录');
  await button(page, '关闭资料').trigger('click');
  expect(page.text()).not.toContain('00000000003');
  page.unmount();
});

it('limits replacement options to on-duty doctors with slots for the original service', async () => {
  mock.api.overview.mockResolvedValueOnce({ ...overview,
    schedules: [
      { ...overview.schedules[0], schedule_id: '1', dentist_name: '原医生' },
      { ...overview.schedules[0], schedule_id: '2', dentist_name: '对口医生' },
      { ...overview.schedules[0], schedule_id: '3', dentist_name: '其他项目医生' },
    ],
    availability: [
      { schedule_id: '2', item_id: '1', slots: ['2030-01-02T01:30:00Z'] },
      { schedule_id: '3', item_id: '2', slots: ['2030-01-02T01:30:00Z'] },
    ],
    appointments: [{ appointment_id: '9', patient_id: '3', patient_name: '合成患者',
      branch_id: '1', branch_name: '教学门店', schedule_id: '1', dentist_name: '原医生',
      starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T01:30:00Z',
      visit_type: '初诊', state: '已确认', version: 1, item_name: '初诊检查', item_id: '1',
      channel: '现场', purpose: '初诊检查', unavailability_alert: true }],
  });
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  await button(page, '拟协调方案').trigger('click');
  const options = page.findAll('label').find(label => label.text().includes('候选排班'))?.text() ?? '';
  expect(options).toContain('对口医生');
  expect(options).not.toContain('其他项目医生');
  expect(page.text()).toContain('原预约项目：初诊检查');
  page.unmount();
});

it('clears the previous account overview and ignores its late response', async () => {
  const late = deferred<typeof overview>();
  mock.api.overview.mockReturnValueOnce(late.promise);
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  session.sessionRevision++;
  await flushPromises();
  late.resolve(overview);
  await flushPromises();
  expect(page.text()).not.toContain('教学门店');
  page.unmount();
});

it('does not display a patient candidate returned after an account switch', async () => {
  const late = deferred<{ matches: Array<{ patient_id: string; display_name: string; phone_suffix: string; birth_year: null; match_type: string }>; requires_manual_verification: boolean; can_create: boolean; reason: null }>();
  mock.api.searchPatient.mockReturnValue(late.promise);
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  await page.get('input[maxlength="60"]').setValue('甲用户');
  await page.get('input[maxlength="30"]').setValue('13800000000');
  await button(page, '精确检索档案').trigger('click');
  session.sessionRevision++;
  await flushPromises();
  late.resolve({ matches: [{ patient_id: '77', display_name: '甲用户', phone_suffix: '0000', birth_year: null, match_type: 'exact_name_phone' }],
    requires_manual_verification: false, can_create: false, reason: null });
  await flushPromises();
  expect(page.text()).not.toContain('甲用户 · 尾号');
  expect(page.text()).not.toContain('找到 1 个候选');
  page.unmount();
});

it('does not attach a late selection token to a new account', async () => {
  const late = deferred<{ selection_token: string; expires_at: string }>();
  mock.api.searchPatient.mockResolvedValue({ matches: [{ patient_id: '77', display_name: '甲用户', phone_suffix: '0000', birth_year: null, match_type: 'exact_name_phone' }],
    requires_manual_verification: false, can_create: false, reason: null });
  mock.api.selectPatient.mockReturnValue(late.promise);
  const page = mount(AppointmentWorkbenchView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
  await flushPromises();
  await page.get('input[maxlength="60"]').setValue('甲用户');
  await page.get('input[maxlength="30"]').setValue('13800000000');
  await button(page, '精确检索档案').trigger('click');
  await flushPromises();
  await page.get('input[type="checkbox"]').setValue(true);
  await button(page, '核验并选择').trigger('click');
  session.sessionRevision++;
  await flushPromises();
  late.resolve({ selection_token: 'private-token', expires_at: '2030-01-02T00:00:00Z' });
  await flushPromises();
  expect(page.text()).not.toContain('已选择：');
  expect(page.text()).not.toContain('线下核验已记录');
  page.unmount();
});
