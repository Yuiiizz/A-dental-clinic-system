import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { ApiError } from '@/services/http';

const mocks = vi.hoisted(() => ({
  state: {
    status: 'guest' as string,
    loginSubmitting: false,
    authSubmitting: false,
    verificationIssue: null as 'unconfirmed' | 'non-staff' | null,
    verificationMessage: '' as string,
  },
  login: vi.fn(),
  retryVerify: vi.fn(),
  replace: vi.fn(),
}));

vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    get status() {
      return mocks.state.status;
    },
    get loginSubmitting() {
      return mocks.state.loginSubmitting;
    },
    get authSubmitting() {
      return mocks.state.authSubmitting;
    },
    get verificationIssue() {
      return mocks.state.verificationIssue;
    },
    get verificationMessage() {
      return mocks.state.verificationMessage;
    },
    login: mocks.login,
    retryVerify: mocks.retryVerify,
  }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
}));

import LoginView from '@/views/LoginView.vue';

function outcome(status: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { executed: true, superseded: false, status, ...extra };
}

describe('LoginView', () => {
  beforeEach(() => {
    mocks.state.status = 'guest';
    mocks.state.loginSubmitting = false;
    mocks.state.authSubmitting = false;
    mocks.state.verificationIssue = null;
    mocks.state.verificationMessage = '';
    mocks.login.mockReset();
    mocks.retryVerify.mockReset();
    mocks.replace.mockReset();
  });

  async function fillAndSubmit(wrapper: VueWrapper, name: string, password: string): Promise<void> {
    await wrapper.find('#login-name').setValue(name);
    await wrapper.find('#login-password').setValue(password);
    await wrapper.find('form').trigger('submit');
    await flushPromises();
  }

  it('正常登录后跳转首页', async () => {
    mocks.state.status = 'ready';
    mocks.login.mockResolvedValue(outcome('ready'));
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(mocks.login).toHaveBeenCalledWith('alice', 'secret-pass');
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'home' });
  });

  it('强制改密账号登录后跳转改密页', async () => {
    mocks.state.status = 'mustChangePassword';
    mocks.login.mockResolvedValue(outcome('mustChangePassword'));
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'bob', 'pass-12345678');

    expect(mocks.replace).toHaveBeenCalledWith({ name: 'change-password' });
  });

  it('登录失败显示中文提示并清空密码', async () => {
    mocks.login.mockRejectedValue(new ApiError('UNAUTHENTICATED', 401, '用户名或密码错误'));
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'wrong-pass');

    expect(wrapper.find('.form-error').text()).toContain('用户名或密码错误');
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('网络失败提示网络错误，不冒充登录成功', async () => {
    mocks.login.mockRejectedValue(new ApiError('NETWORK_ERROR', 0, '网络请求失败'));
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(wrapper.find('.form-error').text()).toContain('网络连接失败');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('空输入不提交，直接提示', async () => {
    const wrapper = mount(LoginView);

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(mocks.login).not.toHaveBeenCalled();
    expect(wrapper.find('.form-error').text()).toContain('请输入用户名和密码');
  });

  it('提交期间按钮禁用，重复提交不再触发', async () => {
    mocks.state.loginSubmitting = true;
    mocks.state.authSubmitting = true;
    const wrapper = mount(LoginView);

    const button = wrapper.find('.submit');
    expect((button.element as HTMLButtonElement).disabled).toBe(true);
    expect(button.text()).toContain('登录中');
  });

  it('登录后身份核验失败：显示“身份确认未完成”，不跳首页，可重试', async () => {
    mocks.login.mockImplementation(async () => {
      mocks.state.status = 'unverified';
      mocks.state.verificationIssue = 'unconfirmed';
      mocks.state.verificationMessage = '身份确认未完成，请检查网络后重试。';
      return outcome('unverified');
    });
    mocks.retryVerify.mockImplementation(async () => {
      mocks.state.status = 'ready';
      mocks.state.verificationIssue = null;
      mocks.state.verificationMessage = '';
      return outcome('ready');
    });
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(wrapper.text()).toContain('身份确认未完成');
    expect(mocks.replace).not.toHaveBeenCalled();
    const retry = wrapper.find('.verification .text-button');
    expect(retry.exists()).toBe(true);

    await retry.trigger('click');
    await flushPromises();

    expect(mocks.retryVerify).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'home' });
  });

  it('刷新恢复失败（bootstrap 后 unverified）也可在登录页重试', async () => {
    mocks.state.status = 'unverified';
    mocks.state.verificationIssue = 'unconfirmed';
    mocks.state.verificationMessage = '身份确认未完成，请检查网络后重试。';
    mocks.retryVerify.mockImplementation(async () => {
      mocks.state.status = 'mustChangePassword';
      mocks.state.verificationIssue = null;
      return outcome('mustChangePassword');
    });
    const wrapper = mount(LoginView);

    expect(wrapper.text()).toContain('身份确认未完成');
    await wrapper.find('.verification .text-button').trigger('click');
    await flushPromises();

    expect(mocks.replace).toHaveBeenCalledWith({ name: 'change-password' });
  });

  it('非员工身份不进入首页，且不提供误导性重试', async () => {
    mocks.login.mockImplementation(async () => {
      mocks.state.status = 'guest';
      mocks.state.verificationIssue = 'non-staff';
      mocks.state.verificationMessage = '当前登录身份不是员工账号，请使用员工账号登录。';
      return outcome('guest');
    });
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(wrapper.text()).toContain('不是员工账号');
    expect(wrapper.find('.verification .text-button').exists()).toBe(false);
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('认证变更互斥：登录未执行时不得跳页或宣称成功', async () => {
    mocks.login.mockResolvedValue({ executed: false, superseded: false, status: 'guest', reason: 'busy' });
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(wrapper.find('.form-error').text()).toContain('正在处理其他认证操作');
    expect(mocks.replace).not.toHaveBeenCalled();
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('');
  });

  it('迟到的旧登录结果（superseded）不按新会话跳页', async () => {
    mocks.login.mockImplementation(async () => {
      // 模拟新代次会话已就绪，但本次结果是旧代次迟到的
      mocks.state.status = 'ready';
      return { executed: true, superseded: true, status: 'ready' };
    });
    const wrapper = mount(LoginView);

    await fillAndSubmit(wrapper, 'alice', 'secret-pass');

    expect(mocks.replace).not.toHaveBeenCalled();
    expect(wrapper.find('.form-error').text()).toContain('已被新的认证操作取代');
  });
});
