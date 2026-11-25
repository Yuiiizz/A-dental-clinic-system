import { authFlow } from '../../services/auth-flow';
import { setDemoEnabled } from '../../demo/store';
import { activate, contextOf, deactivate, resume, suspend } from '../../services/page-context';
import type { ActivePage } from '../../services/page-context';

Page({
  data: {
    statusText: '未登录',
    busy: false,
    message: '',
    showRetry: false,
    showRegistration: false,
    showRegistrationGuide: false,
    returnTo: '',
  },

  onLoad(options: Record<string, string | undefined> = {}) {
    activate(this as unknown as ActivePage);
    this.setData({ returnTo: options.returnTo === 'booking-flow' ? 'booking-flow' : '' });
    if (authFlow.hasSession) {
      this.setData({ statusText: '已登录，待核验', showRetry: true });
      return;
    }
    this.setData({ statusText: '未登录' });
  },

  onShow() {
    const resumed = resume(this as unknown as ActivePage);
    if (resumed && this.data.busy) {
      const hasSession = authFlow.hasSession;
      this.setData({
        busy: false,
        statusText: hasSession ? '已登录，待核验' : '未登录',
        message: '操作已中断，请重试。',
        showRetry: hasSession,
      });
      return;
    }
    // 只做本地状态展示；是否仍有效由服务端 /auth/me 核验，不凭本地令牌宣称已登录。
    if (!authFlow.hasSession) {
      this.setData({ statusText: '未登录' });
    }
  },

  onHide() {
    const ctx = contextOf(this as unknown as ActivePage);
    if (this.data.busy) authFlow.cancelPending(ctx);
    suspend(this as unknown as ActivePage);
  },

  onUnload() {
    const ctx = contextOf(this as unknown as ActivePage);
    if (this.data.busy) authFlow.cancelPending(ctx);
    deactivate(this as unknown as ActivePage);
  },

  async handleLogin() {
    if (this.data.busy) return;
    this.setData({ busy: true, message: '', showRetry: false, showRegistration: false });
    const ctx = contextOf(this as unknown as ActivePage);
    const result = await authFlow.login(ctx);
    if (!ctx.active()) return;
    if (result.status === 'session') {
      this.setData({ statusText: '已登录', busy: false });
      this.finishSession();
      return;
    }
    if (result.status === 'registration') {
      this.setData({
        statusText: '待完善身份',
        busy: false,
        message: result.message,
        showRegistration: true,
        showRegistrationGuide: true,
      });
      return;
    }
    if (result.status === 'unverified') {
      this.setData({ statusText: '身份未确认', busy: false, message: result.message, showRetry: true });
      return;
    }
    if (result.status === 'forbidden') {
      this.setData({ statusText: '访问被拒', busy: false, message: result.message, showRetry: false });
      return;
    }
    this.setData({
      statusText: '未登录',
      busy: false,
      message: result.message,
      showRetry: result.status === 'error' || result.status === 'wechat-unavailable',
    });
  },

  finishSession() {
    if (this.data.returnTo === 'booking-flow') {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.reLaunch({ url: '/pages/profile/profile' });
  },

  goDemo() {
    setDemoEnabled(true);
    wx.navigateTo({ url: '/pages/home/home' });
  },

  async handleRetry() {
    if (this.data.busy) return;
    // 无 Bearer（登录网络失败）重做微信登录；已有 Bearer 但未核验才重试 /auth/me。
    if (!authFlow.hasSession) {
      await this.handleLogin();
      return;
    }
    this.setData({ busy: true, message: '', showRetry: false });
    const ctx = contextOf(this as unknown as ActivePage);
    const result = await authFlow.verifySession(ctx);
    if (!ctx.active()) return;
    if (result.status === 'session') {
      this.setData({ statusText: '已登录', busy: false });
      this.finishSession();
      return;
    }
    if (result.status === 'forbidden') {
      this.setData({ statusText: '访问被拒', busy: false, message: result.message, showRetry: false });
      return;
    }
    this.setData({
      statusText: result.status === 'unverified' ? '身份未确认' : '未登录',
      busy: false,
      message: result.message,
      showRetry: result.status === 'unverified' || result.status === 'error',
    });
  },

  goRegister() {
    if (!authFlow.hasRegistration) {
      this.setData({ message: '请先完成微信登录，再继续本人建档。' });
      return;
    }
    this.setData({ showRegistrationGuide: false });
    wx.navigateTo({ url: `/pages/register/register${this.data.returnTo === 'booking-flow' ? '?returnTo=booking-flow' : ''}` });
  },

  goBind() {
    if (!authFlow.hasRegistration) {
      this.setData({ message: '请先完成微信登录，再使用前台交付的绑定凭证。' });
      return;
    }
    this.setData({ showRegistrationGuide: false });
    wx.navigateTo({ url: `/pages/bind/bind${this.data.returnTo === 'booking-flow' ? '?returnTo=booking-flow' : ''}` });
  },
  closeRegistrationGuide() {
    this.setData({ showRegistrationGuide: false });
  },
  openRegistrationGuide() {
    if (authFlow.hasRegistration) this.setData({ showRegistrationGuide: true });
  },
});
