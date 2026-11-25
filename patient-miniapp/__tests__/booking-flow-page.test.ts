import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RealBookingOverview } from '../miniprogram/services/booking-api';

const state = vi.hoisted(() => ({
  hasSession: true,
  sessionRevision: 1,
  patientRevision: 1,
  branches: vi.fn(),
  overview: vi.fn(),
  create: vi.fn(),
  records: vi.fn(),
}));
vi.mock('../miniprogram/services/auth-flow', () => ({
  authFlow: { get hasSession() { return state.hasSession; }, get sessionRevision() { return state.sessionRevision; } },
}));
vi.mock('../miniprogram/services/session', () => ({
  patientSession: { get revision() { return state.patientRevision; }, get token() { return 'token'; } },
}));
vi.mock('../miniprogram/demo/store', () => ({ isDemoEnabled: () => false }));
vi.mock('../miniprogram/services/public-directory', () => ({
  createPublicDirectoryApi: () => ({ branches: state.branches }),
}));
vi.mock('../miniprogram/services/booking-api', () => ({
  createRealBookingApi: () => ({ overview: state.overview, create: state.create }),
  isApiError: (value: unknown) => typeof value === 'object' && value !== null && 'status' in value,
}));
vi.mock('../miniprogram/services/records-api', () => ({
  createRecordsApi: () => ({ load: state.records }),
}));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}
async function loadPage(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { navigateTo: vi.fn(), navigateBack: vi.fn(), showModal: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/booking-flow/booking-flow');
  if (!config) throw new Error('booking-flow page missing');
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

const overview: RealBookingOverview = {
  schedules: [
    { schedule_id: 's1', starts_at: '2026-11-23T01:00:00Z', ends_at: '2026-11-23T04:00:00Z', branch_id: '1', branch_name: '门店一', dentist_id: 'd1', dentist_name: '医生一' },
    { schedule_id: 's2', starts_at: '2026-11-23T06:00:00Z', ends_at: '2026-11-23T09:00:00Z', branch_id: '1', branch_name: '门店一', dentist_id: 'd2', dentist_name: '医生二' },
  ],
  services: [{ item_id: '1', name: '检查', minutes: 30, price: '80.00' }],
  availability: [
    { schedule_id: 's1', item_id: '1', duration_minutes: 30, slots: ['2026-11-23T01:00:00Z', '2026-11-23T01:30:00Z'] },
    { schedule_id: 's2', item_id: '1', duration_minutes: 30, slots: ['2026-11-23T06:30:00Z'] },
  ],
  appointments: [],
};

beforeEach(() => {
  state.hasSession = true;
  state.sessionRevision = 1;
  state.patientRevision = 1;
  state.branches.mockReset().mockResolvedValue([{ branch_id: '1', name: '门店一', address: '地址一', phone: '000', opening_hours: '09:00–17:00' }]);
  state.overview.mockReset().mockResolvedValue(overview);
  state.create.mockReset().mockResolvedValue({ appointment_id: 'A-1' });
  state.records.mockReset().mockResolvedValue({ visits: [], plans: [] });
});

describe('真实预约主路径', () => {
  it('从项目排班进入时保留门店和项目，直接展示日期时段', async () => {
    const page = await loadPage();
    page.onLoad({ branch_id: '1', item_id: '1' });
    page.onShow();
    await flush();
    expect(page.data.branchId).toBe('1');
    expect((page.data.service as { id: string }).id).toBe('1');
    expect(page.data.step).toBe('schedule');
    expect((page.data.dates as unknown[]).length).toBe(1);
  });

  it('未登录先浏览门店；选择门店后只显示登录提示，不请求 overview', async () => {
    state.hasSession = false;
    const page = await loadPage();
    page.onShow();
    await flush();
    expect((page.data.branches as unknown[]).length).toBe(1);
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    expect(page.data.loginRequired).toBe(true);
    expect(page.data.step).toBe('branch');
    expect(state.overview).not.toHaveBeenCalled();
  });

  it('项目只来自当前门店 availability；选项目后按日期和上午/下午展示', async () => {
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    expect(state.overview).toHaveBeenCalledTimes(1);
    expect(page.data.step).toBe('service');
    expect((page.data.services as Array<{ id: string }>).map((row) => row.id)).toEqual(['1']);
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    expect(page.data.step).toBe('schedule');
    const dates = page.data.dates as Array<{ date: string; morning: unknown[]; afternoon: unknown[] }>;
    expect(dates[0].date).toBe('2026-11-23');
    expect(dates[0].morning).toHaveLength(2);
    expect(dates[0].afternoon).toHaveLength(1);
  });

  it('选时段后读取历史提示；登记类型由用户选择，不自动判为初诊', async () => {
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    await flush();
    expect(page.data.step).toBe('confirm');
    expect(page.data.visitType).toBe('');
    expect(String(page.data.visitTypeNotice)).toContain('请按实际情况选择');
    page.chooseVisitType({ currentTarget: { dataset: { value: '初诊' } } });
    await (page.submit as () => Promise<void>)();
    expect(state.create).toHaveBeenCalledWith('s1', '1', '2026-11-23T01:00:00Z', '初诊', '患者端自助预约');
    expect(page.data.step).toBe('success');
  });

  it('有历史记录只给提示，不自动认定复诊资格', async () => {
    state.records.mockResolvedValue({ visits: [{ visit_id: 'v1' }], plans: [] });
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    await flush();
    expect(page.data.visitType).toBe('');
    expect(String(page.data.visitTypeNotice)).toContain('不能据此确认复诊资格');
    page.chooseVisitType({ currentTarget: { dataset: { value: '复诊' } } });
    expect(page.data.visitType).toBe('复诊');
  });

  it('返回上一步后，迟到的病历响应不能写回页面', async () => {
    let resolveOld!: (value: { visits: unknown[]; plans: unknown[] }) => void;
    state.records.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    page.back();
    resolveOld({ visits: [{ visit_id: 'old' }], plans: [] });
    await flush();
    expect(page.data.step).toBe('schedule');
    expect(String(page.data.visitTypeNotice)).toBe('');
    expect(page.data.visitTypeLoading).toBe(false);
  });

  it('页面隐藏后，迟到的病历响应不能写回页面', async () => {
    let resolveOld!: (value: { visits: unknown[]; plans: unknown[] }) => void;
    state.records.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    page.onHide();
    resolveOld({ visits: [{ visit_id: 'old' }], plans: [] });
    await flush();
    expect(String(page.data.visitTypeNotice)).toBe('');
    expect(page.data.visitTypeLoading).toBe(false);
  });

  it('旧时段病历响应迟到时，不能覆盖新时段的提示', async () => {
    let resolveOld!: (value: { visits: unknown[]; plans: unknown[] }) => void;
    state.records.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    state.records.mockResolvedValueOnce({ visits: [], plans: [] });
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's2', slot: '2026-11-23T06:30:00Z' } } });
    await flush();
    expect(String(page.data.visitTypeNotice)).toContain('未检测到');
    resolveOld({ visits: [{ visit_id: 'old' }], plans: [] });
    await flush();
    expect(String(page.data.visitTypeNotice)).toContain('未检测到');
    expect(String(page.data.visitTypeError)).toBe('');
  });

  it('提交 409 后刷新可约数据并回到时间选择，不自动重试', async () => {
    state.create.mockRejectedValueOnce({ status: 409, message: '该时段不可约' });
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    page.chooseSlot({ currentTarget: { dataset: { date: '2026-11-23', schedule: 's1', slot: '2026-11-23T01:00:00Z' } } });
    await flush();
    page.chooseVisitType({ currentTarget: { dataset: { value: '初诊' } } });
    await (page.submit as () => Promise<void>)();
    await flush();
    expect(state.create).toHaveBeenCalledTimes(1);
    expect(page.data.step).toBe('schedule');
    expect(page.data.selectedSlot).toBeNull();
    expect(String(page.data.overviewError)).toContain('该时段已不可预约');
    expect(state.overview).toHaveBeenCalledTimes(2);
  });

  it('换账号后清空老账号的项目、日期和时段，并保留门店入口', async () => {
    const page = await loadPage();
    page.onShow();
    await flush();
    page.chooseBranch({ currentTarget: { dataset: { id: '1' } } });
    await flush();
    page.chooseService({ currentTarget: { dataset: { id: '1' } } });
    state.sessionRevision = 2;
    state.patientRevision = 2;
    page.onShow();
    await flush();
    expect(page.data.step).toBe('service');
    expect(page.data.service).toBeNull();
    expect(page.data.dates).toEqual([]);
    expect(page.data.branchId).toBe('1');
  });
});
