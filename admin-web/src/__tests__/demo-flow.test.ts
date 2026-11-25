import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { mount } from '@vue/test-utils';
import WorkspaceView from '@/views/WorkspaceView.vue';
import {
  addDemoAppointment, addDemoPlan, addDemoPurchase, collectDemoPayment, demoAppointments, demoClinics,
  demoDoctors, demoPatients, demoServices, demoState, formatCents, runDemoAction,
} from '@/demo/data';

const routeState = vi.hoisted(() => ({ query: {} as Record<string, string> }));
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeState.query }),
  useRouter: () => routerMock,
}));

beforeEach(() => {
  setActivePinia(createPinia());
  routeState.query = {};
  routerMock.replace.mockReset();
  routerMock.push.mockReset();
});

describe('管理端演示业务状态', () => {
  it('扩充案例的医生、患者、门店和项目引用一致', () => {
    expect(demoDoctors.length).toBeGreaterThanOrEqual(8);
    expect(new Set(demoDoctors.map((doctor) => doctor.id)).size).toBe(demoDoctors.length);
    expect(demoDoctors.every((doctor) => demoClinics.some((clinic) => clinic.id === doctor.clinicId))).toBe(true);
    expect(demoAppointments.every((appointment) =>
      demoPatients.some((patient) => patient.id === appointment.patientId)
      && demoDoctors.some((doctor) => doctor.id === appointment.doctorId && doctor.clinicId === appointment.clinicId))) .toBe(true);
    expect(demoAppointments.every((appointment) => demoServices.some((service) => service.name === appointment.item))).toBe(true);
  });

  it('前台代约的演示表单拒绝跨门店医生', () => {
    const before = demoState.appointments.length;
    const result = addDemoAppointment({ date: '2026-11-05', time: '10:00', patientId: 'PT-1005', doctorId: 'DR-05', clinicId: 'CL-02', item: '初诊检查', source: '前台代约' });
    expect(result).toMatchObject({ ok: false });
    expect(demoState.appointments).toHaveLength(before);
  });
  it('代预约写入同一预约集合，工作台和日历读取到新记录', () => {
    const result = addDemoAppointment({
      date: '2026-10-27', time: '09:30', patientId: 'PT-1005', doctorId: 'DR-04',
      clinicId: 'CL-02', item: '初诊检查', source: '前台代约',
    });
    expect(result.ok).toBe(true);
    const row = demoState.appointments.find((a) => a.id === result.id);
    expect(row).toMatchObject({ patientId: 'PT-1005', doctorId: 'DR-04', item: '初诊检查', source: '前台代约' });
    routeState.query = { module: 'calendar' };
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    expect(wrapper.text()).toContain('周舟');
    wrapper.unmount();
  });

  it('状态守卫拒绝已取消/已完成预约的签到、确认和取消', () => {
    const active = demoState.appointments.find((a) => a.status === '待确认' || a.status === '已确认')!;
    expect(active).toBeTruthy();
    expect(runDemoAction('cancel', active.id)).toContain('已模拟取消');
    const cancelled = demoState.appointments.find((a) => a.id === active.id)!;
    const completed = demoState.appointments.find((a) => a.status === '已完成');
    expect(cancelled).toBeTruthy();
    expect(completed).toBeTruthy();
    expect(runDemoAction('checkin', cancelled.id)).toContain('不能');
    expect(runDemoAction('confirm', completed!.id)).toContain('不能');
    expect(runDemoAction('cancel', completed!.id)).toContain('不能');
  });

  it('部分收款更新账单余额和关联流水，重复同键不重复计款', () => {
    const bill = demoState.bills.find((b) => b.id === 'BL-502')!;
    const first = collectDemoPayment('BL-502', '300.00', 'test-pay-key');
    expect(first.ok).toBe(true);
    expect(bill.paidCents).toBe(30000);
    expect(bill.status).toContain('部分');
    const paymentsAfterFirst = demoState.payments.filter((p) => p.billId === 'BL-502').length;
    const duplicate = collectDemoPayment('BL-502', '300.00', 'test-pay-key');
    expect(duplicate.ok).toBe(false);
    expect(demoState.payments.filter((p) => p.billId === 'BL-502').length).toBe(paymentsAfterFirst);
    const over = collectDemoPayment('BL-502', '9999.00', 'test-pay-over');
    expect(over.ok).toBe(false);
    const rest = collectDemoPayment('BL-502', formatCents(bill.totalCents - bill.paidCents), 'test-pay-rest');
    expect(rest.ok).toBe(true);
    expect(bill.paidCents).toBe(bill.totalCents);
    expect(bill.status).toContain('已收款');
    expect(collectDemoPayment('BL-502', '1.00', 'test-pay-paid')).toMatchObject({ ok: false });
  });

  it('治疗计划和采购单使用真实表单字段、数量与金额校验', () => {
    const plan = addDemoPlan('PT-1002', '牙周维护', 3, '演示计划', 'DR-01');
    expect(plan.ok).toBe(true);
    expect(demoState.plans.some((p) => p.id === plan.id && p.count === 3)).toBe(true);
    expect(addDemoPlan('PT-1002', '牙周维护', 0, '', 'DR-01')).toMatchObject({ ok: false });
    const purchase = addDemoPurchase('SP-01', 'MT-01', 4, 12800);
    expect(purchase.ok).toBe(true);
    expect(Number.isInteger(demoState.purchases.length)).toBe(true);
    expect(addDemoPurchase('SP-01', 'MT-01', 0, 12800)).toMatchObject({ ok: false });
    expect(addDemoPurchase('SP-01', 'MT-01', 1, 0)).toMatchObject({ ok: false });
  });

  it('模拟收款新增流水、更新余额，已付清账单不再收款', async () => {
    routeState.query = { module: 'bills' };
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    const row = wrapper.findAll('tbody tr').find((tr) => tr.text().includes('BL-504'))!;
    await row.findAll('button').find((b) => b.text() === '模拟收款')!.trigger('click');
    const form = wrapper.find('.dialog');
    await form.find('input').setValue('100.00');
    await form.trigger('submit');
    const bill = demoState.bills.find((b) => b.id === 'BL-504')!;
    expect(bill.paidCents).toBe(10000);
    expect(bill.status).toContain('部分');
    expect(demoState.payments.some((p) => p.billId === 'BL-504' && p.amount === '¥ 100.00')).toBe(true);
    wrapper.unmount();
  });

  it('核心表单：代预约、治疗计划、采购校验后进入各自列表', async () => {
    routeState.query = { module: 'walkin' };
    const wrapper = mount(WorkspaceView, { props: { demo: true } });
    await wrapper.findAll('button').find((b) => b.text().includes('新增代预约'))!.trigger('click');
    await wrapper.find('.business-dialog').trigger('submit');
    expect(demoState.appointments.some((a) => a.source === '前台代约' && a.patientId === 'PT-1005')).toBe(true);
    wrapper.unmount();

    routeState.query = { module: 'plans' };
    const planWrapper = mount(WorkspaceView, { props: { demo: true } });
    await planWrapper.findAll('button').find((b) => b.text().includes('新增治疗计划'))!.trigger('click');
    expect(planWrapper.find('.business-dialog').text()).toContain('计划次数');
    await planWrapper.find('.business-dialog').trigger('submit');
    expect(demoState.plans.some((p) => p.count === 4 && p.patientId === 'PT-1001')).toBe(true);
    planWrapper.unmount();

    routeState.query = { module: 'purchases' };
    const purchaseWrapper = mount(WorkspaceView, { props: { demo: true } });
    await purchaseWrapper.findAll('button').find((b) => b.text().includes('新增采购单'))!.trigger('click');
    expect(purchaseWrapper.find('.business-dialog').text()).toContain('合计');
    await purchaseWrapper.find('.business-dialog').trigger('submit');
    expect(demoState.purchases.some((p) => p.supplierId === 'SP-01')).toBe(true);
    purchaseWrapper.unmount();
  });
});
