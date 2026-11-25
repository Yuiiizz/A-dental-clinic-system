import { ApiError, type HttpClient, type PageResult } from './http';

export interface StockItem {
  stock_item_id: string; code: string; name: string; kind: '耗材';
  specification: string; base_unit: string; storage_condition: string; enabled: boolean;
}
export interface Warehouse {
  warehouse_id: string; branch_id: string; name: string; enabled: boolean;
}
export interface ReceiptLine {
  receipt_line_id: string; line_no: number; warehouse_id: string; quantity: number; unit_cost: number;
  batch_id: string; batch_no: string; manufacturer: string; holder_name: string | null;
  expires_on: string; trace_reference: string | null; batch_state: string;
  stock_item_id: string; item_code: string; item_name: string; base_unit: string;
}
export interface Receipt {
  receipt_id: string; branch_id: string; supplier_id: string; document_no: string;
  supplier_document: string; received_at: string; accepted_by: string; state: '已过账';
  lines?: ReceiptLine[]; acquisition_cost_total?: number; cost_note?: string;
}
export interface StockBalance {
  warehouse_id: string; branch_id: string; warehouse_name: string; batch_id: string;
  batch_no: string; manufacturer: string; expires_on: string; batch_state: string;
  stock_item_id: string; code: string; item_name: string; base_unit: string; quantity: number;
}
export interface ItemCreate {
  code: string; name: string; specification: string; base_unit: string; storage_condition: string;
}
export interface WarehouseCreate { branch_id: string; name: string }
export interface ReceiveLine {
  line_no: number; stock_item_id: string; warehouse_id: string; manufacturer: string;
  holder_name?: string; batch_no: string; expires_on: string; trace_reference?: string;
  quantity: number; unit_cost: number;
}
export interface ReceiveInput {
  branch_id: string; supplier_id: string; document_no: string; supplier_document: string;
  lines: ReceiveLine[];
}

function invalid(): never { throw new ApiError('INVALID_RESPONSE', 0, '库存数据格式不正确，请稍后重试。'); }
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid();
  return value as Record<string, unknown>;
}
function string(v: Record<string, unknown>, key: string): string {
  const value = v[key];
  if (typeof value !== 'string') return invalid();
  return value;
}
function id(v: Record<string, unknown>, key: string): string {
  const value = v[key];
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) return value;
  // The current backend serializes IDs in list rows as strings, but accept a safe
  // integer as well so a JDBC projection change cannot silently break list views.
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value);
  return invalid();
}
function number(v: Record<string, unknown>, key: string): number {
  const value = v[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) return invalid();
  return value;
}
function boolean(v: Record<string, unknown>, key: string): boolean {
  const value = v[key];
  if (typeof value !== 'boolean') return invalid();
  return value;
}
function nullable(v: Record<string, unknown>, key: string): string | null {
  if (v[key] === null) return null;
  return string(v, key);
}
function item(value: unknown): StockItem {
  const v = object(value);
  if (v['kind'] !== '耗材') return invalid();
  return {
    stock_item_id: id(v, 'stock_item_id'), code: string(v, 'code'), name: string(v, 'name'),
    kind: '耗材', specification: string(v, 'specification'), base_unit: string(v, 'base_unit'),
    storage_condition: string(v, 'storage_condition'), enabled: boolean(v, 'enabled'),
  };
}
function warehouse(value: unknown): Warehouse {
  const v = object(value);
  return { warehouse_id: id(v, 'warehouse_id'), branch_id: id(v, 'branch_id'),
    name: string(v, 'name'), enabled: boolean(v, 'enabled') };
}
function receiptLine(value: unknown): ReceiptLine {
  const v = object(value);
  return {
    receipt_line_id: id(v, 'receipt_line_id'), line_no: number(v, 'line_no'),
    warehouse_id: id(v, 'warehouse_id'), quantity: number(v, 'quantity'),
    unit_cost: number(v, 'unit_cost'), batch_id: id(v, 'batch_id'),
    batch_no: string(v, 'batch_no'), manufacturer: string(v, 'manufacturer'),
    holder_name: nullable(v, 'holder_name'), expires_on: string(v, 'expires_on'),
    trace_reference: nullable(v, 'trace_reference'), batch_state: string(v, 'batch_state'),
    stock_item_id: id(v, 'stock_item_id'), item_code: string(v, 'item_code'),
    item_name: string(v, 'item_name'), base_unit: string(v, 'base_unit'),
  };
}
function receipt(value: unknown): Receipt {
  const v = object(value);
  if (v['state'] !== '已过账') return invalid();
  const result: Receipt = {
    receipt_id: id(v, 'receipt_id'), branch_id: id(v, 'branch_id'),
    supplier_id: id(v, 'supplier_id'), document_no: string(v, 'document_no'),
    supplier_document: string(v, 'supplier_document'), received_at: string(v, 'received_at'),
    accepted_by: id(v, 'accepted_by'), state: '已过账',
  };
  if (v['lines'] !== undefined) {
    if (!Array.isArray(v['lines'])) return invalid();
    result.lines = v['lines'].map(receiptLine);
    result.acquisition_cost_total = number(v, 'acquisition_cost_total');
    result.cost_note = string(v, 'cost_note');
  }
  return result;
}
function balance(value: unknown): StockBalance {
  const v = object(value);
  return {
    warehouse_id: id(v, 'warehouse_id'), branch_id: id(v, 'branch_id'),
    warehouse_name: string(v, 'warehouse_name'), batch_id: id(v, 'batch_id'),
    batch_no: string(v, 'batch_no'), manufacturer: string(v, 'manufacturer'),
    expires_on: string(v, 'expires_on'), batch_state: string(v, 'batch_state'),
    stock_item_id: id(v, 'stock_item_id'), code: string(v, 'code'),
    item_name: string(v, 'item_name'), base_unit: string(v, 'base_unit'),
    quantity: number(v, 'quantity'),
  };
}
function page<T>(result: PageResult<unknown>, parse: (v: unknown) => T): PageResult<T> {
  return { items: result.items.map(parse), nextCursor: result.nextCursor };
}

export function createInventoryApi(http: HttpClient) {
  return {
    async items(cursor?: string): Promise<PageResult<StockItem>> {
      return page(await http.list<unknown>('/staff/stock-items', { query: { cursor } }), item);
    },
    async createItem(input: ItemCreate, key: string): Promise<StockItem> {
      return item((await http.post<unknown>('/staff/stock-items', input, { idempotencyKey: key })).data);
    },
    async warehouses(branchId: string, cursor?: string): Promise<PageResult<Warehouse>> {
      return page(await http.list<unknown>('/staff/warehouses',
        { query: { branch_id: branchId, cursor } }), warehouse);
    },
    async createWarehouse(input: WarehouseCreate, key: string): Promise<Warehouse> {
      return warehouse((await http.post<unknown>('/staff/warehouses', input, { idempotencyKey: key })).data);
    },
    async receipts(branchId: string, cursor?: string, batchId?: string): Promise<PageResult<Receipt>> {
      return page(await http.list<unknown>('/staff/purchase-receipts',
        { query: { branch_id: branchId, cursor, batch_id: batchId } }), receipt);
    },
    async detail(id: string): Promise<Receipt> {
      return receipt((await http.get<unknown>(`/staff/purchase-receipts/${encodeURIComponent(id)}`)).data);
    },
    async receive(input: ReceiveInput, key: string): Promise<Receipt> {
      return receipt((await http.post<unknown>('/staff/purchase-receipts', input,
        { idempotencyKey: key })).data);
    },
    async balances(branchId: string, cursor?: string): Promise<PageResult<StockBalance>> {
      return page(await http.list<unknown>('/staff/stock-balances',
        { query: { branch_id: branchId, cursor } }), balance);
    },
  };
}
