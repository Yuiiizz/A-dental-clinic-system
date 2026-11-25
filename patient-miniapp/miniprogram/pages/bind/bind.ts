import { authFlow } from '../../services/auth-flow';
import { activate, contextOf, deactivate, resume, suspend } from '../../services/page-context';
import type { ActivePage } from '../../services/page-context';

interface InputEvent {
  detail: { value: string };
}

Page({
  data: {
    bindingToken: '',
    busy: false,
    canSubmit: true,
    showForm: true,
    showRecheck: false,
    showLogin: false,
    message: '',
    returnTo: '',
  },

  onLoad(options: Record<string, string | undefined> = {}) {
    activate(this as unknown as ActivePage);
    this.setData({ returnTo: options.returnTo === 'booking-flow' ? 'booking-flow' : '' });
    if (!authFlow.hasRegistration) {
      this.setData({
        showForm: false,
        canSubmit: false,
        message: '注册上下文不存在或已失效，请返回首页重新微信登录。',
      });
    }
  },

  onShow() {
    if (resume(this as unknown as ActivePage) && this.data.busy) {
      this.setData({
        busy: false,
        canSubmit: false,
        showForm: false,
        showRecheck: true,
        message: '操作已中断；请重新微信登录核对，原凭证不会自动重发。',
      });
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

  onTokenInput(event: InputEvent) {
    this.setData({ bindingToken: event.detail.value });
  },

  async handleSubmit() {
    if (this.data.busy || !this.data.canSubmit) return;
    this.setData({ busy: true, message: '', showRecheck: false });
    const ctx = contextOf(this as unknown as ActivePage);
    const result = await authFlow.bind(ctx, this.data.bindingToken);
    if (!ctx.active()) return;
    if (result.status === 'session') {
      this.setData({ busy: false, message: '旧档绑定已完成。' });
      this.finishSession();
      return;
    }
    if (result.status === 'unknown') {
      // 不重发旧绑定凭证；重新取 wx.login code 核对是否已绑定。
      this.setData({ busy: true, message: '结果未确认，正在重新登录核对…', showRecheck: false });
      const recovered = await authFlow.recheck(ctx);
      if (!ctx.active()) return;
      if (recovered.status === 'session') {
        this.setData({ busy: false, message: '重新登录后确认旧档已绑定。' });
        this.finishSession();
        return;
      }
      if (recovered.status === 'registration') {
        this.setData({
          busy: false,
          canSubmit: true,
          showForm: true,
          showRecheck: false,
          message: recovered.message,
        });
        return;
      }
      if (recovered.status === 'session-expired') {
        this.setData({
          busy: false,
          canSubmit: false,
          showForm: false,
          showLogin: true,
          message: recovered.message,
        });
        return;
      }
      this.setData({
        busy: false,
        canSubmit: false,
        showRecheck:
          recovered.status === 'unknown' ||
          recovered.status === 'unverified' ||
          recovered.status === 'error' ||
          recovered.status === 'wechat-unavailable' ||
          recovered.status === 'rate-limited',
        message: recovered.message,
      });
      return;
    }
    if (result.status === 'unverified') {
      this.setData({ busy: false, canSubmit: false, showRecheck: true, message: result.message });
      return;
    }
    if (result.status === 'session-expired' || result.status === 'no-registration') {
      this.setData({
        busy: false,
        canSubmit: false,
        showForm: false,
        showLogin: true,
        message: result.message,
      });
      return;
    }
    this.setData({
      busy: false,
      showRecheck:
        result.status === 'error' ||
        result.status === 'wechat-unavailable' ||
        result.status === 'rate-limited',
      message: result.message,
    });
  },

  async handleRecheck() {
    if (this.data.busy) return;
    this.setData({ busy: true, message: '', showRecheck: false, showLogin: false });
    const ctx = contextOf(this as unknown as ActivePage);
    // 已有 Bearer 但 me 未确认：只重试身份核验；无 Bearer 才重新 wx.login 核对。
    const result = authFlow.hasSession
      ? await authFlow.verifySession(ctx)
      : await authFlow.recheck(ctx);
    if (!ctx.active()) return;
    if (result.status === 'session') {
      this.setData({ busy: false, message: '身份核验已完成。' });
      this.finishSession();
      return;
    }
    if (result.status === 'registration') {
      this.setData({
        busy: false,
        canSubmit: true,
        showForm: true,
        showRecheck: false,
        message: result.message,
      });
      return;
    }
    if (result.status === 'session-expired') {
      this.setData({
        busy: false,
        canSubmit: false,
        showForm: false,
        showLogin: true,
        message: result.message,
      });
      return;
    }
    this.setData({
      busy: false,
      canSubmit: false,
      showRecheck:
        result.status === 'unknown' ||
        result.status === 'unverified' ||
        result.status === 'error' ||
        result.status === 'wechat-unavailable' ||
        result.status === 'rate-limited',
      message: result.message,
    });
  },

  finishSession() {
    if (this.data.returnTo === 'booking-flow') {
      wx.navigateBack({ delta: 2 });
      return;
    }
    wx.reLaunch({ url: '/pages/profile/profile' });
  },

  goLogin() {
    wx.reLaunch({ url: '/pages/index/index' });
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },
});
