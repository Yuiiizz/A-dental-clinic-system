import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { mount } from '@vue/test-utils';
import WorkspaceView from '@/views/WorkspaceView.vue';
import { useSessionStore } from '@/stores/session';

const routeState = vi.hoisted(() => ({ query: {} as Record<string, string> }));
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeState.query }),
  useRouter: () => routerMock,
}));

beforeEach(() => {
  setActivePinia(createPinia());
  sessionStorage.clear();
  routeState.query = {};
  routerMock.replace.mockReset();
  routerMock.push.mockReset();
});

describe('管理端完整工作台', () => {
  it('窄屏导航以抽屉打开和关闭，不把工作区推到大段导航之后', async () => {
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    expect(wrapper.find('.mobile-menu').exists()).toBe(true);
    await wrapper.find('.mobile-menu').trigger('click');
    expect(wrapper.find('.sidebar').classes()).toContain('open');
    expect(wrapper.find('.nav-overlay').exists()).toBe(true);
    await wrapper.find('.nav-overlay').trigger('click');
    expect(wrapper.find('.sidebar').classes()).not.toContain('open');
    wrapper.unmount();
  });

  it('演示模式展示完整侧栏、指标和今日预约', () => {
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    expect(wrapper.text()).toContain('经营工作台');
    expect(wrapper.text()).toContain('预约接诊');
    expect(wrapper.text()).toContain('诊疗与收费');
    expect(wrapper.text()).toContain('今日预约');
    expect(wrapper.text()).toContain('演示数据');
    expect(wrapper.text()).toContain('张岚');
    wrapper.unmount();
  });

  it('演示模块可切换、搜索、执行本地操作并显示未写入提示', async () => {
    routeState.query = { module: 'appointments' };
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    expect(wrapper.text()).toContain('预约列表');
    const row = wrapper.findAll('tbody tr').find((tr) => tr.text().includes('AP-2602') || tr.text().includes('正畸复诊'));
    expect(row).toBeTruthy();
    const checkin = row!.findAll('button').find((button) => button.text() === '签到');
    expect(checkin).toBeTruthy();
    await checkin!.trigger('click');
    expect(wrapper.text()).toContain('未写入后台');
    const search = wrapper.find('.module-toolbar input');
    await search.setValue('不存在的患者');
    expect(wrapper.text()).toContain('没有匹配');
    wrapper.unmount();
  });

  it('未开启演示时不展示样例数据，开启后才展示', async () => {
    const wrapper = mount(WorkspaceView);
    expect(wrapper.text()).toContain('快捷入口');
    expect(wrapper.text()).not.toContain('张岚');
    const enable = wrapper.find('input[type="checkbox"]');
    expect(enable.exists()).toBe(true);
    await enable.setValue(true);
    expect(wrapper.text()).toContain('今日预约');
    expect(wrapper.text()).toContain('演示数据');
    wrapper.unmount();
  });

  it('正式模式不读取遗留演示开关', () => {
    vi.stubEnv('DEV', false);
    sessionStorage.setItem('a-clinic.admin.demo', '1');
    try {
      const wrapper = mount(WorkspaceView);
      expect(wrapper.text()).toContain('快捷入口');
      expect(wrapper.text()).not.toContain('张岚');
      expect(wrapper.text()).not.toContain('演示模式未开启');
      expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0);
      wrapper.unmount();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('有会话时按角色收敛导航，不把 owner 能力给前台', () => {
    const s = useSessionStore();
    s.status = 'ready';
    s.me = { account_id: '2', name: '前台', identity_type: 'staff', must_change_password: false, roles: [{ role_code: 'frontdesk', branch_ids: ['1'] }] };
    const wrapper = mount(WorkspaceView);
    expect(wrapper.text()).toContain('前台预约工作台');
    expect(wrapper.find('.sidebar').text()).toContain('预约列表');
    expect(wrapper.text()).not.toContain('采购验收');
    expect(wrapper.find('.sidebar').text()).not.toContain('机构门店');
    wrapper.unmount();
  });

  it('负责人真实首页不展示系统管理员、沟通及采购验收入口', () => {
    const s = useSessionStore();
    s.status = 'ready';
    s.me = { account_id: '3', name: '负责人', identity_type: 'staff', must_change_password: false,
      roles: [{ role_code: 'owner', branch_ids: ['1'] }] };
    const wrapper = mount(WorkspaceView);
    expect(wrapper.find('.sidebar').text()).toContain('机构门店');
    expect(wrapper.find('.sidebar').text()).not.toContain('账号角色');
    expect(wrapper.find('.sidebar').text()).not.toContain('店内沟通');
    expect(wrapper.find('.sidebar').text()).not.toContain('采购验收');
    wrapper.unmount();
  });

  it('当前页会话失效后隐藏真实业务入口并提供重新登录', async () => {
    const s = useSessionStore();
    s.status = 'ready';
    s.me = { account_id: '2', name: '前台', identity_type: 'staff', must_change_password: false,
      roles: [{ role_code: 'frontdesk', branch_ids: ['1'] }] };
    const wrapper = mount(WorkspaceView);
    s.clearSession();
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.sidebar').text()).not.toContain('前台预约工作台');
    expect(wrapper.text()).toContain('登录状态未确认');
    const login = wrapper.findAll('button').find((button) => button.text() === '前往登录');
    await login!.trigger('click');
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'login' });
    wrapper.unmount();
  });

  it('真实入口不冒充演示模块', async () => {
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    const branches = wrapper.findAll('button').find((button) => button.text().includes('机构门店'));
    expect(branches!.text()).toContain('真实');
    await branches!.trigger('click');
    expect(routerMock.push).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('需要员工登录');
    const appointments = wrapper.findAll('button').find((button) => button.text().includes('预约列表'));
    expect(appointments!.text()).toContain('真实');
    wrapper.unmount();
  });
});
