import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  hasSession: true,
  revision: 1,
  doctors: vi.fn(),
  branches: vi.fn(),
  overview: vi.fn(),
  preferences: vi.fn(),
}));
vi.mock('../miniprogram/services/auth-flow', () => ({
  authFlow: { get hasSession() { return state.hasSession; }, get sessionRevision() { return state.revision; } },
}));
vi.mock('../miniprogram/services/session', () => ({
  patientSession: { get revision() { return state.revision; } },
}));
vi.mock('../miniprogram/services/public-directory', () => ({
  createPublicDirectoryApi: () => ({ branches: state.branches, doctors: state.doctors }),
}));
vi.mock('../miniprogram/services/booking-api', () => ({
  createRealBookingApi: () => ({ overview: state.overview }),
}));
vi.mock('../miniprogram/services/doctor-preferences', () => ({
  createDoctorPreferencesApi: () => ({ list: state.preferences, set: vi.fn(), delete: vi.fn() }),
}));
vi.mock('../miniprogram/demo/store', () => ({ isDemoEnabled: () => false, setDemoEnabled: vi.fn() }));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}
async function doctorsPage(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { getStorageSync: () => '1', navigateTo: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/doctors/doctors');
  if (!config) throw new Error('doctors page missing');
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

describe('医生页跨账号资料隔离', () => {
  beforeEach(() => {
    state.hasSession = true;
    state.revision = 1;
    state.branches.mockReset().mockResolvedValue([]);
    state.doctors.mockReset().mockResolvedValue([{ doctor_id: '1', name: '真实医生', job_title: '主治医师', branches: [] }]);
    state.overview.mockReset().mockResolvedValue({ schedules: [], services: [], availability: [], appointments: [] });
    state.preferences.mockReset().mockResolvedValue([]);
  });

  it('账号代次变化时先清空收藏和旧排班，再读取新账号数据', async () => {
    const page = await doctorsPage();
    page.data.identityRevision = 1;
    page.data.hasPatientSession = true;
    page.data.favoriteSources = { '1': '本人选择' };
    page.data.publicDoctors = [{ doctor_id: '1', name: '真实医生', job_title: '主治医师', branches: [], favoriteSource: '本人选择' }];
    page.data.scheduleServices = [{ id: '1', name: '旧项目', days: [] }];
    page.data.selectedServiceId = '1';
    page.data.scheduleDays = [{ date: '2026-11-05', morning: [] }];
    state.revision = 2;
    page.onShow();
    expect(page.data.favoriteSources).toEqual({});
    expect(page.data.scheduleServices).toEqual([]);
    expect(page.data.scheduleDays).toEqual([]);
    expect((page.data.publicDoctors as Array<{ favoriteSource: string }>)[0].favoriteSource).toBe('');
    await flush();
    expect(page.data.hasPatientSession).toBe(true);
    expect(page.data.showDemo).toBe(false);
  });

  it('从门店进入时只显示该店医生和项目排班', async () => {
    state.branches.mockResolvedValue([
      { branch_id: '1', name: '合成一店' }, { branch_id: '2', name: '合成二店' },
    ]);
    state.doctors.mockResolvedValue([
      { doctor_id: '11', name: '医生甲', job_title: '医师', branches: [{ branch_id: '1', branch_name: '合成一店' }] },
      { doctor_id: '12', name: '医生乙', job_title: '医师', branches: [{ branch_id: '2', branch_name: '合成二店' }] },
    ]);
    state.overview.mockResolvedValue({
      schedules: [
        { schedule_id: '101', dentist_name: '医生甲', branch_id: '1', branch_name: '合成一店', starts_at: '2026-11-24T01:00:00Z', ends_at: '2026-11-24T04:00:00Z' },
        { schedule_id: '102', dentist_name: '医生乙', branch_id: '2', branch_name: '合成二店', starts_at: '2026-11-24T05:00:00Z', ends_at: '2026-11-24T09:00:00Z' },
      ],
      services: [{ item_id: '8', name: '合成检查' }],
      availability: [
        { schedule_id: '101', item_id: '8', slots: ['2026-11-24T01:00:00Z'] },
        { schedule_id: '102', item_id: '8', slots: ['2026-11-24T05:00:00Z'] },
      ], appointments: [],
    });
    const page = await doctorsPage();
    page.onLoad({ branch_id: '1' });
    await page.loadDirectory();
    await page.loadReal();
    expect(page.data.selectedBranchName).toBe('合成一店');
    expect((page.data.publicDoctors as Array<{ doctor_id: string }>).map((doctor) => doctor.doctor_id)).toEqual(['11']);
    const services = page.data.scheduleServices as Array<{ days: Array<{ morning: unknown[]; afternoon: unknown[] }> }>;
    expect(services).toHaveLength(1);
    expect(services[0].days[0].morning).toHaveLength(1);
    expect(services[0].days[0].afternoon).toHaveLength(0);
  });
});
