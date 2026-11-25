<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { createTreatmentPlanApi, type PlanOverview, type PlanRow } from '@/services/treatment-plans';

const session = useSessionStore();
const api = createTreatmentPlanApi(createHttpClient({ baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1', timeoutMs: 30000, getCsrfToken: () => session.csrfToken }));
const data = ref<PlanOverview | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');
const form = ref({ visitId: '', title: '牙周治疗计划', diagnosis: '', items: [{ serviceItemId: '', name: '牙周维护', tooth: '全口', phase: '第一阶段', quantity: 1, price: '680.00' }] });
const plans = computed(() => data.value?.plans ?? []);
const visits = computed(() => data.value?.visits ?? []);
const services = computed(() => data.value?.services ?? []);
const money = (c: number) => `¥ ${(c / 100).toFixed(2)}`;
function parseCents(text: string): number { if (!/^\d+(\.\d{1,2})?$/.test(text.trim())) return NaN; const [w, f = ''] = text.trim().split('.'); return Number(w) * 100 + Number((f + '00').slice(0, 2)); }
async function load(): Promise<void> {
  loading.value = true; error.value = '';
  try { const r = await api.overview(); data.value = r; if (!form.value.visitId) form.value.visitId = r.visits[0]?.visit_id ?? ''; }
  catch (e) { error.value = e instanceof ApiError ? e.message : '治疗计划读取失败。'; }
  finally { loading.value = false; }
}
function addItem(): void { form.value.items.push({ serviceItemId: '', name: '', tooth: '', phase: '第二阶段', quantity: 1, price: '0.00' }); }
function removeItem(index: number): void { if (form.value.items.length > 1) form.value.items.splice(index, 1); }
async function create(): Promise<void> {
  error.value = ''; message.value = '';
  if (!form.value.visitId || !form.value.title.trim()) { error.value = '请选择来源就诊并填写计划名称。'; return; }
  const items = form.value.items.map((i) => ({ serviceItemId: i.serviceItemId, name: i.name, toothPosition: i.tooth, phase: i.phase, quantity: i.quantity, unitPriceCents: parseCents(i.price) }));
  if (items.some((i) => !i.name.trim() || !Number.isFinite(i.unitPriceCents) || i.unitPriceCents < 0 || i.quantity < 1)) { error.value = '请检查项目名称、数量和金额。'; return; }
  loading.value = true;
  try { await api.create(form.value.visitId, form.value.title.trim(), form.value.diagnosis.trim(), items); message.value = '治疗计划已保存为草稿；确认后提交给患者。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '创建治疗计划失败。'; }
  finally { loading.value = false; }
}
async function submit(plan: PlanRow): Promise<void> {
  loading.value = true; error.value = ''; message.value = '';
  try { await api.submit(plan.plan_id); message.value = '计划已提交患者确认。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '提交失败。'; }
  finally { loading.value = false; }
}
async function createBill(plan: PlanRow): Promise<void> {
  loading.value = true; error.value = ''; message.value = '';
  try { const billId = await api.createBill(plan.plan_id); message.value = `已生成账单 ${billId}，请到收费页登记收款。`; }
  catch (e) { error.value = e instanceof ApiError ? e.message : '生成账单失败。'; }
  finally { loading.value = false; }
}
async function setItem(plan: PlanRow, itemId: string, status: string): Promise<void> {
  loading.value = true; error.value = ''; message.value = '';
  try { await api.setItemStatus(plan.plan_id, itemId, status); message.value = '治疗项目进度已更新。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '更新进度失败。'; }
  finally { loading.value = false; }
}
onMounted(() => { void load(); });
</script>

<template>
  <main class="plan-page">
    <header><div><RouterLink to="/">← 工作台</RouterLink><h1>治疗计划</h1><p>真实接口 · 诊断、牙位、分阶段项目、患者确认和执行进度。</p></div><span class="real-badge">实时接口</span></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p><p v-if="loading" class="muted">正在读取治疗计划…</p>
    <template v-if="data">
      <section class="panel">
        <h2>新建治疗计划</h2>
        <div class="grid"><label>来源就诊<select v-model="form.visitId"><option v-for="v in visits" :key="v.visit_id" :value="v.visit_id">{{ v.patient_name }} · {{ v.dentist_name }} · {{ v.completed_at.slice(0, 10) }}</option></select></label><label>计划名称<input v-model="form.title" maxlength="120" /></label><label>诊断<input v-model="form.diagnosis" placeholder="例如：慢性牙周炎" /></label></div>
        <div v-for="(item, index) in form.items" :key="index" class="item-row">
          <label>目录项目<select v-model="item.serviceItemId"><option value="">自定义</option><option v-for="s in services" :key="s.item_id" :value="s.item_id">{{ s.name }}</option></select></label>
          <label>项目名称<input v-model="item.name" maxlength="100" /></label><label>牙位<input v-model="item.tooth" maxlength="20" /></label><label>阶段<input v-model="item.phase" maxlength="40" /></label><label>数量<input v-model.number="item.quantity" type="number" min="1" /></label><label>单价（元）<input v-model="item.price" inputmode="decimal" /></label>
          <button type="button" class="ghost" @click="removeItem(index)" :disabled="form.items.length === 1">删除</button>
        </div>
        <div class="row-actions"><button type="button" @click="addItem">+ 添加项目</button><button class="primary" :disabled="loading" @click="create">保存草稿</button></div>
      </section>
      <section class="panel">
        <h2>计划列表</h2>
        <article v-for="p in plans" :key="p.plan_id" class="plan-card">
          <div class="plan-head"><div><strong>{{ p.title }}</strong><span class="state">{{ p.state }}</span><p>{{ p.patient_name }} · {{ p.dentist_name }} · {{ p.diagnosis || '未填写诊断' }} · 预估 {{ money(p.estimated_total_cents) }}</p></div><div class="head-actions"><button v-if="p.state === '草稿'" :disabled="loading" @click="submit(p)">提交患者确认</button><button v-if="p.state === '已接受' || p.state === '已完成'" :disabled="loading" @click="createBill(p)">生成账单</button><span class="muted">v{{ p.version }} · {{ p.completed_count }}/{{ p.item_count }} 完成</span></div></div>
          <div v-if="p.acceptance_basis" class="basis">患者确认依据：{{ p.acceptance_basis }}</div>
          <div v-if="p.rejected_reason" class="basis rejected">患者拒绝：{{ p.rejected_reason }}</div>
          <table><thead><tr><th>阶段</th><th>项目</th><th>牙位</th><th>数量</th><th>金额</th><th>进度</th><th>操作</th></tr></thead><tbody><tr v-for="i in p.items" :key="i.item_id"><td>{{ i.phase || '—' }}</td><td>{{ i.name }}</td><td>{{ i.tooth_position || '—' }}</td><td>{{ i.quantity }}</td><td>{{ money(i.amount_cents) }}</td><td>{{ i.status }}</td><td class="actions"><button v-if="p.state === '已接受' && i.status !== '已完成'" :disabled="loading" @click="setItem(p, i.item_id, '已完成')">完成</button><button v-if="p.state === '已接受' && i.status === '计划'" :disabled="loading" @click="setItem(p, i.item_id, '进行中')">开始</button></td></tr></tbody></table>
        </article>
        <p v-if="plans.length === 0" class="empty">暂无治疗计划。</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.plan-page { min-height: 100vh; background: #f2f8f7; padding: 22px 26px 40px; color: #193d39; }header { display: flex; justify-content: space-between; gap: 14px; align-items: start; margin-bottom: 14px; }header a { color: #256e60; text-decoration: none; font-size: 13px; }h1 { margin: 7px 0 3px; font-size: 24px; }header p { margin: 0; color: #68847f; font-size: 13px; }.real-badge { background: #dff4e9; color: #1c6c52; border-radius: 999px; padding: 5px 9px; font-size: 11px; }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 10px; padding: 17px; margin-bottom: 14px; }.panel h2 { margin: 0 0 10px; font-size: 16px; }.grid { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 10px; margin-bottom: 12px; }.item-row { display: grid; grid-template-columns: 1fr 1.4fr .7fr .9fr .6fr .8fr auto; gap: 8px; align-items: end; padding: 10px; background: #f7fbfa; border-radius: 7px; margin-bottom: 8px; }label { display: grid; gap: 5px; color: #466a63; font-size: 12px; }input, select { border: 1px solid #cfe2dd; border-radius: 6px; padding: 8px; font: inherit; font-size: 13px; background: #fff; }button { border: 1px solid #c8ded8; background: #fff; color: #246558; border-radius: 6px; padding: 7px 11px; font: inherit; font-size: 12px; cursor: pointer; }button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }button:disabled { opacity: .5; }.row-actions { display: flex; gap: 8px; justify-content: end; }
.plan-card { border: 1px solid #e0eeea; border-radius: 9px; padding: 13px; margin-top: 11px; }.plan-head { display: flex; justify-content: space-between; gap: 12px; }.plan-head strong { font-size: 14px; }.plan-head p { margin: 4px 0 0; font-size: 12px; color: #718b85; }.state { margin-left: 8px; padding: 2px 7px; border-radius: 999px; background: #fff2d7; color: #875b12; font-size: 11px; }.head-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }.basis { margin-top: 8px; padding: 7px 9px; background: #e6f6ef; color: #1e6752; border-radius: 6px; font-size: 12px; }.basis.rejected { background: #fff0eb; color: #963d2e; }
table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 10px; }th { text-align: left; color: #6c8983; font-weight: 500; padding: 7px; border-bottom: 1px solid #e6f0ed; }td { padding: 8px 7px; border-bottom: 1px solid #edf4f2; }.actions { display: flex; gap: 4px; }.muted { color: #819791; font-size: 12px; }.error, .notice { padding: 10px 12px; border-radius: 7px; font-size: 12px; }.error { background: #fff0eb; color: #963d2e; }.notice { background: #e6f6ef; color: #1e6752; }.empty { text-align: center; color: #8ca19c; padding: 18px; }
@media (max-width: 900px) { .grid, .item-row { grid-template-columns: 1fr; }.plan-page { padding: 15px; }.plan-head { display: block; }.head-actions { margin-top: 8px; } }
</style>
