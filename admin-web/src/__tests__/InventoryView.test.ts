import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import InventoryView from '@/views/InventoryView.vue';
import { ApiError } from '@/services/http';

const mocks = vi.hoisted(() => ({
  route: { query: { tab: 'catalog' } as Record<string, string> },
  replace: vi.fn(),
  session: { status: 'ready', sessionRevision: 1, csrfToken: 'csrf',
    me: { account_id: '6', roles: [{ role_code: 'inventory', branch_ids: ['2'] }] }, fetchMe: vi.fn() },
  api: {
    items: vi.fn(), createItem: vi.fn(), warehouses: vi.fn(), createWarehouse: vi.fn(),
    receipts: vi.fn(), detail: vi.fn(), receive: vi.fn(), balances: vi.fn(),
  },
  suppliers: { list: vi.fn() },
}));
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ replace: mocks.replace }) }));
vi.mock('@/stores/session', () => ({ useSessionStore: () => mocks.session }));
vi.mock('@/services/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/http')>()), createHttpClient: () => ({}),
}));
vi.mock('@/services/inventory', () => ({ createInventoryApi: () => mocks.api }));
vi.mock('@/services/suppliers', () => ({ createSuppliersApi: () => mocks.suppliers }));

const item = { stock_item_id: '12', code: 'C-12', name: '教学虚拟口镜', kind: '耗材',
  specification: '一次性', base_unit: '件', storage_condition: '常温', enabled: true };
const warehouse = { warehouse_id: '7', branch_id: '2', name: '主库', enabled: true };

beforeEach(() => {
  vi.clearAllMocks();
  mocks.route.query = { tab: 'catalog' };
  mocks.session.me = { account_id: '6', roles: [{ role_code: 'inventory', branch_ids: ['2'] }] };
  mocks.api.items.mockResolvedValue({ items: [item], nextCursor: null });
  mocks.api.warehouses.mockResolvedValue({ items: [warehouse], nextCursor: null });
  mocks.api.receipts.mockResolvedValue({ items: [], nextCursor: null });
  mocks.api.balances.mockResolvedValue({ items: [], nextCursor: null });
  mocks.suppliers.list.mockResolvedValue({ items: [], nextCursor: null });
  mocks.api.createItem.mockResolvedValue(item);
});

describe('真实耗材管理页面', () => {
  it('读取当前门店的真实目录、库位和余额，不展示演示库存', async () => {
    const wrapper = mount(InventoryView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(mocks.api.warehouses).toHaveBeenCalledWith('2', undefined);
    expect(mocks.api.balances).toHaveBeenCalledWith('2', undefined);
    expect(wrapper.text()).toContain('教学虚拟口镜');
    expect(wrapper.text()).toContain('主库');
    expect(wrapper.text()).toContain('真实接口');
    wrapper.unmount();
  });

  it('无入库权限时显示只读权限提示且不渲染验收表单', async () => {
    mocks.session.me = { account_id: '6', roles: [{ role_code: 'owner', branch_ids: ['2'] }] };
    mocks.route.query = { tab: 'receiving' };
    const wrapper = mount(InventoryView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(wrapper.text()).toContain('只读权限');
    expect(wrapper.findAll('form')).toHaveLength(0);
    wrapper.unmount();
  });

  it('余额为 0 显示暂无可用，并明确不伪造低库存阈值', async () => {
    mocks.route.query = { tab: 'balances' };
    mocks.api.balances.mockResolvedValue({ items: [{ warehouse_id: '7', branch_id: '2', warehouse_name: '主库',
      batch_id: '9', batch_no: 'B-9', manufacturer: '教学虚拟厂商', expires_on: '2028-12-31', batch_state: '有效',
      stock_item_id: '12', code: 'C-12', item_name: '教学虚拟口镜', base_unit: '件', quantity: 0 }], nextCursor: null });
    const wrapper = mount(InventoryView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无可用');
    expect(wrapper.text()).toContain('低库存阈值未由接口提供');
    expect(wrapper.findAll('.balance-card')).toHaveLength(1);
    expect(wrapper.find('.permission-note').exists()).toBe(false);
    wrapper.unmount();
  });

  it('建档成功并刷新后保存按钮恢复可用', async () => {
    const wrapper = mount(InventoryView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const form = wrapper.find('form.mini-form');
    const inputs = form.findAll('input');
    await inputs[0]?.setValue('C-NEW'); await inputs[1]?.setValue('教学虚拟口镜');
    await inputs[2]?.setValue('一次性'); await inputs[3]?.setValue('件');
    await inputs[4]?.setValue('常温');
    await form.trigger('submit'); await flushPromises();
    expect(mocks.api.createItem).toHaveBeenCalledTimes(1);
    expect(mocks.api.items).toHaveBeenCalledTimes(2);
    expect(form.find('button[type="submit"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.text()).toContain('已建档；尚无库存');
    wrapper.unmount();
  });

  it('入库响应未知时保留表单与原幂等键，手动重试只过账一次', async () => {
    mocks.route.query = { tab: 'receiving' };
    mocks.suppliers.list.mockResolvedValue({ items: [{ supplier_id: '5', name: '教学虚拟供应商',
      credit_code: 'DEMO-5', valid_to: '2030-01-01', enabled: true, verified_at: '2026-01-01T00:00:00Z' }], nextCursor: null });
    const receipt = { receipt_id: '30', branch_id: '2', supplier_id: '5', document_no: 'R-30',
      supplier_document: 'S-30', received_at: '2026-11-10T00:00:00Z', accepted_by: '6', state: '已过账',
      lines: [], acquisition_cost_total: 20, cost_note: '取得成本参考' };
    mocks.api.receive.mockRejectedValueOnce(new ApiError('TIMEOUT', 0, 'timeout')).mockResolvedValueOnce(receipt);
    const wrapper = mount(InventoryView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const form = wrapper.find('form');
    const selects = form.findAll('select');
    await selects[0]?.setValue('5'); await selects[1]?.setValue('12'); await selects[2]?.setValue('7');
    const inputs = form.findAll('input');
    await inputs[0]?.setValue('R-30'); await inputs[1]?.setValue('S-30');
    await inputs[2]?.setValue('教学虚拟制造商'); await inputs[3]?.setValue('B-30');
    await inputs[4]?.setValue('2030-01-01'); await inputs[5]?.setValue('2'); await inputs[6]?.setValue('10');
    await form.trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('请求结果未确认');
    expect((inputs[0]?.element as HTMLInputElement).value).toBe('R-30');
    await form.trigger('submit'); await flushPromises();
    expect(mocks.api.receive).toHaveBeenCalledTimes(2);
    expect(mocks.api.receive.mock.calls[0]?.[1]).toBe(mocks.api.receive.mock.calls[1]?.[1]);
    expect(wrapper.text()).toContain('已过账');
    expect(form.find('button[type="submit"]').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });
});
