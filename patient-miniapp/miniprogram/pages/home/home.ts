import { DEMO_NOTICE } from '../../demo/data';
import { isDemoEnabled, setDemoEnabled } from '../../demo/store';
import { authFlow } from '../../services/auth-flow';
import { createRealBookingApi } from '../../services/booking-api';
import type { RealAppointment } from '../../services/booking-api';
import { createPublicDirectoryApi } from '../../services/public-directory';
import type { PublicBranch, PublicDoctor } from '../../services/public-directory';
import { patientSession } from '../../services/session';

const realBooking = createRealBookingApi();
const publicDirectory = createPublicDirectoryApi();

function dateTime(value: string): { date: string; time: string } {
  const d = new Date(new Date(value).getTime() + 8 * 3600000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return { date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`, time: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}` };
}

Page({
  data: {
    demoNotice: DEMO_NOTICE,
    demoEnabled: isDemoEnabled(),
    showDemo: isDemoEnabled(),
    hasPatientSession: false,
    identityRevision: -1,
    changeRequestId: 0,
    changeLoading: false,
    changeError: '',
    changeSummary: null as null | { id: string; state: string; reason: string; pendingCount: number },
    directoryRequestId: 0,
    directoryLoading: false,
    directoryError: '',
    publicBranches: [] as PublicBranch[],
    publicDoctors: [] as PublicDoctor[],
    realRequestId: 0,
    realLoading: false,
    realError: '',
    realNext: null as null | { id: string; display: string; branch: string; dentist: string; state: string },
    realUpcomingCount: 0,
  },

  onLoad() {
    this.setData({ demoEnabled: isDemoEnabled(), showDemo: isDemoEnabled() });
  },

  onShow() {
    const demo = isDemoEnabled();
    const session = authFlow.hasSession;
    const revision = patientSession.revision;
    const identityChanged = this.data.identityRevision !== revision || this.data.hasPatientSession !== session;
    const patch: Record<string, unknown> = {
      demoEnabled: demo,
      showDemo: demo,
      hasPatientSession: session,
      identityRevision: revision,
    };
    if (demo) {
      // 演示模式只展示本地虚构样例，不保留任何真实接口结果或迟到请求。
      Object.assign(patch, {
        changeRequestId: this.data.changeRequestId + 1,
        changeSummary: null,
        changeError: '',
        changeLoading: false,
        directoryRequestId: this.data.directoryRequestId + 1,
        directoryLoading: false,
        directoryError: '',
        publicBranches: [],
        publicDoctors: [],
        realRequestId: this.data.realRequestId + 1,
        realLoading: false,
        realError: '',
        realNext: null,
        realUpcomingCount: 0,
      });
      this.setData(patch);
      return;
    }
    if (identityChanged) {
      Object.assign(patch, {
        changeSummary: null,
        changeError: '',
        realNext: null,
        realUpcomingCount: 0,
        realError: '',
      });
    }
    this.setData(patch);
    void this.loadPublicDirectory();
    if (session) void this.loadRealHome();
    else this.setData({ realLoading: false, realNext: null, realUpcomingCount: 0 });
    void this.loadChangeSummary();
  },

  onHide() {
    this.setData({
      changeRequestId: this.data.changeRequestId + 1,
      directoryRequestId: this.data.directoryRequestId + 1,
      realRequestId: this.data.realRequestId + 1,
    });
  },
  onUnload() {
    this.setData({
      changeRequestId: this.data.changeRequestId + 1,
      directoryRequestId: this.data.directoryRequestId + 1,
      realRequestId: this.data.realRequestId + 1,
    });
  },

  async loadPublicDirectory() {
    if (isDemoEnabled()) return;
    const requestId = this.data.directoryRequestId + 1;
    this.setData({ directoryRequestId: requestId, directoryLoading: true, directoryError: '' });
    try {
      const [branches, doctors] = await Promise.all([publicDirectory.branches(), publicDirectory.doctors()]);
      if (isDemoEnabled() || requestId !== this.data.directoryRequestId) return;
      this.setData({ publicBranches: branches, publicDoctors: doctors, directoryLoading: false });
    } catch (error) {
      if (isDemoEnabled() || requestId !== this.data.directoryRequestId) return;
      this.setData({
        directoryLoading: false,
        publicBranches: [],
        publicDoctors: [],
        directoryError: error instanceof Error ? error.message : '门店与医生公开资料读取失败。',
      });
    }
  },

  async loadRealHome() {
    if (isDemoEnabled() || !authFlow.hasSession) return;
    const revision = patientSession.revision;
    const requestId = this.data.realRequestId + 1;
    this.setData({ realRequestId: requestId, realLoading: true, realError: '', realNext: null, realUpcomingCount: 0 });
    try {
      const overview = await realBooking.overview();
      if (isDemoEnabled() || requestId !== this.data.realRequestId || revision !== patientSession.revision || !authFlow.hasSession) return;
      const now = Date.now();
      const active = overview.appointments
        .filter((item: RealAppointment) => ['已确认', '候诊中', '接诊中'].includes(item.state) && new Date(item.starts_at).getTime() >= now)
        .sort((a: RealAppointment, b: RealAppointment) => a.starts_at.localeCompare(b.starts_at));
      const next = active[0];
      this.setData({
        realLoading: false,
        realUpcomingCount: active.length,
        realNext: next ? {
          id: next.appointment_id,
          display: `${dateTime(next.starts_at).date} ${dateTime(next.starts_at).time} · ${next.item_name}`,
          branch: next.branch_name,
          dentist: next.dentist_name,
          state: next.state,
        } : null,
      });
    } catch (error) {
      if (isDemoEnabled() || requestId !== this.data.realRequestId || revision !== patientSession.revision) return;
      this.setData({ realLoading: false, realNext: null, realUpcomingCount: 0,
        realError: error instanceof Error ? error.message : '预约信息读取失败。' });
    }
  },

  async loadChangeSummary() {
    if (isDemoEnabled()) return;
    const requestId = this.data.changeRequestId + 1;
    const revision = authFlow.sessionRevision;
    this.setData({ changeRequestId: requestId, changeSummary: null, changeError: '', changeLoading: authFlow.hasSession });
    if (!authFlow.hasSession) return;
    try {
      const changes = await realBooking.changes();
      if (isDemoEnabled() || requestId !== this.data.changeRequestId || revision !== authFlow.sessionRevision || !authFlow.hasSession) return;
      const pending = changes.filter(change => change.state === '待答复');
      const latest = pending[0] ?? changes[0];
      this.setData({ changeLoading: false, changeSummary: latest ? {
        id: latest.change_id, state: latest.state, reason: latest.reason, pendingCount: pending.length,
      } : null });
    } catch {
      if (!isDemoEnabled() && requestId === this.data.changeRequestId && revision === authFlow.sessionRevision && authFlow.hasSession)
        this.setData({ changeLoading: false, changeError: '预约协调动态暂时无法读取，请重试。' });
    }
  },

  switchToReal() {
    setDemoEnabled(false);
    this.setData({ demoEnabled: false, showDemo: false });
    this.onShow();
    wx.showToast({ title: '已切换真实模式', icon: 'none' });
  },

  switchToDemo() {
    setDemoEnabled(true);
    this.setData({ demoEnabled: true, showDemo: true });
    this.onShow();
    wx.showToast({ title: '已切换演示模式', icon: 'none' });
  },

  openDemo(e: WechatMiniprogram.BaseEvent) {
    const key = String((e.currentTarget.dataset as Record<string, unknown>).key ?? '');
    const target: Record<string, string> = {
      appointments: '/pages/appointments/appointments',
      records: '/pages/records/records',
    };
    if (!this.data.showDemo || !target[key]) return;
    wx.navigateTo({ url: target[key] });
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/index/index' });
  },
  goClinics() {
    wx.navigateTo({ url: '/pages/clinic/clinic' });
  },
  goDoctors() {
    wx.navigateTo({ url: '/pages/doctors/doctors' });
  },
  goProfile() {
    wx.navigateTo({ url: '/pages/profile/profile' });
  },
  goPayments() {
    if (isDemoEnabled()) { this.switchToReal(); return; }
    if (!authFlow.hasSession) { this.goLogin(); return; }
    wx.navigateTo({ url: '/pages/payments/payments' });
  },
  goBookingFlow() {
    if (isDemoEnabled()) { this.switchToReal(); return; }
    wx.navigateTo({ url: '/pages/booking-flow/booking-flow' });
  },
  goMyAppointments() {
    if (isDemoEnabled()) { this.switchToReal(); return; }
    if (!authFlow.hasSession) { this.goLogin(); return; }
    wx.navigateTo({ url: '/pages/real-booking/real-booking' });
  },
  goRealBooking() {
    if (isDemoEnabled()) { this.switchToReal(); return; }
    if (!authFlow.hasSession) { this.goLogin(); return; }
    wx.navigateTo({ url: '/pages/real-booking/real-booking' });
  },
  goRealRecords() {
    if (isDemoEnabled()) { this.switchToReal(); return; }
    if (!authFlow.hasSession) { this.goLogin(); return; }
    wx.navigateTo({ url: '/pages/real-records/real-records' });
  },
});
