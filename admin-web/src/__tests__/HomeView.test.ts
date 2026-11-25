import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ApiError } from '@/services/http';

const mocks = vi.hoisted(() => ({
  state: {
    me: null as null | Record<string, unknown>,
    logoutSubmitting: false,
    authSubmitting: false,
    isSysadmin: false,
  },
  logout: vi.fn(),
  clearSession: vi.fn(),
  replace: vi.fn(),
  push: vi.fn(),
}));

vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    get me() {
      return mocks.state.me;
    },
    get logoutSubmitting() {
      return mocks.state.logoutSubmitting;
    },
    get authSubmitting() {
      return mocks.state.authSubmitting;
    },
    get isSysadmin() {
      return mocks.state.isSysadmin;
    },
    logout: mocks.logout,
    clearSession: mocks.clearSession,
  }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace, push: mocks.push }),
}));

import HomeView from '@/views/HomeView.vue';

const identity = {
  account_id: '42',
  name: '张三',
  identity_type: 'staff',
  roles: [{ role_code: 'frontdesk', branch_ids: ['B1', 'B2'] }],
  must_change_password: false,
};

describe('HomeView', () => {
  beforeEach(() => {
    mocks.state.me = null;
    mocks.state.logoutSubmitting = false;
    mocks.state.authSubmitting = false;
    mocks.state.isSysadmin = false;
    mocks.logout.mockReset();
    mocks.clearSession.mockReset();
    mocks.replace.mockReset();
    mocks.push.mockReset();
  });

  it('展示当前身份、角色与门店范围', () => {
    mocks.state.me = identity;
    const wrapper = mount(HomeView);
    const text = wrapper.text();
    expect(text).toContain('张三');
    expect(text).toContain('42');
    expect(text).toContain('前台');
    expect(text).toContain('B1、B2');
  });

  it('无角色时显示“无角色”，不伪造业务统计', () => {
    mocks.state.me = { ...identity, roles: [] };
    const wrapper = mount(HomeView);
    expect(wrapper.text()).toContain('无角色');
    expect(wrapper.text()).not.toContain('统计');
    expect(wrapper.text()).not.toContain('今日预约');
  });

  it('退出成功后才跳转登录页', async () => {
    mocks.state.me = identity;
    mocks.logout.mockResolvedValue({ executed: true, superseded: false, status: 'guest', ok: true });
    const wrapper = mount(HomeView);

    await wrapper.find('.logout').trigger('click');
    await flushPromises();

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
    expect(wrapper.find('.error').exists()).toBe(false);
  });

  it('退出未执行（busy）时不跳转', async () => {
    mocks.state.me = identity;
    mocks.logout.mockResolvedValue({ executed: false, superseded: false, status: 'ready', reason: 'busy' });
    const wrapper = mount(HomeView);

    await wrapper.find('.logout').trigger('click');
    await flushPromises();

    expect(wrapper.find('.error').text()).toContain('正在处理其他认证操作');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('迟到的旧退出结果（superseded）不跳转', async () => {
    mocks.state.me = identity;
    mocks.logout.mockResolvedValue({ executed: true, superseded: true, status: 'ready' });
    const wrapper = mount(HomeView);

    await wrapper.find('.logout').trigger('click');
    await flushPromises();

    expect(wrapper.find('.error').text()).toContain('已失效');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('退出网络失败提示“退出未确认”且不跳转', async () => {
    mocks.state.me = identity;
    mocks.logout.mockRejectedValue(new ApiError('NETWORK_ERROR', 0, '网络请求失败'));
    const wrapper = mount(HomeView);

    await wrapper.find('.logout').trigger('click');
    await flushPromises();

    expect(wrapper.find('.error').text()).toContain('退出未确认');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('身份加载失败时提供重新登录入口', async () => {
    mocks.state.me = null;
    const wrapper = mount(HomeView);
    expect(wrapper.text()).toContain('身份信息加载失败');

    await wrapper.find('.logout').trigger('click');
    await flushPromises();

    expect(mocks.clearSession).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
  });

  it('仅系统管理员展示账号管理入口，点击进入账号页', async () => {
    mocks.state.me = identity;
    mocks.state.isSysadmin = true;
    const wrapper = mount(HomeView);
    const entry = wrapper.find('.accounts-entry');
    expect(entry.exists()).toBe(true);
    expect(entry.text()).toContain('账号管理');

    await entry.trigger('click');
    expect(mocks.push).toHaveBeenCalledWith({ name: 'admin-accounts' });
  });

  it('非系统管理员不展示账号管理入口', () => {
    mocks.state.me = identity;
    mocks.state.isSysadmin = false;
    const wrapper = mount(HomeView);
    expect(wrapper.find('.accounts-entry').exists()).toBe(false);
  });
});
