import { DEMO_NOTICE, slots } from '../../demo/data';
import { cancelAppointment, isDemoEnabled, listAppointments, rescheduleAppointment, setDemoEnabled } from '../../demo/store';
import type { AppointmentResult } from '../../demo/store';

function refresh(page: { setData: (data: Record<string, unknown>) => void; data: Record<string, unknown> }): void {
  const enabled = isDemoEnabled();
  const list = enabled ? listAppointments() : [];
  const selectedId = String(page.data.selectedId ?? '');
  page.setData({
    demoEnabled: enabled,
    appointments: list,
    selectedId: list.some((a) => a.id === selectedId) ? selectedId : '',
    reschedulingId: '',
  });
}

Page({
  data: {
    demoNotice: DEMO_NOTICE,
    demoEnabled: true,
    appointments: [] as ReturnType<typeof listAppointments>,
    selectedId: '',
    tab: 'upcoming',
    reschedulingId: '',
    rescheduleSlot: '',
    slots,
    message: '',
  },

  onLoad(options: { id?: string } = {}) {
    this.setData({ selectedId: options.id ?? '' });
    refresh(this);
  },

  onShow() {
    refresh(this);
  },

  choose(e: WechatMiniprogram.BaseEvent) {
    this.setData({ selectedId: String((e.currentTarget.dataset as Record<string, unknown>).id ?? ''), reschedulingId: '' });
  },
  switchTab(e: WechatMiniprogram.BaseEvent) {
    this.setData({ tab: String((e.currentTarget.dataset as Record<string, unknown>).tab ?? 'upcoming') });
  },

  cancel(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const result: AppointmentResult = cancelAppointment(id);
    this.setData({ message: result.message });
    refresh(this);
  },

  startReschedule(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const row = this.data.appointments.find((a) => a.id === id);
    if (!row) { this.setData({ message: '找不到该预约。' }); return; }
    if (row.status.includes('取消') || row.status === '已完成') {
      this.setData({ message: '已取消或已完成的预约不能改约。' });
      return;
    }
    this.setData({ reschedulingId: id, rescheduleSlot: '', message: '' });
  },
  pickRescheduleSlot(e: WechatMiniprogram.BaseEvent) {
    this.setData({ rescheduleSlot: String((e.currentTarget.dataset as Record<string, unknown>).value ?? '') });
  },
  confirmReschedule() {
    if (!this.data.reschedulingId) return;
    if (!this.data.rescheduleSlot) {
      this.setData({ message: '请选择新时段。' });
      return;
    }
    const row = this.data.appointments.find((a) => a.id === this.data.reschedulingId);
    const result = rescheduleAppointment(this.data.reschedulingId, row?.date ?? '2026-10-28', this.data.rescheduleSlot);
    this.setData({ message: result.message });
    refresh(this);
  },
  closeReschedule() {
    this.setData({ reschedulingId: '', rescheduleSlot: '' });
  },

  openBooking() {
    wx.navigateTo({ url: '/pages/booking/booking' });
  },
  goRealBooking() {
    setDemoEnabled(false);
    wx.navigateTo({ url: '/pages/real-booking/real-booking' });
  },
  backHome() {
    wx.navigateTo({ url: '/pages/home/home' });
  },
});
