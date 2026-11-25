import { describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({ hasSession: true, revision: 1, overview: vi.fn() }));
vi.mock('../miniprogram/services/auth-flow', () => ({ authFlow: { get hasSession() { return state.hasSession; } } }));
vi.mock('../miniprogram/services/session', () => ({ patientSession: { get revision() { return state.revision; } } }));
vi.mock('../miniprogram/services/booking-api', () => ({ createRealBookingApi: () => ({ overview: state.overview }) }));

function page() {
  let config: Record<string, unknown> = {};
  const navigateTo = vi.fn();
  vi.stubGlobal('wx', { getStorageSync: () => '0', navigateTo });
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  return import('../miniprogram/pages/doctors/doctors').then(() => {
    const data = { ...(config.data as Record<string, unknown>) };
    const instance: Record<string, unknown> = { data, setData(patch: Record<string, unknown>) { Object.assign(data, patch); } };
    for (const [key, value] of Object.entries(config)) if (typeof value === 'function') instance[key] = value.bind(instance);
    return { instance, data, navigateTo };
  });
}

describe('真实医生排班展示', () => {
  it('按服务展示上午和下午的实际班次，预约保留门店及项目', async () => {
    state.hasSession = true;
    state.revision = 1;
    state.overview.mockResolvedValue({ schedules: [
      { schedule_id: '11', dentist_id: '7', dentist_name: '合成医生甲', branch_id: '1', branch_name: '合成门店一', starts_at: '2026-11-05T01:00:00Z', ends_at: '2026-11-05T04:00:00Z' },
      { schedule_id: '12', dentist_id: '8', dentist_name: '合成医生乙', branch_id: '2', branch_name: '合成门店二', starts_at: '2026-11-05T05:00:00Z', ends_at: '2026-11-05T09:00:00Z' },
    ], services: [
      { item_id: '1', name: '儿童检查', minutes: 30, price: '80.00' },
      { item_id: '2', name: '补牙', minutes: 45, price: '360.00' },
    ], availability: [
      { schedule_id: '11', item_id: '1', duration_minutes: 30, slots: ['2026-11-05T01:00:00Z'] },
      { schedule_id: '12', item_id: '1', duration_minutes: 30, slots: [] },
      { schedule_id: '12', item_id: '2', duration_minutes: 45, slots: ['2026-11-05T05:00:00Z'] },
    ], appointments: [] });
    const { instance, data, navigateTo } = await page();
    await (instance.loadReal as () => Promise<void>)();
    const services = data.scheduleServices as Array<{ id: string; days: Array<{ morning: Array<{ timeRange: string }>; afternoon: Array<{ timeRange: string; available: boolean }> }> }>;
    expect(services.map((service) => service.id)).toEqual(['1', '2']);
    expect(services[0].days[0].morning[0].timeRange).toBe('09:00–12:00');
    expect(services[0].days[0].afternoon[0]).toMatchObject({ timeRange: '13:00–17:00', available: false });
    (instance.chooseScheduleService as (event: unknown) => void)({ currentTarget: { dataset: { id: '2' } } });
    expect(data.selectedServiceId).toBe('2');
    (instance.openReal as (event: unknown) => void)({ currentTarget: { dataset: { branch: '2' } } });
    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/booking-flow/booking-flow?branch_id=2&item_id=2' });
  });
});
