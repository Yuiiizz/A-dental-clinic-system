import { authFlow } from '../../services/auth-flow';
import { isDemoEnabled, setDemoEnabled } from '../../demo/store';
import { createProfileApi } from '../../services/profile-api';
import type { AllergyStatus, PatientProfile, ProfileChange } from '../../services/profile-api';
import { patientSession } from '../../services/session';
import { createHttpClient } from '../../services/http';
import { activate, contextOf, deactivate, resume, suspend } from '../../services/page-context';
import type { ActivePage } from '../../services/page-context';

const profiles = createProfileApi();
const allergyOptions: AllergyStatus[] = ['未申报', '无已知', '有', '不确定'];

Page({
  data: {
    name: '',
    identityLabel: '就诊用户',
    statusText: '正在核验…',
    message: '',
    busy: false,
    showRetry: false,
    showLogin: false,
    hasPatientSession: false,
    profile: null as PatientProfile | null,
    profileDraft: null as ProfileChange | null,
    allergyOptions,
    profileLoading: false,
    profileSaving: false,
    profileMessage: '',
    profileRequestId: 0,
    demoMode: isDemoEnabled(),
    loggingOut: false,
  },

  onLoad() {
    activate(this as unknown as ActivePage);
    if (isDemoEnabled()) {
      this.setData({ demoMode: true, statusText: '演示模式', message: '当前为演示模式；切换到真实模式后才能核验本人资料。' });
      return;
    }
    void this.handleVerify();
  },

  onShow() {
    const demoMode = isDemoEnabled();
    this.setData({ hasPatientSession: authFlow.hasSession, demoMode });
    if (demoMode) {
      this.setData({ name: '', statusText: '演示模式', message: '当前为演示模式；切换到真实模式后才能核验本人资料。', profile: null, profileDraft: null, profileRequestId: this.data.profileRequestId + 1 });
      return;
    }
    if (this.data.statusText === '演示模式') {
      this.setData({ statusText: '身份未确认', message: '' });
      void this.handleVerify();
      return;
    }
    const resumed = resume(this as unknown as ActivePage);
    if (!resumed) return;
    if (this.data.busy) {
      const hasSession = authFlow.hasSession;
      (this as unknown as { identityRevision?: number }).identityRevision = undefined;
      this.setData({
        busy: false,
        name: '',
        statusText: hasSession ? '身份未确认' : '未登录',
        message: '操作已中断，请重试身份核验。',
        showRetry: hasSession,
        showLogin: !hasSession,
      });
      return;
    }
    // 页面隐藏期间会话可能已切换；旧姓名不能跨会话展示。
    const shownRevision = (this as unknown as { identityRevision?: number }).identityRevision;
    if (shownRevision !== undefined && shownRevision !== authFlow.sessionRevision) {
      (this as unknown as { identityRevision?: number }).identityRevision = undefined;
      this.setData({
        name: '',
        statusText: '身份已变化',
        message: '',
        showRetry: false,
        showLogin: false,
        profile: null, profileDraft: null, profileMessage: '', profileRequestId: this.data.profileRequestId + 1,
      });
      void this.handleVerify();
    }
  },

  onHide() {
    this.setData({ profileRequestId: this.data.profileRequestId + 1, profileLoading: false, profileSaving: false });
    const ctx = contextOf(this as unknown as ActivePage);
    if (this.data.busy) authFlow.cancelPending(ctx);
    suspend(this as unknown as ActivePage);
  },

  onUnload() {
    this.setData({ profileRequestId: this.data.profileRequestId + 1 });
    const ctx = contextOf(this as unknown as ActivePage);
    if (this.data.busy) authFlow.cancelPending(ctx);
    deactivate(this as unknown as ActivePage);
  },

  async handleVerify() {
    if (isDemoEnabled()) { this.setData({ demoMode: true, statusText: '演示模式', message: '当前为演示模式；切换到真实模式后才能核验本人资料。' }); return; }
    if (this.data.busy) return;
    // 核验开始即清除旧的已确认姓名，避免网络失败后继续展示旧身份。
    this.setData({ busy: true, name: '', message: '', showRetry: false, showLogin: false,
      profile: null, profileDraft: null, profileRequestId: this.data.profileRequestId + 1 });
    (this as unknown as { identityRevision?: number }).identityRevision = undefined;
    const ctx = contextOf(this as unknown as ActivePage);
    const result = await authFlow.verifySession(ctx);
    if (!ctx.active()) return;
    if (result.status === 'session' && result.me) {
      (this as unknown as { identityRevision?: number }).identityRevision = authFlow.sessionRevision;
      this.setData({
        busy: false,
        name: result.me.name,
        identityLabel: '就诊用户',
        statusText: '个人信息已核实',
        message: '',
      });
      void this.loadProfile();
      return;
    }
    if (result.status === 'unverified' || result.status === 'error') {
      this.setData({
        busy: false,
        name: '',
        statusText: '身份未确认',
        message: result.message,
        showRetry: true,
      });
      return;
    }
    if (result.status === 'forbidden') {
      this.setData({
        busy: false,
        name: '',
        statusText: '访问被拒',
        message: result.message,
        showRetry: false,
        showLogin: false,
      });
      return;
    }
    this.setData({
      busy: false,
      name: '',
      statusText: result.status === 'anonymous' ? '未登录' : '需要重新登录',
      message: result.message,
      showLogin: true,
    });
  },

  async loadProfile() {
    if (!authFlow.hasSession) return;
    const requestId = this.data.profileRequestId + 1;
    const revision = patientSession.revision;
    const ctx = contextOf(this as unknown as ActivePage);
    this.setData({ profileRequestId: requestId, profileLoading: true, profileMessage: '' });
    try {
      const value = await profiles.get();
      if (!ctx.active() || requestId !== this.data.profileRequestId || revision !== patientSession.revision) return;
      this.setData({ profile: value, profileDraft: {
        phone: value.phone, email: value.email ?? '', emergency_name: value.emergency_name ?? '',
        emergency_phone: value.emergency_phone ?? '', allergy_status: value.allergy_status,
        allergy_notes: value.allergy_notes ?? '', expected_version: value.version,
      }, profileLoading: false });
    } catch {
      if (ctx.active() && requestId === this.data.profileRequestId && revision === patientSession.revision)
        this.setData({ profileLoading: false, profileMessage: '个人资料读取失败，请重试。' });
    }
  },

  editProfileField(e: WechatMiniprogram.BaseEvent) {
    const field = String((e.currentTarget.dataset as Record<string, unknown>).field ?? '');
    if (!['phone','email','emergency_name','emergency_phone','allergy_notes'].includes(field) || !this.data.profileDraft) return;
    const value = String((e as unknown as { detail: { value: unknown } }).detail.value ?? '');
    this.setData({ profileDraft: { ...this.data.profileDraft, [field]: value }, profileMessage: '' });
  },

  chooseAllergy(e: WechatMiniprogram.BaseEvent) {
    const status = allergyOptions[Number((e as unknown as { detail: { value: string } }).detail.value)];
    if (status && this.data.profileDraft) this.setData({ profileDraft: { ...this.data.profileDraft, allergy_status: status }, profileMessage: '' });
  },

  async saveProfile() {
    const draft = this.data.profileDraft;
    if (!draft || this.data.profileSaving || this.data.profileLoading) return;
    if (!draft.phone.trim() || (['有','不确定'].includes(draft.allergy_status) && !draft.allergy_notes.trim())) {
      this.setData({ profileMessage: '请填写电话；有过敏或不确定时还须填写说明。' }); return;
    }
    const requestId = this.data.profileRequestId + 1;
    const revision = patientSession.revision;
    const ctx = contextOf(this as unknown as ActivePage);
    this.setData({ profileSaving: true, profileRequestId: requestId, profileMessage: '' });
    try {
      const value = await profiles.update(draft);
      if (!ctx.active() || requestId !== this.data.profileRequestId || revision !== patientSession.revision) return;
      this.setData({ profile: value, profileDraft: {
        phone: value.phone, email: value.email ?? '', emergency_name: value.emergency_name ?? '',
        emergency_phone: value.emergency_phone ?? '', allergy_status: value.allergy_status,
        allergy_notes: value.allergy_notes ?? '', expected_version: value.version,
      }, profileSaving: false, profileMessage: '个人资料已更新。' });
    } catch {
      if (ctx.active() && requestId === this.data.profileRequestId && revision === patientSession.revision)
        this.setData({ profileSaving: false, profileMessage: '保存结果尚未确认，请勿重复修改内容；可刷新资料核对，或用原内容重试。' });
    }
  },

  goClinic() {
    wx.redirectTo({ url: '/pages/clinic/clinic' });
  },
  goHome() {
    wx.redirectTo({ url: '/pages/home/home' });
  },
  goAppointments() {
    if (this.data.demoMode) { wx.navigateTo({ url: '/pages/appointments/appointments' }); return; }
    wx.navigateTo({ url: this.data.hasPatientSession ? '/pages/real-booking/real-booking' : '/pages/index/index' });
  },
  goRecords() {
    if (this.data.demoMode) { wx.navigateTo({ url: '/pages/records/records' }); return; }
    wx.navigateTo({ url: this.data.hasPatientSession ? '/pages/real-records/real-records' : '/pages/index/index' });
  },
  goPayments() {
    if (this.data.demoMode) { setDemoEnabled(false); this.setData({ demoMode: false }); }
    wx.navigateTo({ url: '/pages/payments/payments' });
  },
  switchToRealMode() {
    setDemoEnabled(false);
    this.setData({ demoMode: false, statusText: '身份未确认', message: '' });
    void this.handleVerify();
    wx.showToast({ title: '已切换真实模式', icon: 'none' });
  },

  goLogin() {
    wx.reLaunch({ url: '/pages/index/index' });
  },
  async logout() {
    if (!patientSession.token || this.data.loggingOut) return;
    this.setData({ loggingOut: true, message: '' });
    const revision = patientSession.revision;
    try {
      await createHttpClient({ getBearerToken: () => patientSession.token }).post('/auth/logout', undefined);
      if (revision !== patientSession.revision) return;
      patientSession.clear();
      this.setData({ name: '', profile: null, profileDraft: null, hasPatientSession: false, loggingOut: false,
        profileRequestId: this.data.profileRequestId + 1 });
      wx.reLaunch({ url: '/pages/index/index' });
    } catch {
      if (revision === patientSession.revision) this.setData({ loggingOut: false, message: '退出登录未完成，请检查网络后重试。' });
    }
  },
});
