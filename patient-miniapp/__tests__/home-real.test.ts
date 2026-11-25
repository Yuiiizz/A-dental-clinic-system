import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  hasSession: true,
  revision: 1,
  demo: false,
  overview: vi.fn(),
  changes: vi.fn(),
  branches: vi.fn(),
  doctors: vi.fn(),
}));
vi.mock('../miniprogram/services/auth-flow', () => ({
  authFlow: { get hasSession() { return state.hasSession; }, get sessionRevision() { return state.revision; } },
}));
vi.mock('../miniprogram/services/session', () => ({
  patientSession: { get revision() { return state.revision; } },
}));
vi.mock('../miniprogram/services/booking-api', () => ({
  createRealBookingApi: () => ({ overview: state.overview, changes: state.changes }),
}));
vi.mock('../miniprogram/services/public-directory', () => ({
  createPublicDirectoryApi: () => ({ branches: state.branches, doctors: state.doctors }),
}));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}
async function home(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { getStorageSync: () => state.demo ? '1' : '0', setStorageSync: vi.fn(), navigateTo: vi.fn(), showToast: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/home/home');
  if (!config) throw new Error('home page missing');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch: Record<string, unknown>) { Object.assign(this.data, patch); },
  };
  for (const [key, value] of Object.entries(source)) if (typeof value === 'function') page[key] = value.bind(page);
  return page;
}
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}
const branches = [{ branch_id: '1', name: '真实门店', address: '真实地址', phone: '000', opening_hours: '09:00-18:00' }];
const doctors = [{ doctor_id: '1', name: '真实医生', job_title: '主治医师', branches: [{ branch_id: '1', branch_name: '真实门店' }] }];
const overview = {
  schedules: [], services: [], availability: [],
  appointments: [
    { appointment_id: '2', patient_name: '本人', dentist_name: '真实医生', branch_name: '真实门店', starts_at: '2030-01-02T02:00:00Z', ends_at: '2030-01-02T02:30:00Z', visit_type: '复诊', channel: '患者端', state: '已确认', version: 1, purpose: '复查', item_name: '检查' },
    { appointment_id: '1', patient_name: '本人', dentist_name: '真实医生', branch_name: '真实门店', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T01:30:00Z', visit_type: '初诊', channel: '患者端', state: '已完成', version: 1, purpose: '检查', item_name: '检查' },
  ],
};

describe('首页真实数据与演示隔离', () => {
  beforeEach(() => {
    state.hasSession = true;
    state.revision = 1;
    state.demo = false;
    state.overview.mockReset().mockResolvedValue(overview);
    state.changes.mockReset().mockResolvedValue([]);
    state.branches.mockReset().mockResolvedValue(branches);
    state.doctors.mockReset().mockResolvedValue(doctors);
  });

  it('登录后读取真实门店、医生和下一次有效预约，并隐藏演示入口', async () => {
    const page = await home();
    page.onShow();
    await flush();
    expect(page.data.showDemo).toBe(false);
    expect((page.data.publicBranches as Array<{ name: string }>)[0].name).toBe('真实门店');
    expect((page.data.publicDoctors as Array<{ name: string }>)[0].name).toBe('真实医生');
    expect(page.data.realNext).toMatchObject({ id: '2', branch: '真实门店', dentist: '真实医生', state: '已确认' });
    expect(page.data.realUpcomingCount).toBe(1);
  });

  it('换账号后旧首页真实摘要不能覆盖新账号结果', async () => {
    let resolveOld!: (value: typeof overview) => void;
    state.overview.mockImplementationOnce(() => new Promise<typeof overview>((done) => { resolveOld = done; }));
    const nextOverview = { ...overview, appointments: [{
      ...overview.appointments[0], appointment_id: '9', starts_at: '2030-01-02T03:00:00Z',
      ends_at: '2030-01-02T03:30:00Z', item_name: '新账号预约',
    }] };
    state.overview.mockResolvedValueOnce(nextOverview);
    const page = await home();
    page.onShow();
    state.revision = 2;
    page.onShow();
    await flush();
    expect(page.data.realNext).toMatchObject({ id: '9' });
    resolveOld(overview);
    await flush();
    expect(page.data.realNext).toMatchObject({ id: '9' });
    expect(page.data.realUpcomingCount).toBe(1);
  });

  it('演示模式即使有登录态也不请求真实接口', async () => {
    state.demo = true;
    const page = await home();
    page.onShow();
    await flush();
    expect(page.data.showDemo).toBe(true);
    expect(page.data.realNext).toBeNull();
    expect(state.overview).not.toHaveBeenCalled();
    expect(state.branches).not.toHaveBeenCalled();
  });

  it('不把已过时但状态仍有效的预约显示为下一次就诊', async () => {
    state.overview.mockResolvedValueOnce({ ...overview, appointments: [{
      ...overview.appointments[0], starts_at: '2020-01-02T02:00:00Z', ends_at: '2020-01-02T02:30:00Z',
    }] });
    const page = await home();
    page.onShow();
    await flush();
    expect(page.data.realNext).toBeNull();
    expect(page.data.realUpcomingCount).toBe(0);
  });

  it('未登录时只展示明确的演示样例，不展示真实预约摘要', async () => {
    state.hasSession = false;
    state.demo = true;
    state.branches.mockRejectedValue(new Error('no api'));
    state.doctors.mockRejectedValue(new Error('no api'));
    const page = await home();
    page.onShow();
    await flush();
    expect(page.data.showDemo).toBe(true);
    expect(page.data.realNext).toBeNull();
    expect(page.data.hasPatientSession).toBe(false);
  });
});
