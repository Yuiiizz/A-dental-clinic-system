import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import RealDashboardView from '@/views/RealDashboardView.vue';
import { useSessionStore } from '@/stores/session';
import { ApiError } from '@/services/http';

const mock = vi.hoisted(() => ({ listForDate: vi.fn() }));
vi.mock('@/services/appointments', () => ({ createAppointmentApi: () => mock }));

function ready(name: string) {
  const session = useSessionStore();
  session.me = { account_id: name, name, identity_type: 'staff', must_change_password: false,
    roles: [{ role_code: 'frontdesk', branch_ids: ['1'] }] };
  session.status = 'ready';
  return session;
}
function row(name: string) {
  return { appointment_id: name, patient_name: name, patient_id: '1', branch_name: '教学门店',
    starts_at: new Date().toISOString(), dentist_name: '李医生', item_name: '初诊', state: '已确认' };
}

beforeEach(() => {
  setActivePinia(createPinia());
  mock.listForDate.mockReset();
  sessionStorage.clear();
});

describe('真实工作台首页', () => {
  it('前台仅从按日期的真实预约接口读取，不显示演示统计', async () => {
    ready('前台甲');
    mock.listForDate.mockResolvedValue([row('教学用户甲')]);
    const wrapper = mount(RealDashboardView, { props: { shortcuts: [{ key: 'appointment-workbench', label: '前台预约工作台' }] } });
    await flushPromises();
    expect(mock.listForDate).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/));
    expect(wrapper.text()).toContain('教学用户甲');
    expect(wrapper.text()).toContain('今日有效预约1');
    expect(wrapper.text()).not.toContain('演示账单');
    await wrapper.find('.real-links button').trigger('click');
    expect(wrapper.emitted('open')?.[0]).toEqual(['appointment-workbench']);
    wrapper.unmount();
  });

  it('切换账号立即清旧数据，旧响应不能覆盖新账号', async () => {
    const session = ready('前台甲');
    let finishOld!: (value: unknown[]) => void;
    mock.listForDate.mockReturnValueOnce(new Promise((resolve) => { finishOld = resolve; }));
    mock.listForDate.mockResolvedValueOnce([row('教学用户乙')]);
    const wrapper = mount(RealDashboardView, { props: { shortcuts: [] } });
    session.clearSession();
    ready('前台乙');
    await flushPromises();
    expect(mock.listForDate).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('教学用户乙');
    finishOld([row('教学用户甲')]);
    await flushPromises();
    expect(wrapper.text()).not.toContain('教学用户甲');
    expect(wrapper.text()).toContain('教学用户乙');
    wrapper.unmount();
  });

  it('无预约读取权限时不调用接口；403 只清概览，不清登录态', async () => {
    const session = useSessionStore();
    session.me = { account_id: '2', name: '系统管理员', identity_type: 'staff', must_change_password: false,
      roles: [{ role_code: 'sysadmin', branch_ids: [] }] };
    session.status = 'ready';
    const wrapper = mount(RealDashboardView, { props: { shortcuts: [{ key: 'accounts', label: '账号角色' }] } });
    expect(mock.listForDate).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('当前角色不读取预约概览');
    session.me = { ...session.me, roles: [{ role_code: 'frontdesk', branch_ids: ['1'] }] };
    mock.listForDate.mockRejectedValueOnce(new ApiError('FORBIDDEN', 403, '无权访问'));
    await flushPromises();
    expect(mock.listForDate).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('无权读取预约概览');
    expect(session.status).toBe('ready');
    wrapper.unmount();
  });
});
