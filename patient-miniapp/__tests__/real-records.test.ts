import { beforeEach, describe, expect, it, vi } from 'vitest';

const flowMock = vi.hoisted(() => ({ hasSession: true, sessionRevision: 1 }));
vi.mock('../miniprogram/services/auth-flow', () => ({ authFlow: flowMock }));

const apiMock = vi.hoisted(() => ({
  load: vi.fn(),
  loadTreatmentPlans: vi.fn(),
  acceptPlan: vi.fn(),
  rejectPlan: vi.fn(),
}));
vi.mock('../miniprogram/services/records-api', () => ({ createRecordsApi: () => apiMock }));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}

async function loadPage(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { showModal: vi.fn(), navigateTo: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/real-records/real-records');
  if (!config) throw new Error('real records page missing');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch) { Object.assign(this.data, patch); },
  };
  for (const [key, value] of Object.entries(source)) {
    if (typeof value === 'function') page[key] = (value as (...args: unknown[]) => unknown).bind(page);
  }
  return page;
}

describe('真实就诊页加载边界', () => {
  beforeEach(() => {
    flowMock.hasSession = true;
    flowMock.sessionRevision = 1;
    apiMock.load.mockReset().mockResolvedValue({ visits: [], plans: [] });
    apiMock.loadTreatmentPlans.mockReset().mockResolvedValue([]);
    apiMock.acceptPlan.mockReset();
    apiMock.rejectPlan.mockReset();
  });

  it('只对本人列表中的待协调复诊计划开放预约入口', async () => {
    apiMock.load.mockResolvedValue({ visits: [], plans: [
      { plan_id: '9', state: '待协调' }, { plan_id: '10', state: '已预约' },
    ] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    const book = page['bookFollowup'] as (event: { currentTarget: { dataset: { id: string } } }) => void;
    book({ currentTarget: { dataset: { id: '10' } } });
    book({ currentTarget: { dataset: { id: '99' } } });
    expect(wx.navigateTo).not.toHaveBeenCalled();
    book({ currentTarget: { dataset: { id: '9' } } });
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/real-booking/real-booking?followup_plan_id=9' });
  });

  it('治疗计划失败时显示重试提示，不退化成空计划', async () => {
    apiMock.loadTreatmentPlans.mockRejectedValueOnce(new Error('治疗计划接口失败'));
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    expect(page.data.treatmentError).toBe('治疗计划读取失败，请重试。');
    expect(page.data.loading).toBe(false);
  });

  it('第一段读取返回后切换身份，第二阶段读取迟到也不写页面', async () => {
    let resolvePlans!: (value: unknown) => void;
    apiMock.load.mockResolvedValueOnce({ visits: [{ visit_id: 'A' }], plans: [] });
    apiMock.loadTreatmentPlans.mockReturnValueOnce(new Promise((resolve) => { resolvePlans = resolve; }));
    const page = await loadPage();
    const pending = page['load'] as () => Promise<void>;
    const request = pending();
    await vi.waitFor(() => expect(apiMock.loadTreatmentPlans).toHaveBeenCalled());
    flowMock.sessionRevision = 2;
    resolvePlans([{ plan_id: 'old-plan', estimated_total_cents: 100 }]);
    await request;
    expect(page.data.visits).toEqual([]);
    expect(page.data.treatmentPlans).toEqual([]);
  });

  it('旧身份读取失败也不能写错误提示', async () => {
    let rejectA!: (reason: unknown) => void;
    apiMock.load.mockReturnValueOnce(new Promise((_, reject) => { rejectA = reject; }));
    const page = await loadPage();
    const first = (page['load'] as () => Promise<void>)();
    flowMock.sessionRevision = 2;
    rejectA(new Error('旧会话失败'));
    await first;
    expect(page.data.error).toBe('');
    expect(page.data.visits).toEqual([]);
  });

  it('onLoad 与 onShow 不重复读取', async () => {
    const page = await loadPage();
    page.onLoad?.();
    page.onShow?.();
    expect(apiMock.load).toHaveBeenCalledTimes(1);
  });

  it('同页两次读取乱序返回时只保留最后一次', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    apiMock.load
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve; }));
    const page = await loadPage();
    const load = page['load'] as () => Promise<void>;
    const first = load();
    const second = load();
    resolveSecond({ visits: [{ visit_id: 'B' }], plans: [] });
    await second;
    resolveFirst({ visits: [{ visit_id: 'A' }], plans: [] });
    await first;
    expect((page.data.visits as Array<{ visit_id: string }>).map((v) => v.visit_id)).toEqual(['B']);
  });

  it('接受治疗计划中切换身份：不写旧错误或旧刷新结果', async () => {
    apiMock.loadTreatmentPlans.mockResolvedValue([{ plan_id: 'p1', estimated_total_cents: 100 }]);
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveAccept!: (value: unknown) => void;
    apiMock.acceptPlan.mockReturnValueOnce(new Promise((resolve) => { resolveAccept = resolve; }));
    (wx.showModal as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { confirm: boolean }) => void }) => success({ confirm: true }));
    page.acceptPlan?.({ currentTarget: { dataset: { id: 'p1' } } });
    await vi.waitFor(() => expect(apiMock.acceptPlan).toHaveBeenCalledWith('p1'));
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    resolveAccept({});
    await vi.waitFor(() => expect(page.data.submitting).toBe(false));
    expect(page.data.error).toBe('');
  });

  it('拒绝治疗计划页面隐藏后返回：submitting 会复位', async () => {
    apiMock.loadTreatmentPlans.mockResolvedValue([{ plan_id: 'p1', estimated_total_cents: 100 }]);
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveReject!: (value: unknown) => void;
    apiMock.rejectPlan.mockReturnValueOnce(new Promise((resolve) => { resolveReject = resolve; }));
    (wx.showModal as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { confirm: boolean }) => void }) => success({ confirm: true }));
    page.rejectPlan?.({ currentTarget: { dataset: { id: 'p1' } } });
    await vi.waitFor(() => expect(apiMock.rejectPlan).toHaveBeenCalledWith('p1'));
    expect(page.data.submitting).toBe(true);
    page.onHide?.();
    expect(page.data.submitting).toBe(false);
    page.onShow?.();
    resolveReject({});
    await vi.waitFor(() => expect(page.data.submitting).toBe(false));
  });

  it('未登录不读取真实接口并显示登录状态', async () => {
    flowMock.hasSession = false;
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    expect(page.data.hasSession).toBe(false);
    expect(page.data.loading).toBe(false);
    expect(apiMock.load).not.toHaveBeenCalled();
  });
});
