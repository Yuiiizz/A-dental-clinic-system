<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ApiError, createHttpClient, type PageResult } from '@/services/http';
import { createInventoryApi, type ItemCreate, type Receipt, type StockBalance, type StockItem, type Warehouse } from '@/services/inventory';
import { createSuppliersApi, type SupplierRow } from '@/services/suppliers';
import { emptyLine, emptyReceipt, prepareReceipt, type ReceiptDraft } from '@/services/inventory-form';
import { useSessionStore } from '@/stores/session';

type Tab = 'receiving' | 'balances' | 'receipts' | 'catalog';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const http = createHttpClient({ baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeoutMs: 30000, getCsrfToken: () => session.csrfToken });
const api = createInventoryApi(http);
const supplierApi = createSuppliersApi(http);
const tab = computed<Tab>(() => {
  const value = route.query.tab;
  return value === 'balances' || value === 'receipts' || value === 'catalog' ? value : 'receiving';
});
function switchTab(value: Tab): void { void router.replace({ path: '/inventory', query: { tab: value } }); }
const branchChoices = computed(() => [...new Set((session.me?.roles ?? [])
  .filter((role) => role.role_code === 'owner' || role.role_code === 'inventory')
  .flatMap((role) => role.branch_ids))].sort((a, b) => Number(a) - Number(b)));
const canConfigure = computed(() => (session.me?.roles ?? []).some((role) =>
  (role.role_code === 'owner' || role.role_code === 'inventory') && role.branch_ids.includes(branchId.value)));
const canReceive = computed(() => (session.me?.roles ?? []).some((role) =>
  role.role_code === 'inventory' && role.branch_ids.includes(branchId.value)));
const branchId = ref('');
const items = ref<StockItem[]>([]);
const warehouses = ref<Warehouse[]>([]);
const suppliers = ref<SupplierRow[]>([]);
const receipts = ref<Receipt[]>([]);
const balances = ref<StockBalance[]>([]);
const selectedReceipt = ref<Receipt | null>(null);
const loading = ref(false);
const saving = ref(false);
const detailLoading = ref(false);
const error = ref('');
const notice = ref('');
const itemForm = ref<ItemCreate>({ code: '', name: '', specification: '', base_unit: '', storage_condition: '' });
const warehouseName = ref('');
const draft = ref<ReceiptDraft>(emptyReceipt(''));
const traceBatchId = ref('');
let epoch = 0;
let detailId = 0;
const keys: Record<string, { fingerprint: string; key: string }> = {};

function clear(): void {
  items.value = []; warehouses.value = []; suppliers.value = []; receipts.value = []; balances.value = [];
  selectedReceipt.value = null; detailId++; traceBatchId.value = '';
  loading.value = false; saving.value = false; detailLoading.value = false;
}
function clearDrafts(): void {
  itemForm.value = { code: '', name: '', specification: '', base_unit: '', storage_condition: '' };
  warehouseName.value = ''; draft.value = emptyReceipt(branchId.value);
  for (const kind of Object.keys(keys)) delete keys[kind];
}
function safeMessage(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 401) return '登录状态已变化，请重新确认身份。';
    if (cause.status === 403) return '当前账号没有该门店的库存权限，或任职、门店状态已变化。';
    if (cause.status === 409) return cause.message || '单号、批次或幂等请求发生冲突，请核对。';
    if (cause.status === 422) return cause.message || '表单内容不符合验收规则。';
    if (cause.status >= 500 || cause.code === 'NETWORK_ERROR' || cause.code === 'TIMEOUT' || cause.code === 'INVALID_RESPONSE')
      return '请求结果未确认。请先刷新记录核对，再用原表单和原幂等键手动重试。';
    return cause.message;
  }
  return cause instanceof Error ? cause.message : '请求结果未确认，请先刷新核对。';
}
function accessLoss(cause: unknown): void {
  if (!(cause instanceof ApiError) || (cause.status !== 401 && cause.status !== 403)) return;
  epoch++; clear();
  if (cause.status === 401) void session.fetchMe();
}
async function everyPage<T>(get: (cursor?: string) => Promise<PageResult<T>>): Promise<T[]> {
  const result: T[] = [];
  let cursor: string | undefined;
  const seen = new Set<string>();
  for (let count = 0; count < 50; count++) {
    const page = await get(cursor);
    result.push(...page.items);
    if (!page.nextCursor) return result;
    if (seen.has(page.nextCursor)) throw new ApiError('INVALID_RESPONSE', 0, '分页游标重复。');
    seen.add(page.nextCursor); cursor = page.nextCursor;
  }
  throw new ApiError('INVALID_RESPONSE', 0, '记录较多，请缩小范围后重试。');
}
async function load(): Promise<void> {
  if (!branchId.value || session.status !== 'ready') return;
  const current = ++epoch;
  const branch = branchId.value;
  loading.value = true; error.value = '';
  try {
    const [nextItems, nextWarehouses, nextSuppliers, nextReceipts, nextBalances] = await Promise.all([
      everyPage((cursor) => api.items(cursor)),
      everyPage((cursor) => api.warehouses(branch, cursor)),
      everyPage((cursor) => supplierApi.list(cursor)),
      everyPage((cursor) => api.receipts(branch, cursor, traceBatchId.value || undefined)),
      everyPage((cursor) => api.balances(branch, cursor)),
    ]);
    if (current !== epoch || branch !== branchId.value) return;
    items.value = nextItems; warehouses.value = nextWarehouses; suppliers.value = nextSuppliers;
    receipts.value = nextReceipts; balances.value = nextBalances;
  } catch (cause) {
    if (current === epoch) { accessLoss(cause); error.value = safeMessage(cause); }
  } finally { if (current === epoch) loading.value = false; }
}
async function showReceipt(id: string): Promise<void> {
  const current = epoch; const request = ++detailId;
  detailLoading.value = true; error.value = '';
  try {
    const value = await api.detail(id);
    if (current === epoch && request === detailId) selectedReceipt.value = value;
  } catch (cause) {
    if (current === epoch && request === detailId) { accessLoss(cause); error.value = safeMessage(cause); }
  } finally { if (current === epoch && request === detailId) detailLoading.value = false; }
}
function stableKey(kind: string, input: unknown): string {
  const fingerprint = JSON.stringify(input);
  if (keys[kind]?.fingerprint !== fingerprint) keys[kind] = { fingerprint, key: crypto.randomUUID() };
  return keys[kind].key;
}
async function createItem(): Promise<void> {
  if (saving.value || !canConfigure.value) return;
  const input = Object.fromEntries(Object.entries(itemForm.value).map(([k, v]) => [k, v.trim()])) as unknown as ItemCreate;
  if (Object.values(input).some((v) => !v)) { error.value = '请填写完整的耗材编码、名称、规格、单位和储存要求。'; return; }
  const key = stableKey('item', input); const current = epoch;
  saving.value = true; error.value = ''; notice.value = '';
  try {
    const created = await api.createItem(input, key);
    if (current !== epoch) return;
    itemForm.value = { code: '', name: '', specification: '', base_unit: '', storage_condition: '' };
    delete keys.item; notice.value = `耗材 ${created.name} 已建档；尚无库存。`;
    saving.value = false;
    await load();
  } catch (cause) { if (current === epoch) { accessLoss(cause); error.value = safeMessage(cause); } }
  finally { if (current === epoch) saving.value = false; }
}
async function createWarehouse(): Promise<void> {
  if (saving.value || !canConfigure.value) return;
  const input = { branch_id: branchId.value, name: warehouseName.value.trim() };
  if (!input.name) { error.value = '请填写库位名称。'; return; }
  const key = stableKey('warehouse', input); const current = epoch;
  saving.value = true; error.value = ''; notice.value = '';
  try {
    const created = await api.createWarehouse(input, key);
    if (current !== epoch) return;
    warehouseName.value = ''; delete keys.warehouse;
    notice.value = `库位 ${created.name} 已建立。`; saving.value = false; await load();
  } catch (cause) { if (current === epoch) { accessLoss(cause); error.value = safeMessage(cause); } }
  finally { if (current === epoch) saving.value = false; }
}
async function receive(): Promise<void> {
  if (saving.value || !canReceive.value) return;
  let input;
  try { input = prepareReceipt(draft.value); }
  catch (cause) { error.value = safeMessage(cause); return; }
  const key = stableKey('receipt', input); const current = epoch;
  saving.value = true; error.value = ''; notice.value = '';
  try {
    const created = await api.receive(input, key);
    if (current !== epoch) return;
    draft.value = emptyReceipt(branchId.value); delete keys.receipt;
    selectedReceipt.value = created;
    notice.value = `验收单 ${created.document_no} 已过账。请在记录与批次余额核对。`;
    saving.value = false;
    await load();
  } catch (cause) { if (current === epoch) { accessLoss(cause); error.value = safeMessage(cause); } }
  finally { if (current === epoch) saving.value = false; }
}
function trace(batchId: string): void { traceBatchId.value = batchId; switchTab('receipts'); void load(); }
function resetTrace(): void { traceBatchId.value = ''; void load(); }
function warehouseNameOf(id: string): string { return warehouses.value.find((row) => row.warehouse_id === id)?.name ?? `库位 #${id}`; }
function supplierName(id: string): string { return suppliers.value.find((row) => row.supplier_id === id)?.name ?? `供应商 #${id}`; }
function money(value: number): string { return Number.isFinite(value) ? `¥${value.toFixed(2)}` : '—'; }
function dateTime(value: string): string { return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }); }
function stockUnit(itemId: string): string { return items.value.find((row) => row.stock_item_id === itemId)?.base_unit || '单位待选'; }
function quantityText(quantity: number, unit: string): string { return quantity === 0 ? '暂无可用' : `${quantity} ${unit}`; }
watch([() => session.sessionRevision, branchId], () => {
  epoch++; clear(); clearDrafts(); error.value = ''; notice.value = '';
  const choices = branchChoices.value;
  if (!choices.includes(branchId.value)) { branchId.value = choices[0] ?? ''; return; }
  if (branchId.value && session.status === 'ready') void load();
}, { immediate: true });
onMounted(() => { if (branchId.value && !loading.value && items.value.length === 0) void load(); });
</script>

<template>
  <main class="inventory-page">
    <header class="hero">
      <div><RouterLink to="/">← 返回工作台</RouterLink><p class="eyebrow">A CLINIC · 采购与成本</p>
        <h1>普通耗材管理</h1><p>供应商、验收入库、批次余额在同一真实工作流中核对。</p></div>
      <div class="hero-side"><span class="real-tag">真实接口</span><label>当前门店
        <select v-model="branchId" :disabled="saving || loading"><option value="" disabled>选择门店</option>
          <option v-for="id in branchChoices" :key="id" :value="id">门店 #{{ id }}</option></select></label></div>
    </header>
    <div class="scope-note">本页只管理普通耗材。余额来自已过账验收流水，不代表已付款、药品库存或临床可领用量。操作权限由服务端核验。</div>
    <p class="stock-policy">当前接口只提供验收入库和批次余额，尚未接入出库/领用、补货阈值或低库存自动判定；本页不会伪造这些结果。数量为 0 的批次统一显示“暂无可用”。</p>
    <p v-if="!branchChoices.length" class="banner warn">当前账号没有可见门店范围，或尚未完成门店任职配置。</p>
    <div v-if="branchId" class="content">
      <nav class="tabs" aria-label="耗材管理模块">
        <button v-for="entry in ([['receiving','验收入库'],['balances','批次余额'],['receipts','验收记录'],['catalog','目录与库位']] as const)"
          :key="entry[0]" type="button" :class="{active:tab===entry[0]}" @click="switchTab(entry[0])">{{ entry[1] }}</button>
        <button type="button" class="refresh" :disabled="loading" @click="load()">{{ loading ? '读取中…' : '刷新数据' }}</button>
      </nav>
      <p v-if="error" class="banner error" role="alert">{{ error }}</p><p v-if="notice" class="banner success" role="status">{{ notice }}</p>
      <section v-if="tab==='receiving'" class="layout">
        <div class="card">
          <div class="section-head"><div><p class="eyebrow">01 / RECEIVE</p><h2>新增采购验收</h2></div><span>一次过账 · 不生成付款</span></div>
          <p v-if="!canReceive" class="permission-note" role="status"><strong>只读权限</strong> 当前角色可查看库存；验收入库需要库管角色及该门店的有效授权。</p>
          <form v-else @submit.prevent="receive"><fieldset :disabled="saving || loading">
            <div class="two"><label>供应商<select v-model="draft.supplier_id" required><option value="" disabled>选择已核验供应商</option>
                <option v-for="supplier in suppliers.filter((s)=>s.enabled)" :key="supplier.supplier_id" :value="supplier.supplier_id">{{ supplier.name }}</option></select></label>
              <label>采购单号<input v-model="draft.document_no" maxlength="80" required placeholder="本店唯一单号" /></label></div>
            <label>供应商单据<input v-model="draft.supplier_document" maxlength="100" required placeholder="送货单 / 发票引用，不代表已付款" /></label>
            <div v-for="(line, index) in draft.lines" :key="index" class="line-card">
              <div class="line-title"><strong>验收明细 {{ index + 1 }}</strong><button v-if="draft.lines.length>1" type="button" class="text-btn" @click="draft.lines.splice(index,1)">移除</button></div>
              <div class="two"><label>耗材<select v-model="line.stock_item_id" required><option value="" disabled>选择耗材</option><option v-for="item in items.filter((i)=>i.enabled)" :key="item.stock_item_id" :value="item.stock_item_id">{{ item.code }} · {{ item.name }}</option></select></label>
                <label>入库库位<select v-model="line.warehouse_id" required><option value="" disabled>选择本店库位</option><option v-for="place in warehouses.filter((w)=>w.enabled)" :key="place.warehouse_id" :value="place.warehouse_id">{{ place.name }}</option></select></label></div>
              <div class="two"><label>生产企业<input v-model="line.manufacturer" maxlength="120" required /></label><label>批号<input v-model="line.batch_no" maxlength="80" required /></label></div>
              <div class="three"><label>有效期<input v-model="line.expires_on" type="date" required /></label><label>验收数量 <span class="unit-badge">{{ stockUnit(line.stock_item_id) }}</span><input v-model="line.quantity" inputmode="decimal" placeholder="最多 3 位小数" required /></label><label>单位取得成本（元）<input v-model="line.unit_cost" inputmode="decimal" placeholder="最多 4 位小数" required /></label></div>
              <div class="two"><label>持有人（可选）<input v-model="line.holder_name" maxlength="120" /></label><label>追溯依据（可选）<input v-model="line.trace_reference" maxlength="300" /></label></div>
            </div>
            <div class="form-actions"><button type="button" class="outline" :disabled="draft.lines.length>=50" @click="draft.lines.push(emptyLine())">＋ 增加明细</button><button type="submit" class="primary">{{ saving ? '提交中…' : '核对并过账' }}</button></div>
          </fieldset></form>
        </div>
        <aside class="card side"><h2>入库前核对</h2><ol><li>供应商已核验且资质在有效期内。</li><li>耗材和本店库位处于启用状态。</li><li>生产企业、批号和有效期与实物一致。</li><li>数量和单位取得成本按真实单据录入。</li></ol><p>请求超时或结果不明时，先查看验收记录；表单和原幂等键会保留，页面不会自动重复过账。</p><RouterLink to="/suppliers">维护供应商 →</RouterLink></aside>
      </section>
      <section v-else-if="tab==='balances'" class="card"><div class="section-head"><div><p class="eyebrow">02 / STOCK</p><h2>批次账面余额</h2></div><span>按库位和批次汇总</span></div>
        <div v-if="!balances.length" class="empty">{{ loading ? '正在读取余额…' : '暂无已过账批次余额。' }}</div>
        <template v-else>
          <p class="stock-note">仅展示已过账验收流水形成的账面余额；数量为 0 显示“暂无可用”。低库存阈值未由接口提供，本页不自动判定补货，请按门店实际规则核对。</p>
          <div class="table-wrap balance-table"><table><thead><tr><th>耗材</th><th>库位</th><th>批号 / 生产企业</th><th>有效期</th><th>状态</th><th>余额</th><th>来源</th></tr></thead>
            <tbody><tr v-for="row in balances" :key="`${row.warehouse_id}:${row.batch_id}`"><td><strong>{{ row.item_name }}</strong><small>{{ row.code }}</small></td><td>{{ row.warehouse_name }}</td><td>{{ row.batch_no }}<small>{{ row.manufacturer }}</small></td><td>{{ row.expires_on }}</td><td><span class="batch-state" :class="{ warn: row.batch_state !== '有效' }">{{ row.batch_state }}</span></td><td class="number" :class="{ zero: row.quantity === 0 }">{{ quantityText(row.quantity, row.base_unit) }}</td><td><button type="button" class="text-btn" @click="trace(row.batch_id)">查看验收单</button></td></tr></tbody></table></div>
          <div class="balance-cards">
            <article v-for="row in balances" :key="`${row.warehouse_id}:${row.batch_id}`" class="balance-card">
              <div class="balance-head"><strong>{{ row.item_name }}</strong><span class="quantity-pill" :class="{ zero: row.quantity === 0 }">{{ quantityText(row.quantity, row.base_unit) }}</span></div>
              <p class="balance-code">{{ row.code }} · {{ row.warehouse_name }}</p>
              <dl class="balance-meta"><dt>批号</dt><dd>{{ row.batch_no }}</dd><dt>生产企业</dt><dd>{{ row.manufacturer }}</dd><dt>有效期</dt><dd>{{ row.expires_on }} · <span class="batch-state" :class="{ warn: row.batch_state !== '有效' }">{{ row.batch_state }}</span></dd></dl>
              <button type="button" class="outline" @click="trace(row.batch_id)">查看验收单</button>
            </article>
          </div>
        </template>
      </section>
      <section v-else-if="tab==='receipts'" class="layout"><div class="card"><div class="section-head"><div><p class="eyebrow">03 / RECORDS</p><h2>已过账验收单</h2></div><button v-if="traceBatchId" type="button" class="outline" @click="resetTrace">清除批次筛选 #{{ traceBatchId }}</button></div>
          <div v-if="!receipts.length" class="empty">{{ loading ? '正在读取验收单…' : '暂无符合条件的验收单。' }}</div>
          <button v-for="row in receipts" :key="row.receipt_id" type="button" class="record" @click="showReceipt(row.receipt_id)"><strong>{{ row.document_no }}</strong><span>{{ supplierName(row.supplier_id) }} · {{ dateTime(row.received_at) }}</span><em>{{ row.state }}</em></button>
        </div><aside class="card side"><h2>验收详情</h2><p v-if="detailLoading">读取明细中…</p><p v-else-if="!selectedReceipt">选择一张验收单查看批次与成本。</p>
          <template v-else><p><strong>{{ selectedReceipt.document_no }}</strong> · {{ selectedReceipt.state }}</p><p>供应商：{{ supplierName(selectedReceipt.supplier_id) }}</p><p>供应商单据：{{ selectedReceipt.supplier_document }}</p><p>经办员工编号：{{ selectedReceipt.accepted_by }}</p>
            <div v-for="line in selectedReceipt.lines ?? []" :key="line.receipt_line_id" class="detail-line"><strong>{{ line.item_name }}</strong><span>{{ warehouseNameOf(line.warehouse_id) }} · 批号 {{ line.batch_no }}</span><span>{{ line.quantity }} {{ line.base_unit }} × {{ money(line.unit_cost) }}</span></div>
            <p>取得成本合计：<strong>{{ money(selectedReceipt.acquisition_cost_total ?? 0) }}</strong></p><small>{{ selectedReceipt.cost_note }}</small></template></aside></section>
      <section v-else class="layout"><div class="card"><div class="section-head"><div><p class="eyebrow">04 / CATALOG</p><h2>耗材目录</h2></div><span>{{ items.length }} 项</span></div>
          <div v-if="!items.length" class="empty">暂无耗材目录。</div><div v-for="item in items" :key="item.stock_item_id" class="catalog-row"><strong>{{ item.name }}</strong><span>{{ item.code }} · {{ item.specification }} · {{ item.base_unit }}</span><small>{{ item.storage_condition }} · {{ item.enabled ? '启用' : '停用' }}</small></div>
          <form v-if="canConfigure" class="mini-form" @submit.prevent="createItem"><fieldset :disabled="saving"><h3>建立普通耗材</h3><div class="two"><label>编码<input v-model="itemForm.code" maxlength="40" required /></label><label>名称<input v-model="itemForm.name" maxlength="120" required /></label></div><div class="two"><label>规格<input v-model="itemForm.specification" maxlength="120" required /></label><label>基本单位<input v-model="itemForm.base_unit" maxlength="20" required /></label></div><label>储存要求<input v-model="itemForm.storage_condition" maxlength="200" required /></label><button class="primary" type="submit">{{ saving ? '保存中…' : '保存耗材目录' }}</button></fieldset></form>
        </div><div class="card"><div class="section-head"><div><p class="eyebrow">04 / LOCATIONS</p><h2>本店库位</h2></div><span>{{ warehouses.length }} 处</span></div>
          <div v-if="!warehouses.length" class="empty">暂无本店库位。</div><div v-for="place in warehouses" :key="place.warehouse_id" class="catalog-row"><strong>{{ place.name }}</strong><span>库位 #{{ place.warehouse_id }} · {{ place.enabled ? '启用' : '停用' }}</span></div>
          <form v-if="canConfigure" class="mini-form" @submit.prevent="createWarehouse"><fieldset :disabled="saving"><h3>建立本店库位</h3><label>库位名称<input v-model="warehouseName" maxlength="80" required /></label><button class="primary" type="submit">{{ saving ? '保存中…' : '保存库位' }}</button></fieldset></form>
        </div></section>
    </div>
  </main>
</template>

<style scoped>
.inventory-page{min-height:100vh;background:#f4f9f8;color:#243e3b;padding:30px 4vw 60px}.hero,.content,.scope-note,.banner{max-width:1280px;margin-left:auto;margin-right:auto}.hero{display:flex;justify-content:space-between;gap:25px;align-items:end;padding:28px 34px;border-radius:24px;background:linear-gradient(115deg,#e2f7f3,#fefcf7 72%);border:1px solid #d7eae4}.hero a,.side a{color:#208c7d;text-decoration:none}.hero h1{font-size:34px;letter-spacing:-.03em;margin:8px 0}.hero p{margin:5px 0;color:#587570}.eyebrow{color:#2c9887!important;font-size:12px;font-weight:800;letter-spacing:.14em}.hero-side{min-width:190px;display:grid;gap:12px}.hero-side label{font-size:13px;font-weight:700}.real-tag{justify-self:start;border-radius:99px;background:#cfefe6;color:#167e68;padding:7px 12px;font-size:12px;font-weight:800}.scope-note{margin-top:17px;margin-bottom:17px;color:#58736e;font-size:13px}.content{margin-top:20px}.tabs{display:flex;gap:7px;flex-wrap:wrap;margin-bottom:16px}.tabs button,.outline{border:1px solid #d0e5de;background:white;color:#3f625d;border-radius:10px;padding:10px 16px;cursor:pointer}.tabs button.active{background:#238f80;color:white;border-color:#238f80}.tabs .refresh{margin-left:auto}.layout{display:grid;grid-template-columns:minmax(0,1.75fr) minmax(260px,1fr);gap:18px;align-items:start}.card{background:white;border:1px solid #dceae5;border-radius:20px;padding:24px;box-shadow:0 12px 36px #2a6c5b0a}.card h2{font-size:21px;margin:4px 0 14px}.card h3{font-size:17px}.section-head{display:flex;justify-content:space-between;gap:12px;align-items:start}.section-head span,.hint,.side p,.side li,.side small{color:#6a817b;font-size:13px}.side ol{padding-left:20px;line-height:2}.two,.three{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.three{grid-template-columns:repeat(3,minmax(0,1fr))}label{display:block;font-size:13px;font-weight:650;margin:12px 0}input,select{width:100%;box-sizing:border-box;margin-top:6px;padding:10px 12px;border:1px solid #c9ddd7;border-radius:9px;background:white;color:#284640;font:inherit}fieldset{padding:0;margin:0;border:0}.line-card{border:1px solid #daeae4;background:#f8fcfa;border-radius:14px;padding:17px;margin:18px 0}.line-title,.form-actions{display:flex;justify-content:space-between;align-items:center;gap:12px}.form-actions{margin-top:18px}.primary{border:0;border-radius:10px;background:#238f80;color:white;padding:11px 20px;cursor:pointer;font:inherit}.text-btn{border:0;background:transparent;color:#178b77;cursor:pointer;padding:5px;font:inherit}.primary:disabled,button:disabled{opacity:.5;cursor:not-allowed}.banner{padding:12px 16px;border-radius:10px;margin-top:12px}.error{background:#fff0ed;color:#9d4238}.success{background:#e5f5ec;color:#2e705d}.warn{background:#fff7e3;color:#755825}.empty{padding:32px;text-align:center;color:#7e9690}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:720px}th,td{text-align:left;padding:13px;border-bottom:1px solid #edf3f0}th{font-size:12px;color:#69837c}td{font-size:13px}td small,.catalog-row small{display:block;color:#78928c;margin-top:5px}.number{font-weight:800;white-space:nowrap}.record{width:100%;display:flex;gap:12px;align-items:center;text-align:left;border:0;border-bottom:1px solid #edf2ef;background:white;padding:16px 4px;cursor:pointer;color:inherit}.record span{color:#708983;flex:1}.record em{font-size:12px;color:#278978;font-style:normal}.detail-line,.catalog-row{display:grid;gap:5px;padding:13px 0;border-bottom:1px solid #edf3f0}.detail-line span,.catalog-row span{font-size:13px;color:#66807a}.mini-form{margin-top:25px;border-top:1px solid #e8f0ec;padding-top:14px}@media(max-width:780px){.inventory-page{padding:12px}.hero{display:block;padding:20px}.hero-side{margin-top:14px}.layout,.two,.three{grid-template-columns:1fr}.tabs .refresh{margin-left:0}.card{padding:18px}}

.stock-policy{margin:0 auto 16px;max-width:1280px;padding:10px 14px;border-left:3px solid #d9b66e;background:#fffaf0;color:#735823;font-size:12px;line-height:1.65;border-radius:6px}
.permission-note{margin:12px 0 0;padding:11px 13px;border:1px solid #efd9a7;border-radius:9px;background:#fff9eb;color:#75571c;font-size:13px;line-height:1.6}
.unit-badge{display:inline-block;margin-left:6px;padding:1px 7px;border-radius:999px;background:#e9f6f1;color:#2a7365;font-size:11px;font-weight:700}
.stock-note{margin:0 0 12px;color:#627c76;font-size:12px;line-height:1.65}
td.number.zero{color:#9a5b19;background:#fffaf0;font-weight:700}
.balance-cards{display:none}
.balance-card{padding:16px;border:1px solid #dceae5;border-radius:14px;background:#fbfefc}
.balance-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
.balance-head strong{min-width:0;overflow-wrap:anywhere;line-height:1.45}
.quantity-pill{flex:none;padding:5px 9px;border-radius:999px;background:#dff4e9;color:#1c6b52;font-size:12px;font-weight:750;white-space:nowrap}
.quantity-pill.zero{background:#fff0dd;color:#8a5314}
.batch-state{display:inline-block;padding:3px 8px;border-radius:999px;background:#e2f5ea;color:#1d6b52;font-size:11px;font-weight:700;white-space:nowrap}
.batch-state.warn{background:#fff0dd;color:#8a5314}
.balance-code{margin:6px 0 12px;color:#718b84;font-size:12px;overflow-wrap:anywhere}
.balance-meta{display:grid;grid-template-columns:76px minmax(0,1fr);gap:6px 10px;margin:0 0 14px;font-size:12px}
.balance-meta dt{color:#7e958f}
.balance-meta dd{margin:0;color:#34564f;overflow-wrap:anywhere}
.balance-card .outline{width:100%}
.record span{min-width:0;overflow-wrap:anywhere}
.catalog-row strong,.catalog-row span,.detail-line strong,.detail-line span{overflow-wrap:anywhere}
@media(max-width:900px){
  .balance-table{display:none}
  .balance-cards{display:grid;gap:12px}
}
@media(max-width:780px){
  .layout{align-items:start}
  .card{padding:18px 16px}
  .section-head{gap:8px}
  .section-head span{text-align:right;line-height:1.45}
  .tabs .refresh{margin-left:0;width:100%}
  .form-actions{flex-wrap:wrap}
  .form-actions .primary,.form-actions .outline{flex:1;min-width:130px}
}

</style>
