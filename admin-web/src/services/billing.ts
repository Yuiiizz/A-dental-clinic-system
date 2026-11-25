import { ApiError, type HttpClient } from './http';

export interface BillRow {
  bill_id: string; patient_name: string; total_cents: number; paid_cents: number; remaining_cents: number;
  status: string; version: number; visit_id: string | null; created_at: string;
}
export interface BillingOverview {
  bills: BillRow[];
  visits: Array<{ visit_id: string; patient_name: string; branch_id: string; completed_at: string }>;
  payments: Array<{ payment_id: string; bill_id: string; amount_cents: number; method: string; state: string; received_at: string }>;
  refunds: Array<{ refund_id: string; bill_id: string; payment_id: string | null; amount_cents: number; reason: string; status: string; requested_at: string; processed_at: string | null; processing_basis: string | null }>;
  invoices: Array<{ invoice_id: string; bill_id: string; title: string; amount_cents: number; status: string; requested_at: string; invoice_type: string; invoice_no: string | null; buyer_type: string; buyer_tax_no: string | null; tax_treatment: string; tax_rate_bps: number; net_amount_cents: number; tax_amount_cents: number; red_of_id: string | null; red_basis: string | null }>;
}
function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new ApiError('INVALID_RESPONSE', 0, '账单数据无法解析。');
  return value as Record<string, unknown>;
}
const text = (v: unknown): string => { if (typeof v !== 'string') throw new ApiError('INVALID_RESPONSE', 0, '账单数据无法解析。'); return v; };
const num = (v: unknown): number => { if (typeof v !== 'number') throw new ApiError('INVALID_RESPONSE', 0, '账单数据无法解析。'); return v; };
const arr = (v: unknown): unknown[] => { if (!Array.isArray(v)) throw new ApiError('INVALID_RESPONSE', 0, '账单数据无法解析。'); return v; };
function bill(v: unknown): BillRow {
  const r = record(v);
  return { bill_id: text(r['bill_id']), patient_name: text(r['patient_name']), total_cents: num(r['total_cents']), paid_cents: num(r['paid_cents']), remaining_cents: num(r['remaining_cents']), status: text(r['status']), version: num(r['version']), visit_id: r['visit_id'] == null ? null : text(r['visit_id']), created_at: text(r['created_at']) };
}
function overview(v: unknown): BillingOverview {
  const r = record(v);
  return {
    bills: arr(r['bills']).map(bill),
    visits: arr(r['visits']).map((x) => { const a = record(x); return { visit_id: text(a['visit_id']), patient_name: text(a['patient_name']), branch_id: text(a['branch_id']), completed_at: text(a['completed_at']) }; }),
    payments: arr(r['payments']).map((x) => { const a = record(x); return { payment_id: text(a['payment_id']), bill_id: text(a['bill_id']), amount_cents: num(a['amount_cents']), method: text(a['method']), state: text(a['state']), received_at: text(a['received_at']) }; }),
    refunds: arr(r['refunds']).map((x) => { const a = record(x); return { refund_id: text(a['refund_id']), bill_id: text(a['bill_id']), payment_id: a['payment_id'] == null ? null : text(a['payment_id']), amount_cents: num(a['amount_cents']), reason: text(a['reason']), status: text(a['status']), requested_at: text(a['requested_at']), processed_at: a['processed_at'] == null ? null : text(a['processed_at']), processing_basis: a['processing_basis'] == null ? null : text(a['processing_basis']) }; }),
    invoices: arr(r['invoices']).map((x) => { const a = record(x); return { invoice_id: text(a['invoice_id']), bill_id: text(a['bill_id']), title: text(a['title']), amount_cents: num(a['amount_cents']), status: text(a['status']), requested_at: text(a['requested_at']), invoice_type: text(a['invoice_type'] ?? '人工收据'), invoice_no: a['invoice_no'] == null ? null : text(a['invoice_no']), buyer_type: text(a['buyer_type'] ?? '个人'), buyer_tax_no: a['buyer_tax_no'] == null ? null : text(a['buyer_tax_no']), tax_treatment: text(a['tax_treatment'] ?? '不征税'), tax_rate_bps: num(a['tax_rate_bps'] ?? 0), net_amount_cents: num(a['net_amount_cents'] ?? a['amount_cents']), tax_amount_cents: num(a['tax_amount_cents'] ?? 0), red_of_id: a['red_of_id'] == null ? null : text(a['red_of_id']), red_basis: a['red_basis'] == null ? null : text(a['red_basis']) }; }),
  };
}
export function createBillingApi(client: HttpClient) {
  // Retain key on ambiguous failure; clear only after a confirmed success.
  const pendingPayments = new Map<string, string>();
  return {
    async overview(): Promise<BillingOverview> { const r = await client.get<unknown>('/staff/billing'); return overview(r.data); },
    async createBill(visitId: string, itemName: string, amountCents: number): Promise<BillRow> {
      const r = await client.post<unknown>('/staff/bills', { visit_id: visitId, items: [{ name: itemName, quantity: 1, unit_price_cents: amountCents }] });
      return bill(r.data);
    },
    async pay(billId: string, amountCents: number, method: string, note: string): Promise<BillRow> {
      const fingerprint = JSON.stringify([billId, amountCents, method, note]);
      const key = pendingPayments.get(fingerprint) ?? crypto.randomUUID();
      pendingPayments.set(fingerprint, key);
      const r = await client.post<unknown>(`/staff/bills/${billId}/payments`, { amount_cents: amountCents, method, note }, { idempotencyKey: key });
      const result = bill(r.data);
      pendingPayments.delete(fingerprint);
      return result;
    },
    async requestRefund(billId: string, amountCents: number, reason: string): Promise<void> {
      await client.post('/staff/refunds', { bill_id: billId, payment_id: 0, amount_cents: amountCents, reason });
    },
    async processRefund(refundId: string, decision: '已退款' | '已拒绝', basis: string): Promise<void> {
      await client.post(`/staff/refunds/${refundId}/process`, { decision, basis });
    },
    async invoice(billId: string, input: { title: string; type: string; buyerType: string; buyerTaxNo: string; treatment: string; rateBps: number; netCents: number; taxCents: number }): Promise<void> {
      await client.post('/staff/invoices', { bill_id: billId, title: input.title, invoice_type: input.type, buyer_type: input.buyerType, buyer_tax_no: input.buyerTaxNo, tax_treatment: input.treatment, tax_rate_bps: input.rateBps, net_amount_cents: input.netCents, tax_amount_cents: input.taxCents });
    },
    async issueInvoice(id: string, invoiceNo: string, basis: string): Promise<void> { await client.post(`/staff/invoices/${id}/issue`, { invoice_no: invoiceNo, basis }); },
    async voidInvoice(id: string, reason: string): Promise<void> { await client.post(`/staff/invoices/${id}/void`, { reason }); },
    async redInvoice(id: string): Promise<void> { await client.post(`/staff/invoices/${id}/red`, {}); },
  };
}
