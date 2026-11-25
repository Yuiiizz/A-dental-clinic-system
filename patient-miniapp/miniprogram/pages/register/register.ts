import { authFlow } from '../../services/auth-flow';
import { activate, contextOf, deactivate, resume, suspend } from '../../services/page-context';
import type { ActivePage } from '../../services/page-context';
import type { AllergyStatus, PatientRegistrationForm } from '../../services/auth-types';

interface InputEvent {
  detail: { value: string };
}

interface PickerEvent {
  detail: { value: string };
}

const ALLERGY_OPTIONS: AllergyStatus[] = ['未申报', '无已知', '有', '不确定'];

Page({
  data: {
    name: '',
    phone: '',
    birthDate: '',
    allergyIndex: 0,
    allergyOptions: ALLERGY_OPTIONS,
    allergyNotes: '',
    notesRequired: false,
    fieldErrors: { name: '', phone: '', allergyNotes: '' },
    focusedField: '',
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

  onNameInput(event: InputEvent) {
    this.setData({ name: event.detail.value, 'fieldErrors.name': '' });
  },

  onPhoneInput(event: InputEvent) {
    this.setData({ phone: event.detail.value, 'fieldErrors.phone': '' });
  },

  onBirthChange(event: PickerEvent) {
    this.setData({ birthDate: event.detail.value });
  },

  onAllergyChange(event: PickerEvent) {
    const allergyIndex = Number(event.detail.value);
    const notesRequired = ALLERGY_OPTIONS[allergyIndex] === '有' || ALLERGY_OPTIONS[allergyIndex] === '不确定';
    this.setData({ allergyIndex, notesRequired, ...(notesRequired ? {} : { 'fieldErrors.allergyNotes': '' }) });
  },

  onFieldFocus(event: WechatMiniprogram.BaseEvent) {
    this.setData({ focusedField: String((event.currentTarget.dataset as Record<string, unknown>).field ?? '') });
  },
  onFieldBlur() {
    this.setData({ focusedField: '' });
  },

  validateFields(): boolean {
    const name = this.data.name.trim();
    const phone = this.data.phone.trim();
    const allergy = ALLERGY_OPTIONS[this.data.allergyIndex] ?? '未申报';
    const notes = this.data.allergyNotes.trim();
    const errors = { name: '', phone: '', allergyNotes: '' };
    if (!name) errors.name = '请填写本人姓名。';
    else if (name.length > 60) errors.name = '姓名不能超过 60 个字符。';
    if (!phone) errors.phone = '请填写本人联系电话。';
    else if (phone.length > 30) errors.phone = '联系电话不能超过 30 个字符。';
    if ((allergy === '有' || allergy === '不确定') && !notes) errors.allergyNotes = '选择“有”或“不确定”时，请填写过敏说明。';
    this.setData({ fieldErrors: errors });
    return !errors.name && !errors.phone && !errors.allergyNotes;
  },

  onNotesInput(event: InputEvent) {
    this.setData({ allergyNotes: event.detail.value, 'fieldErrors.allergyNotes': '' });
  },

  async handleSubmit() {
    if (this.data.busy || !this.data.canSubmit) return;
    if (!this.validateFields()) {
      this.setData({ message: '请先检查标红的字段。' });
      wx.showToast({ title: '请检查必填项', icon: 'none' });
      return;
    }
    const form: PatientRegistrationForm = {
      name: this.data.name,
      phone: this.data.phone,
      birth_date: this.data.birthDate || undefined,
      allergy_status: ALLERGY_OPTIONS[this.data.allergyIndex] ?? '未申报',
      allergy_notes: this.data.allergyNotes || undefined,
    };
    this.setData({ busy: true, message: '', showRecheck: false });
    const ctx = contextOf(this as unknown as ActivePage);
    const result = await authFlow.register(ctx, form);
    if (!ctx.active()) return;
    if (result.status === 'session') {
      this.setData({ busy: false, message: '本人建档已完成。' });
      this.finishSession();
      return;
    }
    if (result.status === 'unknown') {
      // 不重发旧注册凭证；重新取 wx.login code 核对是否已绑定。
      this.setData({ busy: true, message: '结果未确认，正在重新登录核对…', showRecheck: false });
      const recovered = await authFlow.recheck(ctx);
      if (!ctx.active()) return;
      if (recovered.status === 'session') {
        this.setData({ busy: false, message: '重新登录后确认档案已绑定。' });
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
      canSubmit: result.status === 'validation',
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
