import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../miniprogram/services/http';

const flowMock = vi.hoisted(() => ({ hasSession: true, sessionRevision: 1 }));
vi.mock('../miniprogram/services/auth-flow', () => ({ authFlow: flowMock }));

const apiMock = vi.hoisted(() => ({
  overview: vi.fn(),
  create: vi.fn(),
  reschedule: vi.fn(),
  cancel: vi.fn(),
  checkin: vi.fn(),
  changes: vi.fn(),
  answerChange: vi.fn(),
}));
vi.mock('../miniprogram/services/booking-api', () => ({
  createRealBookingApi: () => apiMock,
  isApiError: (value: unknown) => typeof value === 'object' && value !== null && 'status' in value,
}));
const recordsMock = vi.hoisted(() => ({ load: vi.fn() }));
vi.mock('../miniprogram/services/records-api', () => ({ createRecordsApi: () => recordsMock }));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>, callback?: () => void) => void;
  [key: string]: unknown;
}

async function loadPage(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { showModal: vi.fn(), navigateTo: vi.fn(), scanCode: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/real-booking/real-booking');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch, callback) { Object.assign(this.data, patch); callback?.(); },
  };
  for (const [key, value] of Object.entries(source)) {
    if (typeof value === 'function') page[key] = (value as (...args: unknown[]) => unknown).bind(page);
  }
  return page;
}

function overview() {
  return {
    schedules: [{ schedule_id: '1', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z', branch_id: '1', branch_name: '门店', dentist_id: '1', dentist_name: '医生' }],
    services: [{ item_id: '1', name: '检查', minutes: 30, price: '80.00' }],
    availability: [{ schedule_id: '1', item_id: '1', duration_minutes: 30, slots: ['2030-01-02T01:00:00Z'] }],
    appointments: [],
  };
}

describe('真实预约页冲突刷新', () => {
  afterEach(() => vi.useRealTimers());
  beforeEach(() => {
    flowMock.hasSession = true;
    flowMock.sessionRevision = 1;
    apiMock.overview.mockReset().mockResolvedValue(overview());
    apiMock.create.mockReset();
    apiMock.reschedule.mockReset();
    apiMock.cancel.mockReset();
    apiMock.checkin.mockReset();
    apiMock.changes.mockReset().mockResolvedValue([]);
    apiMock.answerChange.mockReset();
    recordsMock.load.mockReset().mockResolvedValue({ visits: [], plans: [] });
  });

  it('把候诊中预约突出显示，其他待就诊按时间排列，历史默认收起', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2030-01-02T02:00:00Z'));
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [
      { appointment_id: 'cancelled', state: '已取消', starts_at: '2030-01-30T02:00:00Z', item_name: '洁牙', dentist_name: '甲医生', branch_name: '云栖门店', version: 1 },
      { appointment_id: 'tomorrow', state: '已确认', starts_at: '2030-01-03T02:00:00Z', item_name: '复查', dentist_name: '乙医生', branch_name: '滨江门店', version: 1 },
      { appointment_id: 'waiting', state: '候诊中', starts_at: '2030-01-02T03:30:00Z', item_name: '初诊检查', dentist_name: '丙医生', branch_name: '云栖门店', version: 2, queue_no: 1 },
      { appointment_id: 'today', state: '已确认', starts_at: '2030-01-02T04:00:00Z', item_name: '补牙', dentist_name: '丁医生', branch_name: '云栖门店', version: 1 },
      { appointment_id: 'completed', state: '已完成', starts_at: '2030-01-01T01:00:00Z', item_name: '检查', dentist_name: '戊医生', branch_name: '云栖门店', version: 2 },
    ] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    expect((page.data.featuredAppointment as { appointment_id: string; display: string }).appointment_id).toBe('waiting');
    expect((page.data.featuredAppointment as { display: string }).display).toBe('今天 11:30');
    expect((page.data.otherActiveAppointments as Array<{ appointment_id: string }>).map(a => a.appointment_id)).toEqual(['today', 'tomorrow']);
    expect((page.data.historyAppointments as Array<{ appointment_id: string }>).map(a => a.appointment_id)).toEqual(['cancelled', 'completed']);
    expect(page.data.historyExpanded).toBe(false);
    (page['toggleHistory'] as () => void)();
    expect(page.data.historyExpanded).toBe(true);
  });

  it('打开改约时选中第一组真实有号的排班与项目', async () => {
    const source = overview();
    apiMock.overview.mockResolvedValue({ ...source,
      schedules: [source.schedules[0], { ...source.schedules[0], schedule_id: '2' }],
      services: [source.services[0], { ...source.services[0], item_id: '2', name: '复查' }],
      availability: [{ schedule_id: '2', item_id: '2', duration_minutes: 30, slots: ['2030-01-02T02:00:00Z'] }],
      appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }],
    });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (page['startReschedule'] as (e: unknown) => void)({ currentTarget: { dataset: { id: '8' } } });
    expect(page.data.rescheduleScheduleIndex).toBe(1);
    expect(page.data.rescheduleServiceIndex).toBe(1);
    expect(page.data.rescheduleSlots).toEqual([{ value: '2030-01-02T02:00:00Z', label: '10:00' }]);
  });

  it('从本人复诊计划进入时仅列原医生排班，并带计划编号提交真实复诊', async () => {
    apiMock.overview.mockResolvedValue({ ...overview(), services: [
      ...overview().services, { item_id: '2', name: '短时项目', minutes: 60, price: '50.00' },
    ], availability: [...overview().availability,
      { schedule_id: '1', item_id: '2', duration_minutes: 15, slots: ['2030-01-02T02:00:00Z'] },
    ], schedules: [
      ...overview().schedules,
      { ...overview().schedules[0], schedule_id: '2', dentist_id: '2', dentist_name: '替诊医生' },
    ] });
    recordsMock.load.mockResolvedValue({ visits: [], plans: [{
      plan_id: '9', source_visit_id: '5', dentist_id: '1', dentist_name: '医生',
      patient_name: '教学用户', suggested_from: '2030-01-01', suggested_to: '2030-01-10',
      duration_minutes: 30, purpose: '复诊检查', state: '待协调',
    }] });
    apiMock.create.mockResolvedValue({ appointment_id: '18' });
    const page = await loadPage();
    page.onLoad?.({ followup_plan_id: '9' });
    await (page['load'] as () => Promise<void>)();
    expect((page.data.schedules as Array<{ id: string }>).map((s) => s.id)).toEqual(['1']);
    expect((page.data.services as Array<{ id: string }>).map((s) => s.id)).toEqual(['1']);
    await (page['submit'] as () => Promise<void>)();
    expect(apiMock.create).toHaveBeenCalledWith('1', '1', '2030-01-02T01:00:00Z', '复诊', '复诊检查', '9');
    expect(page.data.followupPlanId).toBe('');
  });

  it('复诊计划指定预约项目时只展示该项目并原样提交', async () => {
    apiMock.overview.mockResolvedValue({ ...overview(), services: [
      ...overview().services, { item_id: '2', name: '复诊项目', minutes: 30, price: '80.00' },
    ], availability: [...overview().availability,
      { schedule_id: '1', item_id: '2', duration_minutes: 30, slots: ['2030-01-02T01:30:00Z'] },
    ] });
    recordsMock.load.mockResolvedValue({ visits: [], plans: [{
      plan_id: '9', source_visit_id: '5', dentist_id: '1', dentist_name: '医生',
      patient_name: '用户', suggested_from: '2030-01-01', suggested_to: '2030-01-10',
      duration_minutes: 30, purpose: '复诊检查', state: '待协调', service_item_id: '2', service_item_name: '复诊项目',
    }] });
    apiMock.create.mockResolvedValue({ appointment_id: '19' });
    const page = await loadPage();
    page.onLoad?.({ followup_plan_id: '9' });
    await (page['load'] as () => Promise<void>)();
    expect((page.data.services as Array<{ id: string }>).map((s) => s.id)).toEqual(['2']);
    await (page['submit'] as () => Promise<void>)();
    expect(apiMock.create).toHaveBeenCalledWith('1', '2', '2030-01-02T01:30:00Z', '复诊', '复诊检查', '9');
  });

  it('计划失效时不退化成普通预约提交', async () => {
    const page = await loadPage();
    page.onLoad?.({ followup_plan_id: '9' });
    await (page['load'] as () => Promise<void>)();
    expect(page.data.overview).toBeNull();
    expect(page.data.error).toContain('已不可自助预约');
    await (page['submit'] as () => Promise<void>)();
    expect(apiMock.create).not.toHaveBeenCalled();
  });

  it('A 请求未返回时切换身份，迟到响应不能写回页面', async () => {
    let resolveA!: (value: unknown) => void;
    apiMock.overview.mockReturnValueOnce(new Promise((resolve) => { resolveA = resolve; }));
    const page = await loadPage();
    const pendingA = page['load'] as () => Promise<void>;
    const first = pendingA();
    expect(page.data.overview).toBeNull();

    let resolveB!: (value: unknown) => void;
    apiMock.overview.mockReturnValueOnce(new Promise((resolve) => { resolveB = resolve; }));
    flowMock.sessionRevision = 2;
    const second = pendingA();
    expect(page.data.overview).toBeNull();
    expect(page.data.appointments).toEqual([]);

    resolveB({ ...overview(), appointments: [{ appointment_id: 'B', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    await second;
    expect((page.data.appointments as Array<{ appointment_id: string }>)[0].appointment_id).toBe('B');

    resolveA({ ...overview(), appointments: [{ appointment_id: 'A', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '旧数据', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    await first;
    expect((page.data.appointments as Array<{ appointment_id: string }>)[0].appointment_id).toBe('B');
  });

  it('旧身份读取失败也不能写错误提示', async () => {
    let rejectA!: (reason: unknown) => void;
    apiMock.overview.mockReturnValueOnce(new Promise((_, reject) => { rejectA = reject; }));
    const page = await loadPage();
    const first = (page['load'] as () => Promise<void>)();
    flowMock.sessionRevision = 2;
    rejectA(new Error('旧会话失败'));
    await first;
    expect(page.data.error).toBe('');
    expect(page.data.appointments).toEqual([]);
  });

  it('onLoad 与 onShow 不重复读取', async () => {
    const page = await loadPage();
    page.onLoad?.();
    page.onShow?.();
    expect(apiMock.overview).toHaveBeenCalledTimes(1);
  });

  it('页面隐藏后旧读取不能写回', async () => {
    let resolveA!: (value: unknown) => void;
    apiMock.overview.mockReturnValueOnce(new Promise((resolve) => { resolveA = resolve; }));
    const page = await loadPage();
    const first = (page['load'] as () => Promise<void>)();
    page.onHide?.();
    resolveA(overview());
    await first;
    expect(page.data.overview).toBeNull();
    expect(page.data.loading).toBe(false);
  });

  it('旧身份协调方案迟到不写回', async () => {
    let resolveChanges!: (value: unknown) => void;
    apiMock.changes.mockReturnValueOnce(new Promise((resolve) => { resolveChanges = resolve; }));
    const page = await loadPage();
    const pending = (page['loadChanges'] as () => Promise<void>)();
    flowMock.sessionRevision = 2;
    resolveChanges([{ change_id: 'old', state: '待答复', version: 1, starts_at: '2030-01-02T02:00:00Z', reason: '旧会话' }]);
    await pending;
    expect(page.data.changes).toEqual([]);
  });

  it('同页两次读取乱序返回时只保留最后一次', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    apiMock.overview
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve; }));
    const page = await loadPage();
    const load = page['load'] as () => Promise<void>;
    const first = load();
    const second = load();
    resolveSecond({ ...overview(), appointments: [{ appointment_id: 'B', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '第二次', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    await second;
    resolveFirst({ ...overview(), appointments: [{ appointment_id: 'A', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '第一次', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    await first;
    expect((page.data.appointments as Array<{ appointment_id: string }>)[0].appointment_id).toBe('B');
  });

  it('创建操作中切换身份：不写旧预约编号也不触发旧刷新', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveCreate!: (value: unknown) => void;
    apiMock.create.mockReturnValueOnce(new Promise((resolve) => { resolveCreate = resolve; }));
    const action = (page['submit'] as () => Promise<void>)();
    expect(page.data.submitting).toBe(true);
    page.data.message = '旧身份消息';
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    expect(page.data.message).toBe('');
    resolveCreate({ appointment_id: 'A-OLD' });
    await action;
    expect(page.data.message).not.toContain('A-OLD');
    expect(page.data.submitting).toBe(false);
    expect(apiMock.overview).toHaveBeenCalledTimes(2);
  });

  it('改约操作中切换身份：迟到结果和刷新都不写', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    page.data.reschedulingId = '8';
    page.data.rescheduleReason = '调整';
    page.data.rescheduleVersion = 1;
    page.data.rescheduleSlots = [{ value: '2030-01-02T01:30:00Z', label: '09:30' }];
    let resolveReschedule!: (value: unknown) => void;
    apiMock.reschedule.mockReturnValueOnce(new Promise((resolve) => { resolveReschedule = resolve; }));
    const action = (page['confirmReschedule'] as () => Promise<void>)();
    await vi.waitFor(() => expect(apiMock.reschedule).toHaveBeenCalled());
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    resolveReschedule({ replacement: { appointment_id: 'A-OLD' } });
    await action;
    expect(page.data.message).not.toContain('A-OLD');
    expect(page.data.submitting).toBe(false);
  });

  it('扫码签到中切换身份：迟到候诊号不写回', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { result: string }) => void }) => success({ result: 'signed' }));
    let resolveCheckin!: (value: unknown) => void;
    apiMock.checkin.mockReturnValueOnce(new Promise((resolve) => { resolveCheckin = resolve; }));
    const action = (page['scanCheckin'] as (e: unknown) => Promise<void>)({ currentTarget: { dataset: { id: '8' } } });
    await vi.waitFor(() => expect(apiMock.checkin).toHaveBeenCalledWith('8', 'signed', 1));
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    resolveCheckin({ queue_no: 99 });
    await action;
    expect(page.data.message).not.toContain('99');
    expect(page.data.submitting).toBe(false);
    expect(wx.showModal).not.toHaveBeenCalled();
  });

  it('取消操作中切换身份：不写旧结果', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveCancel!: (value: unknown) => void;
    apiMock.cancel.mockReturnValueOnce(new Promise((resolve) => { resolveCancel = resolve; }));
    (wx.showModal as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { confirm: boolean }) => void }) => success({ confirm: true }));
    page.cancel?.({ currentTarget: { dataset: { id: '8' } } });
    await vi.waitFor(() => expect(apiMock.cancel).toHaveBeenCalledWith('8', '患者自助取消'));
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    resolveCancel({});
    await vi.waitFor(() => expect(page.data.submitting).toBe(false));
    expect(page.data.message).not.toContain('已取消');
  });

  it('协调答复中切换身份：不写旧答复结果', async () => {
    const page = await loadPage();
    const change = { change_id: '8', original_id: '1', proposed_id: '9', state: '待答复', version: 1, reason: '停诊', starts_at: '2030-01-02T02:00:00Z', ends_at: '2030-01-02T02:30:00Z', candidate_state: '待确认', applied: false };
    apiMock.changes.mockResolvedValueOnce([change]);
    await (page['loadChanges'] as () => Promise<void>)();
    let resolveAnswer!: (value: unknown) => void;
    apiMock.answerChange.mockReturnValueOnce(new Promise((resolve) => { resolveAnswer = resolve; }));
    (wx.showModal as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { confirm: boolean }) => void }) => success({ confirm: true }));
    page['answerChange']?.({ currentTarget: { dataset: { id: '8', accept: 'true' } } });
    await vi.waitFor(() => expect(apiMock.answerChange).toHaveBeenCalled());
    flowMock.sessionRevision = 2;
    await (page['load'] as () => Promise<void>)();
    resolveAnswer({ ...change, state: '已同意' });
    await vi.waitFor(() => expect(page.data.submitting).toBe(false));
    expect(page.data.message).not.toContain('已记录同意');
  });

  it('页面隐藏再返回同一账号不会永久卡在 submitting', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveCreate!: (value: unknown) => void;
    apiMock.create.mockReturnValueOnce(new Promise((resolve) => { resolveCreate = resolve; }));
    const action = (page['submit'] as () => Promise<void>)();
    page.onHide?.();
    expect(page.data.submitting).toBe(false);
    page.onShow?.();
    resolveCreate({ appointment_id: 'A-OLD' });
    await action;
    expect(page.data.submitting).toBe(false);
  });

  it('旧操作结束时不清理新操作的 submitting', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    let resolveA!: (value: unknown) => void;
    apiMock.create.mockReturnValueOnce(new Promise((resolve) => { resolveA = resolve; }));
    const actionA = (page['submit'] as () => Promise<void>)();
    page.onHide?.();
    await (page['load'] as () => Promise<void>)();
    let resolveB!: (value: unknown) => void;
    apiMock.create.mockReturnValueOnce(new Promise((resolve) => { resolveB = resolve; }));
    const actionB = (page['submit'] as () => Promise<void>)();
    expect(page.data.submitting).toBe(true);
    resolveA({ appointment_id: 'A-OLD' });
    await actionA;
    expect(page.data.submitting).toBe(true);
    resolveB({ appointment_id: 'B-NEW' });
    await actionB;
    expect(page.data.message).toContain('B-NEW');
  });

  it('创建成功后等待权威刷新期间仍阻止重复提交', async () => {
    let resolveRefresh!: (value: unknown) => void;
    apiMock.overview
      .mockResolvedValueOnce(overview())
      .mockReturnValueOnce(new Promise((resolve) => { resolveRefresh = resolve; }));
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    apiMock.create.mockResolvedValueOnce({ appointment_id: 'created' });

    const submit = page['submit'] as () => Promise<void>;
    const action = submit();
    await vi.waitFor(() => expect(apiMock.overview).toHaveBeenCalledTimes(2));
    expect(page.data.submitting).toBe(true);
    await submit();
    expect(apiMock.create).toHaveBeenCalledTimes(1);

    resolveRefresh(overview());
    await action;
    expect(page.data.submitting).toBe(false);
    expect(page.data.message).toContain('created');
  });

  it('未登录显示登录入口且不读取真实接口', async () => {
    flowMock.hasSession = false;
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    expect(page.data.hasSession).toBe(false);
    expect(page.data.error).toContain('登录');
    expect(apiMock.overview).not.toHaveBeenCalled();
  });

  it('创建提交透传后端原始 ISO 时段', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    apiMock.create.mockResolvedValueOnce({ appointment_id: '3' });
    await (page['submit'] as () => Promise<void>)();
    expect(apiMock.create).toHaveBeenCalledWith('1', '1', '2030-01-02T01:00:00Z', '初诊', '患者端自助预约');
  });

  it('改约提交透传后端原始 ISO 时段', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    page.data.reschedulingId = '2';
    page.data.rescheduleReason = '调整';
    page.data.rescheduleVersion = 1;
    page.data.rescheduleSlots = [{ value: '2030-01-02T01:30:00Z', label: '09:30' }];
    apiMock.reschedule.mockResolvedValueOnce({ original: { appointment_id: '2' }, replacement: { appointment_id: '3' } });
    await (page['confirmReschedule'] as () => Promise<void>)();
    expect(apiMock.reschedule).toHaveBeenCalledWith('2', '1', '1', '2030-01-02T01:30:00Z', 1, '调整');
  });

  it('创建 409 后重新读取权威数据并保留冲突提示', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    expect(page.data.overview).toBeTruthy();
    expect((page.data.slots as Array<{ value: string }>)[0].value).toBe('2030-01-02T01:00:00Z');
    apiMock.create.mockRejectedValueOnce(new ApiError('SLOT_UNAVAILABLE', 409, '该时段已不可预约'));
    await (page['submit'] as () => Promise<void>)();
    expect(apiMock.overview).toHaveBeenCalledTimes(2);
    expect(page.data.error).toBe('该时段已不可预约');
    expect(page.data.submitting).toBe(false);
  });

  it('改约 409 后重新读取并重置失效选择', async () => {
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    page.data.reschedulingId = '2';
    page.data.rescheduleReason = '调整';
    page.data.rescheduleSlots = [{ value: '2030-01-02T01:30:00Z', label: '09:30' }];
    apiMock.reschedule.mockRejectedValueOnce(new ApiError('SLOT_UNAVAILABLE', 409, '新时段冲突'));
    await (page['confirmReschedule'] as () => Promise<void>)();
    expect(apiMock.overview).toHaveBeenCalledTimes(2);
    expect(page.data.error).toBe('新时段冲突');
    expect(page.data.rescheduleSlotIndex).toBe(0);
  });

  it('只对已确认预约扫码，成功后读取并显示同一候诊号', async () => {
    const page=await loadPage();
    apiMock.overview.mockResolvedValue({ ...overview(), appointments:[{appointment_id:'8',previous_id:null,patient_name:'甲',dentist_name:'医生',branch_name:'门店',starts_at:'2030-01-02T01:00:00Z',ends_at:'2030-01-02T01:30:00Z',visit_type:'初诊',channel:'患者端',state:'已确认',version:1,purpose:'检查',item_name:'检查'}] });
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementation(({success}: {success:(v:{result:string})=>void})=>success({result:'signed-code'}));
    apiMock.checkin.mockResolvedValueOnce({ticket_id:'3',appointment_id:'8',queue_no:2});
    await (page['scanCheckin'] as (e: unknown) => Promise<void>)({currentTarget:{dataset:{id:'8'}}});
    expect(apiMock.checkin).toHaveBeenCalledWith('8','signed-code',1);
    expect(apiMock.overview).toHaveBeenCalledTimes(2);
    expect(page.data.message).toContain('候诊号 2');
    expect(wx.showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '签到成功', content: expect.stringContaining('候诊号 2'), showCancel: false }));
  });

  it('扫码成功后立即告知候诊号，不等待列表刷新', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { result: string }) => void }) => success({ result: 'signed' }));
    let resolveOverview!: (value: unknown) => void;
    apiMock.overview.mockReturnValueOnce(new Promise((resolve) => { resolveOverview = resolve; }));
    apiMock.checkin.mockResolvedValueOnce({ ticket_id: '3', appointment_id: '8', queue_no: 7 });
    const action = (page['scanCheckin'] as (e: unknown) => Promise<void>)({ currentTarget: { dataset: { id: '8' } } });
    await vi.waitFor(() => expect(wx.showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '签到成功', content: expect.stringContaining('候诊号 7') })));
    expect(page.data.submitting).toBe(true);
    resolveOverview(overview());
    await action;
    expect(page.data.submitting).toBe(false);
  });

  it('微信扫码界面暂时隐藏页面后，返回时仍提交同一次扫码', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { result: string }) => void }) => {
      (page['onHide'] as () => void)();
      (page['onShow'] as () => void)();
      success({ result: 'signed' });
    });
    apiMock.checkin.mockResolvedValueOnce({ ticket_id: '3', appointment_id: '8', queue_no: 4 });
    await (page['scanCheckin'] as (e: unknown) => Promise<void>)({ currentTarget: { dataset: { id: '8' } } });
    expect(apiMock.checkin).toHaveBeenCalledTimes(1);
    expect(wx.showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '签到成功', content: expect.stringContaining('候诊号 4') }));
    expect(page.data.scanPending).toBe(false);
    expect(page.data.submitting).toBe(false);
  });

  it('用户取消扫码时不发签到请求，也不显示失败弹窗', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementationOnce(({ fail }: { fail: (v: { errMsg: string }) => void }) => {
      (page['onHide'] as () => void)();
      (page['onShow'] as () => void)();
      fail({ errMsg: 'scanCode:fail cancel' });
    });
    await (page['scanCheckin'] as (e: unknown) => Promise<void>)({ currentTarget: { dataset: { id: '8' } } });
    expect(apiMock.checkin).not.toHaveBeenCalled();
    expect(wx.showModal).not.toHaveBeenCalled();
    expect(page.data.submitting).toBe(false);
  });

  it('签到码过期时弹出明确错误并刷新预约状态', async () => {
    apiMock.overview.mockResolvedValueOnce({ ...overview(), appointments: [{ appointment_id: '8', state: '已确认', starts_at: '2030-01-02T01:00:00Z', item_name: '检查', dentist_name: '医生', branch_name: '门店', version: 1 }] });
    const page = await loadPage();
    await (page['load'] as () => Promise<void>)();
    (wx.scanCode as ReturnType<typeof vi.fn>).mockImplementationOnce(({ success }: { success: (v: { result: string }) => void }) => success({ result: 'expired' }));
    apiMock.checkin.mockRejectedValueOnce(new ApiError('CHECKIN_CODE_INVALID', 409, '签到码已过期，请重新扫码'));
    await (page['scanCheckin'] as (e: unknown) => Promise<void>)({ currentTarget: { dataset: { id: '8' } } });
    expect(wx.showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '暂未签到', content: '签到码已过期，请重新扫码' }));
    expect(apiMock.overview).toHaveBeenCalledTimes(2);
    expect(page.data.error).toBe('签到码已过期，请重新扫码');
  });

  it('已撤回候选不提供答复入口', async () => {
    const page=await loadPage();
    apiMock.changes.mockResolvedValue([{change_id:'7',original_id:'1',proposed_id:'9',state:'已撤回',version:2,
      reason:'门店撤回',starts_at:'2030-01-02T02:00:00Z',ends_at:'2030-01-02T02:30:00Z',
      candidate_state:'已取消',applied:false}]);
    await (page['loadChanges'] as () => Promise<void>)();
    expect((page.data.changes as Array<{canAnswer:boolean}>)[0].canAnswer).toBe(false);
    (page['answerChange'] as (e: unknown) => void)({currentTarget:{dataset:{id:'7',accept:'true'}}});
    expect(apiMock.answerChange).not.toHaveBeenCalled();
  });

  it('候选答复必须经明确确认，同意后仍提示等待落实', async () => {
    const page=await loadPage();
    const change={change_id:'8',original_id:'1',proposed_id:'9',state:'待答复',version:1,
      reason:'停诊',starts_at:'2030-01-02T02:00:00Z',ends_at:'2030-01-02T02:30:00Z',
      candidate_state:'待确认',applied:false};
    apiMock.changes.mockResolvedValue([change]);
    await (page['loadChanges'] as () => Promise<void>)();
    expect((page.data.changes as unknown[]).length).toBe(1);
    apiMock.answerChange.mockResolvedValue({...change,state:'已同意',version:2});
    (wx.showModal as ReturnType<typeof vi.fn>).mockImplementationOnce(({success}: {success:(v:{confirm:boolean})=>void})=>success({confirm:true}));
    (page['answerChange'] as (e: unknown) => void)({currentTarget:{dataset:{id:'8',accept:'true'}}});
    await vi.waitFor(()=>expect(apiMock.answerChange).toHaveBeenCalledWith('8',true,1));
    await vi.waitFor(()=>expect(page.data.message).toContain('等待前台落实'));
  });
});
