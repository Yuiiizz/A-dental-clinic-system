import { DEMO_NOTICE, doctors, items, slots } from '../../demo/data';
import { createAppointment, isDemoEnabled, setDemoEnabled } from '../../demo/store';

interface SubstituteRequest {
  originalDoctor: string;
  newDoctor: string;
  slot: string;
  reason: string;
}

Page({
  data: {
    demoNotice: DEMO_NOTICE,
    demoEnabled: true,
    doctors,
    items,
    slots,
    doctorId: 'DR-01',
    selectedItem: '初诊检查',
    date: '2026-10-28',
    slot: '',
    substitute: null as SubstituteRequest | null,
    substituteMessage: '',
    submitting: false,
    confirmed: false,
    resultText: '',
  },

  onLoad(options: { doctorId?: string }) {
    const doctorId = options.doctorId && doctors.some((d) => d.id === options.doctorId) ? options.doctorId : 'DR-01';
    this.setData({ doctorId, demoEnabled: isDemoEnabled() });
  },

  onShow() {
    this.setData({ demoEnabled: isDemoEnabled() });
  },

  pickDoctor(e: WechatMiniprogram.BaseEvent) {
    this.setData({ doctorId: String((e.currentTarget.dataset as Record<string, unknown>).id ?? 'DR-01'), confirmed: false, substitute: null });
  },
  pickItem(e: WechatMiniprogram.BaseEvent) {
    this.setData({ selectedItem: String((e.currentTarget.dataset as Record<string, unknown>).value ?? '初诊检查'), confirmed: false });
  },
  pickSlot(e: WechatMiniprogram.BaseEvent) {
    this.setData({ slot: String((e.currentTarget.dataset as Record<string, unknown>).value ?? ''), confirmed: false });
  },
  onDateChange(e: WechatMiniprogram.BaseEvent) {
    const detail = (e as unknown as { detail?: { value?: string } }).detail;
    this.setData({ date: detail?.value ?? '2026-10-28', confirmed: false });
  },

  proposeSubstitute() {
    if (!this.data.slot) {
      wx.showToast({ title: '请先选择时段', icon: 'none' });
      return;
    }
    const original = doctors.find((d) => d.id === this.data.doctorId) ?? doctors[0];
    const replacement = doctors.find((d) => d.id !== original.id) ?? doctors[0];
    this.setData({
      substitute: {
        originalDoctor: original.name,
        newDoctor: replacement.name,
        slot: this.data.slot,
        reason: '原医生临时停诊（演示）',
      },
      substituteMessage: '',
    });
  },
  acceptSubstitute() {
    const request = this.data.substitute;
    if (!request) return;
    const replacement = doctors.find((d) => d.name === request.newDoctor);
    this.setData({
      doctorId: replacement?.id ?? this.data.doctorId,
      substitute: null,
      substituteMessage: `已接受 ${request.newDoctor} 替诊（演示）；确认预约后才会生成记录。`,
      confirmed: false,
    });
  },
  rejectSubstitute() {
    const request = this.data.substitute;
    if (!request) return;
    this.setData({
      substitute: null,
      substituteMessage: `已拒绝 ${request.newDoctor} 替诊，原医生和原预约均未改变。`,
    });
  },

  confirm() {
    if (!this.data.demoEnabled) {
      wx.showToast({ title: '演示已关闭', icon: 'none' });
      return;
    }
    if (this.data.submitting) return;
    if (!this.data.slot) {
      wx.showToast({ title: '请选择时段', icon: 'none' });
      return;
    }
    const doctor = doctors.find((d) => d.id === this.data.doctorId);
    if (!doctor) {
      wx.showToast({ title: '医生不存在', icon: 'none' });
      return;
    }
    this.setData({ submitting: true, confirmed: false });
    const result = createAppointment({
      date: this.data.date,
      time: this.data.slot,
      clinic: doctor.clinic,
      doctor: doctor.name,
      item: this.data.selectedItem,
      source: '患者自助',
    });
    if (!result.ok) {
      this.setData({ submitting: false });
      wx.showToast({ title: result.message, icon: 'none' });
      return;
    }
    this.setData({
      submitting: false,
      confirmed: true,
      resultText: `${result.message}；编号 ${result.appointment?.id ?? ''}`,
    });
  },

  openAppointments() {
    wx.navigateTo({ url: '/pages/appointments/appointments' });
  },
  goRealBooking() {
    setDemoEnabled(false);
    wx.navigateTo({ url: '/pages/real-booking/real-booking' });
  },
  backHome() {
    wx.navigateTo({ url: '/pages/home/home' });
  },
});
