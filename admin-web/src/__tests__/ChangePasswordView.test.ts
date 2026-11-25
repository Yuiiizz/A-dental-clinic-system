import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { ApiError } from '@/services/http';

const mocks = vi.hoisted(() => ({
  state: {
    status: 'mustChangePassword' as string,
    authSubmitting: false,
  },
  changePassword: vi.fn(),
  logout: vi.fn(),
  retryVerify: vi.fn(),
  clearSession: vi.fn(),
  replace: vi.fn(),
}));

vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    get status() {
      return mocks.state.status;
    },
    get authSubmitting() {
      return mocks.state.authSubmitting;
    },
    changePassword: mocks.changePassword,
    logout: mocks.logout,
    retryVerify: mocks.retryVerify,
    clearSession: mocks.clearSession,
  }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
}));

import ChangePasswordView from '@/views/ChangePasswordView.vue';

function outcome(status: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { executed: true, superseded: false, status, ...extra };
}

describe('ChangePasswordView', () => {
  beforeEach(() => {
    mocks.state.status = 'mustChangePassword';
    mocks.state.authSubmitting = false;
    mocks.changePassword.mockReset();
    mocks.logout.mockReset();
    mocks.retryVerify.mockReset();
    mocks.clearSession.mockReset();
    mocks.replace.mockReset();
  });

  async function submit(
    wrapper: VueWrapper,
    current: string,
    next: string,
    confirm: string,
  ): Promise<void> {
    await wrapper.find('#current-password').setValue(current);
    await wrapper.find('#new-password').setValue(next);
    await wrapper.find('#confirm-password').setValue(confirm);
    await wrapper.find('form').trigger('submit');
    await flushPromises();
  }

  it('新密码不足 12 个字符时不提交', async () => {
    const wrapper = mount(ChangePasswordView);
    await submit(wrapper, 'old-pass', 'short', 'short');

    expect(wrapper.find('.form-error').text()).toContain('至少需要 12 个字符');
    expect(mocks.changePassword).not.toHaveBeenCalled();
  });

  it('两次新密码不一致时不提交', async () => {
    const wrapper = mount(ChangePasswordView);
    await submit(wrapper, 'old-pass', '123456789012', '123456789013');

    expect(wrapper.find('.form-error').text()).toContain('不一致');
    expect(mocks.changePassword).not.toHaveBeenCalled();
  });

  it('改密成功后清空三个密码框并跳转首页', async () => {
    mocks.state.status = 'ready';
    mocks.changePassword.mockResolvedValue(outcome('ready', { result: 'changed' }));
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(mocks.changePassword).toHaveBeenCalledWith('old-pass', '123456789012');
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'home' });
    expect((wrapper.find('#current-password').element as HTMLInputElement).value).toBe('');
    expect((wrapper.find('#new-password').element as HTMLInputElement).value).toBe('');
    expect((wrapper.find('#confirm-password').element as HTMLInputElement).value).toBe('');
  });

  it('当前密码错误（/auth/me 确认会话有效）显示提示且不跳页', async () => {
    mocks.state.status = 'ready';
    mocks.changePassword.mockResolvedValue(outcome('ready', { result: 'password-rejected' }));
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'wrong-old', '123456789012', '123456789012');

    expect(wrapper.find('.form-error').text()).toContain('当前密码不正确');
    expect((wrapper.find('#current-password').element as HTMLInputElement).value).toBe('');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('改密 401 且 /auth/me 确认已失效：要求重新登录', async () => {
    mocks.state.status = 'guest';
    mocks.changePassword.mockResolvedValue(outcome('guest', { result: 'session-invalid' }));
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.find('.relogin').exists()).toBe(true);
    expect(wrapper.text()).toContain('登录已失效');

    await wrapper.find('.relogin .submit').trigger('click');
    await flushPromises();

    expect(mocks.clearSession).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
  });

  it('改密结果 unconfirmed：保留表单并提示重试身份确认', async () => {
    mocks.state.status = 'unverified';
    mocks.changePassword.mockResolvedValue(outcome('unverified', { result: 'unconfirmed' }));
    mocks.retryVerify.mockImplementation(async () => {
      mocks.state.status = 'ready';
      return outcome('ready');
    });
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.text()).toContain('身份确认未完成');
    expect(mocks.replace).not.toHaveBeenCalled();
    const retry = wrapper.find('.auth-form > .submit[type="button"]');
    expect(retry.exists()).toBe(true);

    await retry.trigger('click');
    await flushPromises();

    expect(mocks.retryVerify).toHaveBeenCalledTimes(1);
    // 只是探测出会话有效，密码是否已改仍未知：不冒充改密成功。
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请重新提交修改密码');
  });

  it('改密已成功但身份核验失败：重试确认后才进入首页', async () => {
    mocks.state.status = 'unverified';
    mocks.changePassword.mockResolvedValue(outcome('unverified', { result: 'changed' }));
    mocks.retryVerify.mockImplementation(async () => {
      mocks.state.status = 'ready';
      return outcome('ready');
    });
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.text()).toContain('密码已更新');
    expect(mocks.replace).not.toHaveBeenCalled();

    await wrapper.find('.auth-form > .submit[type="button"]').trigger('click');
    await flushPromises();

    expect(mocks.replace).toHaveBeenCalledWith({ name: 'home' });
  });

  it('改密未执行（busy）不跳页、不冒充成功', async () => {
    mocks.changePassword.mockResolvedValue({ executed: false, superseded: false, status: 'mustChangePassword', reason: 'busy' });
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.find('.form-error').text()).toContain('正在处理其他认证操作');
    expect(mocks.replace).not.toHaveBeenCalled();
    expect((wrapper.find('#current-password').element as HTMLInputElement).value).toBe('');
  });

  it('迟到的旧改密结果（superseded）不跳页', async () => {
    mocks.state.status = 'ready';
    mocks.changePassword.mockResolvedValue({ executed: true, superseded: true, status: 'ready', result: 'changed' });
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.find('.form-error').text()).toContain('已被新的认证操作取代');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('改密提交中退出按钮禁用，不会并发发起退出', async () => {
    mocks.state.authSubmitting = true;
    const wrapper = mount(ChangePasswordView);

    const logout = wrapper.find('.text-button');
    expect((logout.element as HTMLButtonElement).disabled).toBe(true);
    await logout.trigger('click');
    await flushPromises();

    expect(mocks.logout).not.toHaveBeenCalled();
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('强制改密页可退出登录，服务端成功后才跳转', async () => {
    mocks.state.status = 'guest';
    mocks.logout.mockResolvedValue(outcome('guest', { ok: true }));
    const wrapper = mount(ChangePasswordView);

    await wrapper.find('.text-button').trigger('click');
    await flushPromises();

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
  });

  it('退出未确认时不跳转并提示重试', async () => {
    mocks.logout.mockRejectedValue(new ApiError('NETWORK_ERROR', 0, '网络请求失败'));
    const wrapper = mount(ChangePasswordView);

    await wrapper.find('.text-button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('退出未确认');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('退出未执行（busy）时不跳转', async () => {
    mocks.logout.mockResolvedValue({ executed: false, superseded: false, status: 'mustChangePassword', reason: 'busy' });
    const wrapper = mount(ChangePasswordView);

    await wrapper.find('.text-button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('正在处理其他认证操作');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('CSRF 缺失时要求重新登录，不杜撰获取令牌', async () => {
    mocks.changePassword.mockRejectedValue(new ApiError('CSRF_MISSING', 0, '会话安全令牌缺失'));
    const wrapper = mount(ChangePasswordView);

    await submit(wrapper, 'old-pass', '123456789012', '123456789012');

    expect(wrapper.find('.relogin').exists()).toBe(true);
    expect(wrapper.text()).toContain('重新登录');

    await wrapper.find('.relogin .submit').trigger('click');
    await flushPromises();

    expect(mocks.clearSession).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
  });
});
