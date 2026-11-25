import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}

const pageConfigs = new Map<string, Record<string, unknown>>();

const wxMock = vi.hoisted(() => ({
  navigateTo: vi.fn(),
  showToast: vi.fn(),
  showModal: vi.fn(),
  getStorageSync: vi.fn().mockReturnValue('1'),
  setStorageSync: vi.fn(),
}));

async function loadPage(path: string, resetModules = true): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', wxMock);
  if (resetModules) vi.resetModules();
  await import(path);
  if (config) pageConfigs.set(path, config);
  else config = pageConfigs.get(path) ?? null;
  if (!config) throw new Error('Page config not captured');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch: Record<string, unknown>) { Object.assign(this.data, patch); },
  };
  for (const [key, value] of Object.entries(source)) {
    if (typeof value === 'function') page[key] = (value as (...args: unknown[]) => unknown).bind(page);
  }
  return page;
}

describe('患者端演示页面', () => {
  beforeEach(() => {
    wxMock.getStorageSync.mockReturnValue('1');
    wxMock.setStorageSync.mockImplementation((key: string, value: string) => {
      if (key === 'a-clinic.patient.demo-enabled') wxMock.getStorageSync.mockReturnValue(value);
    });
  });

  it('首页把真实服务与本地演示样例分开，演示入口不冒充真实入口', async () => {
    const page = await loadPage('../miniprogram/pages/home/home');
    page.onLoad?.();
    expect(page.data.demoNotice).toContain('演示数据');
    expect(page.data.showDemo).toBe(true);
    expect(page.data.hasPatientSession).toBe(false);
    expect(page.data.realNext).toBeNull();
    expect(page.data.appointments).toBeUndefined();
  });

  it('正常预约无需替诊同意；确认后写入统一演示预约并显示编号', async () => {
    const page = await loadPage('../miniprogram/pages/booking/booking');
    page.onLoad?.({ doctorId: 'DR-02' });
    page.confirm?.();
    expect(wxMock.showToast).toHaveBeenCalledWith(expect.objectContaining({ title: '请选择时段' }));
    page.onDateChange?.({ detail: { value: '2026-10-27' } });
    page.pickSlot?.({ currentTarget: { dataset: { value: '10:00' } } });
    page.confirm?.();
    expect(page.data.confirmed).toBe(true);
    expect(String(page.data.resultText)).toContain('未写入后台');
    expect(String(page.data.resultText)).toContain('AP-DEMO-');

    const appointments = await loadPage('../miniprogram/pages/appointments/appointments', false);
    appointments.onLoad?.();
    const rows = appointments.data.appointments as Array<{ id: string; time: string }>;
    expect(rows.some((x) => x.time === '10:00')).toBe(true);
  });

  it('改约需选择新时段并更新同一条预约', async () => {
    const page = await loadPage('../miniprogram/pages/appointments/appointments', false);
    page.onLoad?.();
    const rows = page.data.appointments as Array<{ id: string; date: string; time: string }>;
    const target = rows.find((x) => x.id.startsWith('AP-DEMO-'))!;
    page.startReschedule?.({ currentTarget: { dataset: { id: target.id } } });
    expect(page.data.reschedulingId).toBe(target.id);
    page.pickRescheduleSlot?.({ currentTarget: { dataset: { value: '15:00' } } });
    page.confirmReschedule?.();
    expect((page.data.appointments as Array<{ id: string; time: string }>).find((x) => x.id === target.id)?.time).toBe('15:00');
    expect(String(page.data.message)).toContain('改约');
  });

  it('改约控件的点击不会冒泡到整张预约卡片并关闭改约区', () => {
    const wxml = readFileSync(new URL('../miniprogram/pages/appointments/appointments.wxml', import.meta.url), 'utf8');
    expect(wxml).toMatch(/class="appt-card[^\n]*>\s*<view class="appt-head" data-id="\{\{item\.id\}\}" bindtap="choose"/);
    expect(wxml).not.toMatch(/class="appt-card[^\n]*bindtap="choose"/);
  });

  it('关闭患者端演示后不展示预约样例，可重新开启', async () => {
    const page = await loadPage('../miniprogram/pages/home/home', false);
    page.onLoad?.();
    page.switchToReal?.();
    expect(page.data.demoEnabled).toBe(false);
    expect(page.data.showDemo).toBe(false);
    page.switchToDemo?.();
    expect(page.data.demoEnabled).toBe(true);
    expect(page.data.showDemo).toBe(true);
  });

  it('只有实际提出替诊时才让患者接受或拒绝；拒绝不自动确认原预约', async () => {
    const page = await loadPage('../miniprogram/pages/booking/booking');
    page.onLoad?.({ doctorId: 'DR-01' });
    page.pickSlot?.({ currentTarget: { dataset: { value: '14:30' } } });
    page.proposeSubstitute?.();
    expect(page.data.substitute).toMatchObject({ originalDoctor: '林知夏', reason: expect.stringContaining('演示') });
    page.rejectSubstitute?.();
    expect(page.data.substitute).toBeNull();
    expect(page.data.confirmed).toBe(false);
    expect(String(page.data.substituteMessage)).toContain('原医生和原预约均未改变');
  });

  it('演示预约、列表和记录页都有进入真实服务的出口', async () => {
    wxMock.navigateTo.mockClear();
    const booking = await loadPage('../miniprogram/pages/booking/booking');
    booking.goRealBooking?.();
    expect(wxMock.navigateTo).toHaveBeenCalledWith({ url: '/pages/real-booking/real-booking' });

    wxMock.navigateTo.mockClear();
    const appointments = await loadPage('../miniprogram/pages/appointments/appointments', false);
    appointments.goRealBooking?.();
    expect(wxMock.navigateTo).toHaveBeenCalledWith({ url: '/pages/real-booking/real-booking' });

    wxMock.navigateTo.mockClear();
    const records = await loadPage('../miniprogram/pages/records/records', false);
    records.goRealRecords?.();
    expect(wxMock.navigateTo).toHaveBeenCalledWith({ url: '/pages/real-records/real-records' });
  });

  it('已取消演示预约不能继续改约', async () => {
    const page = await loadPage('../miniprogram/pages/appointments/appointments');
    page.onLoad?.();
    page.cancel?.({ currentTarget: { dataset: { id: 'AP-1001' } } });
    page.startReschedule?.({ currentTarget: { dataset: { id: 'AP-1001' } } });
    expect(String(page.data.message)).toContain('不能改约');
  });

  it('取消预约只修改本地状态并明确未写入后台', async () => {
    const page = await loadPage('../miniprogram/pages/appointments/appointments');
    page.onLoad?.();
    page.cancel?.({ currentTarget: { dataset: { id: 'AP-1001' } } });
    const rows = page.data.appointments as Array<{ id: string; status: string }>;
    expect(rows.find((x) => x.id === 'AP-1001')?.status).toContain('已取消');
    expect(String(page.data.message)).toContain('未写入后台');
  });
});
