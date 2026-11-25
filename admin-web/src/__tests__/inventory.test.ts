import { describe, expect, it, vi } from 'vitest';
import { ApiError, type HttpClient } from '@/services/http';
import { createInventoryApi } from '@/services/inventory';
import { emptyReceipt, prepareReceipt } from '@/services/inventory-form';

const stockItem = { stock_item_id: '12', code: 'C-12', name: '教学虚拟口镜', kind: '耗材',
  specification: '一次性', base_unit: '件', storage_condition: '常温', enabled: true };
const line = { receipt_line_id: '18', line_no: 1, warehouse_id: '7', quantity: 2,
  unit_cost: 12.5, batch_id: '9', batch_no: 'B-1', manufacturer: '教学虚拟制造商',
  holder_name: null, expires_on: '2030-01-01', trace_reference: null, batch_state: '合格',
  stock_item_id: '12', item_code: 'C-12', item_name: '教学虚拟口镜', base_unit: '件' };
const receipt = { receipt_id: '3', branch_id: '2', supplier_id: '5', document_no: 'RC-1',
  supplier_document: 'DOC-1', received_at: '2026-11-10T08:00:00Z', accepted_by: '6',
  state: '已过账', lines: [line], acquisition_cost_total: 25, cost_note: '取得成本参考' };

describe('真实采购库存接口封装', () => {
  it('读取真实分页与明细，并透传批次追溯参数', async () => {
    const list = vi.fn().mockResolvedValue({ items: [receipt], nextCursor: '3' });
    const get = vi.fn().mockResolvedValue({ data: receipt });
    const api = createInventoryApi({ list, get } as unknown as HttpClient);
    expect((await api.receipts('2', undefined, '9')).items[0]?.receipt_id).toBe('3');
    expect(list).toHaveBeenCalledWith('/staff/purchase-receipts',
      { query: { branch_id: '2', cursor: undefined, batch_id: '9' } });
    expect((await api.detail('3')).lines?.[0]?.batch_id).toBe('9');
  });

  it('入库保持调用方同一幂等键，不把结果当作本地演示状态', async () => {
    const post = vi.fn().mockResolvedValue({ data: receipt });
    const api = createInventoryApi({ post } as unknown as HttpClient);
    const draft = emptyReceipt('2');
    draft.supplier_id = '5'; draft.document_no = 'RC-1'; draft.supplier_document = 'DOC-1';
    draft.lines[0] = { stock_item_id: '12', warehouse_id: '7', manufacturer: '教学虚拟制造商',
      holder_name: '', batch_no: 'B-1', expires_on: '2030-01-01', trace_reference: '', quantity: '2.000', unit_cost: '12.5000' };
    const input = prepareReceipt(draft);
    expect(input.lines[0]?.quantity).toBe(2);
    expect((await api.receive(input, 'same-key')).state).toBe('已过账');
    expect(post).toHaveBeenCalledWith('/staff/purchase-receipts', input, { idempotencyKey: 'same-key' });
  });

  it('严格拒绝非法日期、精度和空明细', () => {
    const draft = emptyReceipt('2');
    draft.supplier_id = '5'; draft.document_no = 'RC-1'; draft.supplier_document = 'DOC-1';
    draft.lines[0] = { stock_item_id: '12', warehouse_id: '7', manufacturer: 'M', holder_name: '',
      batch_no: 'B', expires_on: '2026-02-30', trace_reference: '', quantity: '1', unit_cost: '1' };
    expect(() => prepareReceipt(draft)).toThrow('有效期');
    draft.lines[0].expires_on = '2030-01-01'; draft.lines[0].unit_cost = '1.12345';
    expect(() => prepareReceipt(draft)).toThrow('单位取得成本');
    draft.lines = [];
    expect(() => prepareReceipt(draft)).toThrow('1 至 50 行');
  });

  it('拒绝畸形响应，避免把未过账记录展示为成功', async () => {
    const list = vi.fn().mockResolvedValue({ items: [{ ...stockItem, enabled: 'true' }], nextCursor: null });
    const post = vi.fn().mockResolvedValue({ data: { ...receipt, state: '草稿' } });
    const api = createInventoryApi({ list, post } as unknown as HttpClient);
    await expect(api.items()).rejects.toBeInstanceOf(ApiError);
    await expect(api.receive({ branch_id: '2', supplier_id: '5', document_no: 'X', supplier_document: 'Y', lines: [] }, 'k'))
      .rejects.toBeInstanceOf(ApiError);
  });

  it('批次余额解析账面数量及库位批次标识', async () => {
    const balance = { warehouse_id: '7', branch_id: '2', warehouse_name: '主库', batch_id: '9',
      batch_no: 'B-1', manufacturer: '教学虚拟制造商', expires_on: '2030-01-01', batch_state: '合格',
      stock_item_id: '12', code: 'C-12', item_name: '教学虚拟口镜', base_unit: '件', quantity: 2 };
    const list = vi.fn().mockResolvedValue({ items: [balance], nextCursor: null });
    const api = createInventoryApi({ list } as unknown as HttpClient);
    expect((await api.balances('2')).items[0]?.quantity).toBe(2);
  });
});
