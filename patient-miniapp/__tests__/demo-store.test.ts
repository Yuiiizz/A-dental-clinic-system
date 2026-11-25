import { beforeEach, describe, expect, it, vi } from 'vitest';

interface Store {
  isDemoEnabled: () => boolean;
  setDemoEnabled: (value: boolean) => void;
  listAppointments: () => Array<{ id: string; status: string; date: string; time: string; doctor: string }>;
  nextAppointment: () => { id: string; time: string } | null;
  createAppointment: (input: { date: string; time: string; clinic: string; doctor: string; item: string; source: string }) => { ok: boolean; message: string; appointment?: { id: string; time: string } };
  cancelAppointment: (id: string) => { ok: boolean; message: string };
  rescheduleAppointment: (id: string, date: string, time: string) => { ok: boolean; message: string };
  appointmentById: (id: string) => { status: string; time: string } | undefined;
}

let store: Store;

beforeEach(async () => {
  vi.stubGlobal('wx', {
    getStorageSync: vi.fn().mockReturnValue('1'),
    setStorageSync: vi.fn(),
  });
  vi.resetModules();
  store = (await import('../miniprogram/demo/store')) as unknown as Store;
  store.setDemoEnabled(true);
});

describe('患者端统一演示预约状态', () => {
  it('新增预约进入同一集合，首页下一次预约读取最新状态', () => {
    const result = store.createAppointment({
      date: '2026-10-27', time: '11:30', clinic: '云栖总院', doctor: '林知夏', item: '初诊检查', source: '患者自助',
    });
    expect(result.ok).toBe(true);
    expect(store.listAppointments().some((a) => a.id === result.appointment?.id)).toBe(true);
    expect(store.nextAppointment()?.id).toBe(result.appointment?.id);
  });

  it('取消和改约更新同一条记录，已取消后不能再次取消或改约', () => {
    const created = store.createAppointment({
      date: '2026-10-27', time: '12:00', clinic: '云栖总院', doctor: '周屿', item: '正畸复诊', source: '患者自助',
    });
    const id = created.appointment!.id;
    expect(store.rescheduleAppointment(id, '2026-10-28', '14:30').ok).toBe(true);
    expect(store.appointmentById(id)?.time).toBe('14:30');
    expect(store.cancelAppointment(id).ok).toBe(true);
    expect(store.appointmentById(id)?.status).toContain('已取消');
    expect(store.cancelAppointment(id).ok).toBe(false);
    expect(store.rescheduleAppointment(id, '2026-10-29', '09:00').ok).toBe(false);
  });

  it('关闭演示后不能创建演示预约', () => {
    store.setDemoEnabled(false);
    expect(store.isDemoEnabled()).toBe(false);
    expect(store.createAppointment({
      date: '2026-10-29', time: '09:00', clinic: '云栖总院', doctor: '林知夏', item: '初诊检查', source: '患者自助',
    })).toMatchObject({ ok: false });
  });
});
