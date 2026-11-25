import type { ReceiveInput, ReceiveLine } from './inventory';

export interface ReceiptLineDraft {
  stock_item_id: string; warehouse_id: string; manufacturer: string; holder_name: string;
  batch_no: string; expires_on: string; trace_reference: string; quantity: string; unit_cost: string;
}
export interface ReceiptDraft {
  branch_id: string; supplier_id: string; document_no: string; supplier_document: string;
  lines: ReceiptLineDraft[];
}
export function emptyLine(): ReceiptLineDraft {
  return { stock_item_id: '', warehouse_id: '', manufacturer: '', holder_name: '', batch_no: '',
    expires_on: '', trace_reference: '', quantity: '', unit_cost: '' };
}
export function emptyReceipt(branchId: string): ReceiptDraft {
  return { branch_id: branchId, supplier_id: '', document_no: '', supplier_document: '', lines: [emptyLine()] };
}
function id(value: string): string {
  if (!/^[1-9]\d*$/.test(value)) throw new Error('请选择有效的供应商、耗材和库位。');
  return value;
}
function decimal(value: string, whole: number, fraction: number, label: string): number {
  const normalized = value.trim();
  if (!new RegExp(`^(?:0|[1-9]\\d{0,${whole - 1}})(?:\\.\\d{1,${fraction}})?$`).test(normalized)
      || Number(normalized) <= 0) throw new Error(`${label}须大于 0，整数最多 ${whole} 位、小数最多 ${fraction} 位。`);
  return Number(normalized);
}
export function prepareReceipt(draft: ReceiptDraft): ReceiveInput {
  if (!draft.document_no.trim() || draft.document_no.trim().length > 80 ||
      !draft.supplier_document.trim() || draft.supplier_document.trim().length > 100)
    throw new Error('请填写采购单号和供应商单据，分别不超过 80、100 个字符。');
  if (draft.lines.length < 1 || draft.lines.length > 50) throw new Error('每张验收单须有 1 至 50 行。');
  const lines: ReceiveLine[] = draft.lines.map((row, index) => {
    const manufacturer = row.manufacturer.trim();
    const batchNo = row.batch_no.trim();
    if (!manufacturer || manufacturer.length > 120 || !batchNo || batchNo.length > 80)
      throw new Error(`第 ${index + 1} 行请填写生产企业和批号。`);
    if (row.holder_name.trim().length > 120 || row.trace_reference.trim().length > 300)
      throw new Error(`第 ${index + 1} 行持有人或追溯依据过长。`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(row.expires_on) ||
        Number.isNaN(Date.parse(`${row.expires_on}T00:00:00Z`)) ||
        new Date(`${row.expires_on}T00:00:00Z`).toISOString().slice(0, 10) !== row.expires_on)
      throw new Error(`第 ${index + 1} 行有效期无效。`);
    return {
      line_no: index + 1, stock_item_id: id(row.stock_item_id), warehouse_id: id(row.warehouse_id),
      manufacturer, batch_no: batchNo, expires_on: row.expires_on,
      ...(row.holder_name.trim() ? { holder_name: row.holder_name.trim() } : {}),
      ...(row.trace_reference.trim() ? { trace_reference: row.trace_reference.trim() } : {}),
      quantity: decimal(row.quantity, 11, 3, `第 ${index + 1} 行数量`),
      unit_cost: decimal(row.unit_cost, 10, 4, `第 ${index + 1} 行单位取得成本`),
    };
  });
  return { branch_id: id(draft.branch_id), supplier_id: id(draft.supplier_id),
    document_no: draft.document_no.trim(), supplier_document: draft.supplier_document.trim(), lines };
}
