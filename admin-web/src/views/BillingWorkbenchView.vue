<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { createBillingApi, type BillingOverview, type BillRow } from '@/services/billing';

const session = useSessionStore();
const api = createBillingApi(createHttpClient({ baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1', timeoutMs: 30000, getCsrfToken: () => session.csrfToken }));
const data = ref<BillingOverview | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');
const form = ref({ visitId: '', itemName: '牙周维护', amount: '500.00' });
const payForm = ref({ bill: null as BillRow | null, amount: '', method: '现金', note: '' });
const invoiceForm = ref({ bill: null as BillRow | null, type: '人工收据', buyerType: '个人', treatment: '不征税', title: '个人', taxNo: '', rate: 0, net: '', tax: '0.00' });
const invoiceAction = ref<{ id: string; mode: 'issue' | 'void'; value: string; basis: string; red: boolean } | null>(null);
const refundForm = ref<{ bill: BillRow; amount: string; reason: string } | null>(null);
const refundAction = ref<{ id: string; decision: '已退款' | '已拒绝'; basis: string } | null>(null);
const bills = computed(() => data.value?.bills ?? []);
const visits = computed(() => data.value?.visits ?? []);
const payments = computed(() => data.value?.payments ?? []);
const invoices = computed(() => data.value?.invoices ?? []);
const refunds = computed(() => data.value?.refunds ?? []);
const canProcessRefund = computed(() => session.me?.roles.some((role) => role.role_code === 'finance') ?? false);
const money = (cents: number) => `¥ ${(cents / 100).toFixed(2)}`;
function parseCents(text: string): number | null {
  if (!/^\d+(\.\d{1,2})?$/.test(text.trim())) return null;
  const [whole, frac = ''] = text.trim().split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}
async function load(): Promise<void> {
  loading.value = true; error.value = '';
  try {
    const result = await api.overview();
    data.value = result;
    if (!form.value.visitId) form.value.visitId = result.visits[0]?.visit_id ?? '';
  } catch (e) { error.value = e instanceof ApiError ? e.message : '账单读取失败。'; }
  finally { loading.value = false; }
}
async function createBill(): Promise<void> {
  error.value = ''; message.value = '';
  const cents = parseCents(form.value.amount);
  if (!form.value.visitId || !form.value.itemName.trim() || cents === null || cents <= 0) { error.value = '请选择就诊记录、填写项目和有效金额。'; return; }
  loading.value = true;
  try { await api.createBill(form.value.visitId, form.value.itemName.trim(), cents); message.value = '账单已创建。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '创建账单失败。'; }
  finally { loading.value = false; }
}
function openPay(bill: BillRow): void {
  if (bill.remaining_cents <= 0) { message.value = '该账单已付清，不能重复收款。'; return; }
  payForm.value = { bill, amount: (bill.remaining_cents / 100).toFixed(2), method: '现金', note: '' };
  error.value = ''; message.value = '';
}
async function pay(): Promise<void> {
  if (!payForm.value.bill) return;
  const cents = parseCents(payForm.value.amount);
  if (cents === null || cents <= 0) { error.value = '请输入有效收款金额。'; return; }
  loading.value = true; error.value = '';
  try {
    await api.pay(payForm.value.bill.bill_id, cents, payForm.value.method, payForm.value.note.trim());
    message.value = '线下收款已登记（未调用支付渠道）。'; payForm.value.bill = null; await load();
  } catch (e) { error.value = e instanceof ApiError ? e.message : '登记收款失败。'; }
  finally { loading.value = false; }
}
function invoice(bill: BillRow): void {
  if (bill.status !== '已收款') { message.value = '只有已收款账单可以申请票据。'; return; }
  invoiceForm.value = { bill, type: '人工收据', buyerType: '个人', treatment: '不征税', title: '个人', taxNo: '', rate: 0, net: (bill.paid_cents / 100).toFixed(2), tax: '0.00' };
  error.value = ''; message.value = '';
}
async function submitInvoice(): Promise<void> {
  const f = invoiceForm.value;
  if (!f.bill) return;
  const net = parseCents(f.net); const tax = parseCents(f.tax);
  if (net === null || tax === null) { error.value = '净额/税额格式不正确。'; return; }
  if (f.type !== '人工收据') {
    if (f.buyerType === '单位' && !f.taxNo.trim()) { error.value = '单位购方必须填写税号。'; return; }
    if (f.type === '增值税专用发票' && f.buyerType !== '单位') { error.value = '专用发票购方必须为单位。'; return; }
    if (f.treatment === '应税' && (f.rate <= 0 || net <= 0 || net + tax !== f.bill.paid_cents)) { error.value = '应税票据需填写税率，且净额+税额等于已收金额。'; return; }
    if (f.treatment !== '应税' && (net !== f.bill.paid_cents || tax !== 0 || f.rate !== 0)) { error.value = '免税/零税率/不征税票据税额必须为 0，净额等于已收金额。'; return; }
  }
  loading.value = true; error.value = '';
  try { await api.invoice(f.bill.bill_id, { title: f.title.trim(), type: f.type, buyerType: f.buyerType, buyerTaxNo: f.taxNo.trim(), treatment: f.treatment, rateBps: Number(f.rate), netCents: net, taxCents: tax }); message.value = '票据申请已登记（待开票）。'; invoiceForm.value.bill = null; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '申请票据失败。'; }
  finally { loading.value = false; }
}
function openInvoiceAction(id: string, mode: 'issue' | 'void', red = false): void {
  invoiceAction.value = { id, mode, value: '', basis: '', red };
  error.value = ''; message.value = '';
}
async function redInvoice(id: string): Promise<void> {
  if (!window.confirm('确认为该已开票票据创建红冲票据吗？')) return;
  loading.value = true; error.value = '';
  try { await api.redInvoice(id); message.value = '红冲票据已创建，待开票。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '红冲失败。'; }
  finally { loading.value = false; }
}
async function submitInvoiceAction(): Promise<void> {
  if (!invoiceAction.value || !invoiceAction.value.value.trim()) { error.value = '请填写票据号或作废原因。'; return; }
  loading.value = true; error.value = '';
  try {
    if (invoiceAction.value.mode === 'issue') await api.issueInvoice(invoiceAction.value.id, invoiceAction.value.value.trim(), invoiceAction.value.basis.trim());
    else await api.voidInvoice(invoiceAction.value.id, invoiceAction.value.value.trim());
    message.value = invoiceAction.value.mode === 'issue' ? '票据已开票。' : '票据已作废。';
    invoiceAction.value = null; await load();
  } catch (e) { error.value = e instanceof ApiError ? e.message : '票据操作失败。'; }
  finally { loading.value = false; }
}
async function requestRefund(): Promise<void> {
  const f = refundForm.value; if (!f) return;
  const cents = parseCents(f.amount);
  if (cents === null || cents <= 0 || cents > f.bill.paid_cents || !f.reason.trim()) { error.value = '请核对退款金额和原因。'; return; }
  loading.value = true; error.value = '';
  try { await api.requestRefund(f.bill.bill_id, cents, f.reason.trim()); refundForm.value = null; message.value = '退款申请已记录，尚未完成退款。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '退款申请结果未确认，请先刷新核对。'; }
  finally { loading.value = false; }
}
async function processRefund(): Promise<void> {
  const f = refundAction.value; if (!f || !f.basis.trim()) { error.value = '请填写处理依据。'; return; }
  loading.value = true; error.value = '';
  try { await api.processRefund(f.id, f.decision, f.basis.trim()); refundAction.value = null; message.value = '处理结果已登记；如已开票，请另行核对票据更正。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '处理结果未确认，请先刷新核对。'; }
  finally { loading.value = false; }
}
onMounted(() => { void load(); });
</script>

<template>
  <main class="billing-page">
    <header><div><RouterLink to="/">← 工作台</RouterLink><h1>收费与票据</h1><p>真实接口 · 线下受控收款与人工票据登记；不连接税控系统，不自动计算税率。</p></div><span class="real-badge">实时接口</span></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="loading" class="muted">正在读取账单…</p>
    <p class="refund-note">退款为线下凭证登记；申请不等于退款成功。财务确认后登记处理结果，正式渠道退款需另行核对。</p>
    <template v-if="data">
      <div class="summary"><div><span>账单</span><strong>{{ bills.length }}</strong></div><div><span>待收/部分</span><strong>{{ bills.filter(b => b.status !== '已收款').length }}</strong></div><div><span>收款登记</span><strong>{{ payments.length }}</strong></div><div><span>票据申请</span><strong>{{ invoices.length }}</strong></div></div>
      <section class="panel">
        <h2>从已完成就诊创建账单</h2>
        <div class="grid"><label>就诊记录<select v-model="form.visitId"><option v-for="v in visits" :key="v.visit_id" :value="v.visit_id">{{ v.patient_name }} · {{ v.completed_at.slice(0, 10) }}</option></select></label><label>项目<input v-model="form.itemName" maxlength="100" /></label><label>金额（元）<input v-model="form.amount" inputmode="decimal" /></label></div>
        <button class="primary" :disabled="loading" @click="createBill">创建账单</button>
      </section>
      <section class="panel">
        <h2>账单列表</h2>
        <table><thead><tr><th>账单</th><th>患者</th><th>总额</th><th>已付</th><th>待付</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="b in bills" :key="b.bill_id"><td>{{ b.bill_id }}</td><td>{{ b.patient_name }}</td><td>{{ money(b.total_cents) }}</td><td>{{ money(b.paid_cents) }}</td><td>{{ money(b.remaining_cents) }}</td><td><span class="badge">{{ b.status }}</span></td>
            <td class="actions"><button :disabled="b.remaining_cents <= 0 || loading" @click="openPay(b)">登记收款</button><button :disabled="b.paid_cents <= 0 || loading" @click="refundForm = { bill: b, amount: '', reason: '' }">申请退款</button><button :disabled="b.status !== '已收款' || loading" @click="invoice(b)">人工票据</button></td></tr>
          <tr v-if="bills.length === 0"><td colspan="7" class="empty">暂无真实账单。</td></tr>
        </tbody></table>
      </section>
      <section class="panel"><h2>收款登记</h2><table><thead><tr><th>流水</th><th>账单</th><th>金额</th><th>方式</th><th>状态</th></tr></thead><tbody><tr v-for="p in payments" :key="p.payment_id"><td>{{ p.payment_id }}</td><td>{{ p.bill_id }}</td><td>{{ money(p.amount_cents) }}</td><td>{{ p.method }}</td><td>{{ p.state }}</td></tr></tbody></table></section>
      <section class="panel"><h2>退款记录</h2><table><thead><tr><th>编号</th><th>账单</th><th>金额</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="r in refunds" :key="r.refund_id"><td>{{ r.refund_id }}</td><td>{{ r.bill_id }}</td><td>{{ money(r.amount_cents) }}</td><td>{{ r.reason }}</td><td>{{ r.status }}<small v-if="r.processing_basis"> · {{ r.processing_basis }}</small></td><td class="actions"><template v-if="r.status === '待处理' && canProcessRefund"><button :disabled="loading" @click="refundAction = { id: r.refund_id, decision: '已退款', basis: '' }">登记已退款</button><button :disabled="loading" @click="refundAction = { id: r.refund_id, decision: '已拒绝', basis: '' }">拒绝</button></template></td></tr><tr v-if="refunds.length === 0"><td colspan="6" class="empty">暂无退款记录。</td></tr></tbody></table></section>
      <section class="panel"><h2>票据记录</h2><table><thead><tr><th>编号</th><th>账单</th><th>类型</th><th>金额</th><th>税号/税率</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="i in invoices" :key="i.invoice_id"><td>{{ i.invoice_id }}</td><td>{{ i.bill_id }}</td><td>{{ i.invoice_type }}</td><td>{{ money(i.amount_cents) }}</td><td>{{ i.buyer_tax_no || '—' }} / {{ (i.tax_rate_bps / 100).toFixed(2) }}%</td><td>{{ i.status }}<span v-if="i.invoice_no"> · {{ i.invoice_no }}</span></td><td class="actions"><button v-if="i.status === '待开票'" :disabled="loading" @click="openInvoiceAction(i.invoice_id, 'issue', !!i.red_of_id)">开票</button><button v-if="i.status === '已开票' && !i.red_of_id" :disabled="loading" @click="redInvoice(i.invoice_id)">红冲</button><button v-if="i.status !== '已作废' && i.status !== '已红冲'" :disabled="loading" @click="openInvoiceAction(i.invoice_id, 'void')">作废</button></td></tr>
        <tr v-if="invoices.length === 0"><td colspan="7" class="empty">暂无票据记录。</td></tr>
      </tbody></table></section>
    </template>
    <div v-if="payForm.bill" class="mask" @click.self="payForm.bill = null"><form class="dialog" @submit.prevent="pay"><h2>登记线下收款</h2><p>账单 {{ payForm.bill.bill_id }} · 待付 {{ money(payForm.bill.remaining_cents) }}</p><p class="warn">本地受控登记，不会调用真实支付渠道。</p><label>金额（元）<input v-model="payForm.amount" inputmode="decimal" required /></label><label>方式<select v-model="payForm.method"><option>现金</option><option>银行卡</option><option>微信线下登记</option><option>其他线下</option></select></label><label>备注<input v-model="payForm.note" maxlength="300" /></label><div class="actions"><button type="button" @click="payForm.bill = null">取消</button><button class="primary" type="submit" :disabled="loading">确认登记</button></div></form></div>
    <div v-if="invoiceForm.bill" class="mask" @click.self="invoiceForm.bill = null"><form class="dialog" @submit.prevent="submitInvoice"><h2>申请票据</h2><p>账单 {{ invoiceForm.bill.bill_id }} · 已收 {{ money(invoiceForm.bill.paid_cents) }}</p><p class="warn">本地人工票据登记，不连接税控系统；税额需人工确认，不自动猜测税率。</p><label>类型<select v-model="invoiceForm.type"><option>人工收据</option><option>增值税普通发票</option><option>增值税专用发票</option></select></label><label>抬头<input v-model="invoiceForm.title" required maxlength="120" /></label><div class="grid2"><label>购方类型<select v-model="invoiceForm.buyerType" :disabled="invoiceForm.type === '人工收据'"><option>个人</option><option>单位</option></select></label><label>税务处理<select v-model="invoiceForm.treatment" :disabled="invoiceForm.type === '人工收据'"><option>不征税</option><option>免税</option><option>零税率</option><option>应税</option></select></label></div><label>购方税号<input v-model="invoiceForm.taxNo" maxlength="30" :disabled="invoiceForm.type === '人工收据' || invoiceForm.buyerType === '个人'" /></label><div class="grid2"><label>税率（%）<input v-model.number="invoiceForm.rate" type="number" min="0" max="100" :disabled="invoiceForm.type === '人工收据' || invoiceForm.treatment !== '应税'" /></label><label>净额（元）<input v-model="invoiceForm.net" inputmode="decimal" :disabled="invoiceForm.type !== '人工收据' && invoiceForm.treatment !== '应税'" /></label><label>税额（元）<input v-model="invoiceForm.tax" inputmode="decimal" :disabled="invoiceForm.type !== '人工收据' && invoiceForm.treatment !== '应税'" /></label></div><div class="actions"><button type="button" @click="invoiceForm.bill = null">取消</button><button class="primary" type="submit" :disabled="loading">提交申请</button></div></form></div>
    <div v-if="invoiceAction" class="mask" @click.self="invoiceAction = null"><form class="dialog" @submit.prevent="submitInvoiceAction"><h2>{{ invoiceAction.mode === 'issue' ? '登记开票号码' : '作废票据' }}</h2><p>{{ invoiceAction.mode === 'issue' ? '这是人工登记，不连接税控系统。' : '作废会保留原记录和作废原因。' }}</p><label>{{ invoiceAction.mode === 'issue' ? '票据号码' : '作废原因' }}<input v-model="invoiceAction.value" :maxlength="invoiceAction.mode === 'issue' ? 40 : 300" required /></label><label v-if="invoiceAction.mode === 'issue' && invoiceAction.red">红冲完成依据<input v-model="invoiceAction.basis" maxlength="300" required placeholder="人工核验依据、原票处理结果" /></label><div class="actions"><button type="button" @click="invoiceAction = null">取消</button><button class="primary" type="submit" :disabled="loading">确认</button></div></form></div>
    <div v-if="refundForm" class="mask" @click.self="refundForm = null"><form class="dialog" @submit.prevent="requestRefund"><h2>申请退款</h2><p>账单 {{ refundForm.bill.bill_id }} · 累计收款 {{ money(refundForm.bill.paid_cents) }}</p><label>金额（元）<input v-model="refundForm.amount" inputmode="decimal" required /></label><label>原因<input v-model="refundForm.reason" maxlength="300" required /></label><div class="actions"><button type="button" @click="refundForm = null">取消</button><button class="primary" type="submit" :disabled="loading">提交申请</button></div></form></div>
    <div v-if="refundAction" class="mask" @click.self="refundAction = null"><form class="dialog" @submit.prevent="processRefund"><h2>{{ refundAction.decision === '已退款' ? '登记退款完成' : '拒绝退款申请' }}</h2><p>仅财务人员可处理。登记退款完成前须核实线下退款凭证。</p><label>处理依据<input v-model="refundAction.basis" maxlength="300" required /></label><div class="actions"><button type="button" @click="refundAction = null">取消</button><button class="primary" type="submit" :disabled="loading">确认</button></div></form></div>
  </main>
</template>

<style scoped>
.billing-page { min-height: 100vh; background: #f2f8f7; padding: 22px 26px 40px; color: #193d39; }
header { display: flex; justify-content: space-between; gap: 14px; align-items: start; margin-bottom: 14px; }header a { color: #256e60; text-decoration: none; font-size: 13px; }h1 { margin: 7px 0 3px; font-size: 24px; }header p { margin: 0; color: #68847f; font-size: 13px; }.real-badge { background: #dff4e9; color: #1c6c52; border-radius: 999px; padding: 5px 9px; font-size: 11px; }
.summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }.summary div { background: #fff; border: 1px solid #dbeae6; border-radius: 9px; padding: 13px; display: grid; gap: 5px; }.summary span { color: #728d87; font-size: 12px; }.summary strong { font-size: 23px; color: #15594e; }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 10px; padding: 17px; margin-bottom: 14px; }.panel h2 { margin: 0 0 10px; font-size: 16px; }.grid { display: grid; grid-template-columns: 2fr 1.4fr 1fr; gap: 10px; margin-bottom: 10px; }label { display: grid; gap: 5px; color: #466a63; font-size: 12px; }input, select { border: 1px solid #cfe2dd; border-radius: 6px; padding: 8px; font: inherit; font-size: 13px; background: #fff; }
button { border: 1px solid #c8ded8; background: #fff; color: #246558; border-radius: 6px; padding: 7px 11px; font: inherit; font-size: 12px; cursor: pointer; }button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }button:disabled { opacity: .5; cursor: not-allowed; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }th { text-align: left; color: #6c8983; font-weight: 500; padding: 8px; border-bottom: 1px solid #e6f0ed; }td { padding: 9px 8px; border-bottom: 1px solid #edf4f2; }.badge { padding: 3px 7px; border-radius: 999px; background: #fff2d7; color: #875b12; font-size: 11px; }.actions { display: flex; gap: 5px; }.empty { text-align: center; color: #8ca19c; padding: 20px; }.error, .notice { padding: 10px 12px; border-radius: 7px; font-size: 12px; }.error { background: #fff0eb; color: #963d2e; }.notice { background: #e6f6ef; color: #1e6752; }.muted { color: #819791; font-size: 12px; }.refund-note { margin: 0 0 12px; padding: 8px 10px; border-radius: 6px; background: #fff7e7; color: #805817; font-size: 12px; }
.mask { position: fixed; inset: 0; background: #092a2633; display: flex; align-items: center; justify-content: center; z-index: 20; }.dialog { background: #fff; border-radius: 10px; padding: 20px; width: min(420px, 92vw); display: grid; gap: 12px; }.dialog h2 { margin: 0; font-size: 18px; }.dialog p { margin: 0; color: #6f8983; font-size: 12px; }.warn { color: #8a5b10; background: #fff7e7; padding: 8px; border-radius: 6px; }.dialog .actions { justify-content: end; }.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
@media (max-width: 760px) { .grid { grid-template-columns: 1fr; }.summary { grid-template-columns: 1fr 1fr; }.billing-page { padding: 15px; } }
</style>
