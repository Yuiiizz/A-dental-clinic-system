<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { useRoute } from 'vue-router';
import { createClinicalApi, type ClinicalOverview } from '@/services/clinical';

const session = useSessionStore();
const route = useRoute();
const api = createClinicalApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1', timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));
const data = ref<ClinicalOverview | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');
const visitForm = ref({ appointmentId: '', allergy: '无已知', snapshot: '', notes: '', followupOutcome: '' });
function shanghaiToday(): string {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}
function addDays(day: string, count: number): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, date + count)).toISOString().slice(0, 10);
}
const today = shanghaiToday();
const planForm = ref({ visitId: '', from: today, to: addDays(today, 14), duration: 30, purpose: '复诊复查', serviceItemId: '' });
function requestedTab(): 'appointments' | 'visits' | 'plans' {
  return route.query.tab === 'visits' || route.query.tab === 'plans' ? route.query.tab : 'appointments';
}
const active = ref<'appointments' | 'visits' | 'plans'>(requestedTab());
watch(() => route.query.tab, () => { active.value = requestedTab(); });
const appointments = computed(() => data.value?.appointments ?? []);
const selectedAppointment = computed(() => appointments.value.find(a => a.appointment_id === visitForm.value.appointmentId));
const visits = computed(() => data.value?.visits ?? []);
const planSourceVisits = computed(() => data.value?.plan_source_visits ?? []);
const plans = computed(() => data.value?.plans ?? []);
const planServices = computed(() => data.value?.plan_services ?? []);
watch(() => visitForm.value.appointmentId, () => { visitForm.value.followupOutcome = ''; });
function shanghai(iso: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).formatToParts(new Date(iso));
  const m = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  return `${m.year}-${m.month}-${m.day} ${m.hour === '24' ? '00' : m.hour}:${m.minute}`;
}
async function load(): Promise<void> {
  loading.value = true; error.value = '';
  try {
    const result = await api.overview();
    data.value = result;
    if (!result.appointments.some(a => a.appointment_id === visitForm.value.appointmentId))
      visitForm.value.appointmentId = result.appointments[0]?.appointment_id ?? '';
    if (!result.plan_source_visits.some(v => v.visit_id === planForm.value.visitId))
      planForm.value.visitId = result.plan_source_visits[0]?.visit_id ?? '';
  } catch (e) { error.value = e instanceof ApiError ? e.message : '临床数据读取失败。'; }
  finally { loading.value = false; }
}
async function startVisit(): Promise<void> {
  if (loading.value || !visitForm.value.appointmentId) return;
  loading.value=true; error.value=''; message.value='';
  try {
    const client=createHttpClient({baseUrl:import.meta.env.VITE_API_BASE_URL || '/api/v1',getCsrfToken:()=>session.csrfToken});
    await client.post(`/staff/appointments/${visitForm.value.appointmentId}/start-visit`,{});
    message.value='已记录实际开始接诊时间。'; await load();
  } catch(e) { error.value=e instanceof ApiError ? e.message : '开始接诊失败'; }
  finally { loading.value=false; }
}
async function completeVisit(): Promise<void> {
  error.value = ''; message.value = '';
  if (!visitForm.value.appointmentId) { error.value = '请选择候诊/接诊中的预约。'; return; }
  if ((visitForm.value.allergy === '有' || visitForm.value.allergy === '不确定') && !visitForm.value.snapshot.trim()) {
    error.value = '选择“有/不确定”时必须填写过敏核对快照。'; return;
  }
  if (selectedAppointment.value?.followup_plan_id && (!visitForm.value.followupOutcome || !visitForm.value.notes.trim())) {
    error.value = '关联复诊须由接诊医生确认目标是否达到，并填写临床记录。'; return;
  }
  loading.value = true;
  try { await api.completeVisit(visitForm.value.appointmentId, visitForm.value.allergy, visitForm.value.snapshot.trim(), visitForm.value.notes.trim(),
    selectedAppointment.value?.followup_plan_id ? visitForm.value.followupOutcome === 'achieved' : undefined);
    message.value = '就诊记录与复诊结果已保存。'; visitForm.value.followupOutcome = ''; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '完成就诊失败。'; }
  finally { loading.value = false; }
}
async function createPlan(): Promise<void> {
  error.value = ''; message.value = '';
  if (!planSourceVisits.value.some(v => v.visit_id === planForm.value.visitId) || !planForm.value.purpose.trim()) { error.value = '请选择本人完成的来源就诊记录并填写复诊目的。'; return; }
  if (planForm.value.to < planForm.value.from) { error.value = '复诊结束日期不能早于开始日期。'; return; }
  loading.value = true;
  try { await api.createPlan(planForm.value.visitId, planForm.value.from, planForm.value.to, Number(planForm.value.duration), planForm.value.purpose.trim(), planForm.value.serviceItemId); message.value = '复诊计划已创建。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '创建复诊计划失败。'; }
  finally { loading.value = false; }
}
onMounted(() => { void load(); });
</script>

<template>
  <main class="clinical-page">
    <header><div><RouterLink to="/">← 工作台</RouterLink><h1>就诊与复诊工作台</h1><p>真实接口 · 完成就诊写入 visit_record，安排复诊写入 followup_plan。</p></div><span class="real-badge">实时接口</span></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="loading" class="muted">正在读取真实临床数据…</p>
    <template v-if="data">
      <div class="summary">
        <div><span>候诊/接诊中</span><strong>{{ appointments.length }}</strong></div>
        <div><span>已记录就诊</span><strong>{{ visits.length }}</strong></div>
        <div><span>复诊计划</span><strong>{{ plans.length }}</strong></div>
      </div>
      <div class="tabs"><button :class="{ active: active === 'appointments' }" @click="active = 'appointments'">完成就诊</button><button :class="{ active: active === 'visits' }" @click="active = 'visits'">就诊记录</button><button :class="{ active: active === 'plans' }" @click="active = 'plans'">复诊计划</button><button type="button" :disabled="loading" @click="load">刷新接诊列表</button></div>

      <section v-if="active === 'appointments'" class="panel">
        <h2>完成就诊</h2>
        <p class="hint">先由预约确认医生开始接诊，再完成记录；完成后预约状态变为已完成。过敏核对必须明确。</p>
        <p v-if="appointments.length === 0" class="hint" role="status">暂无可接诊预约。已确认的预约须在预约当天由前台签到，进入候诊后才会出现在这里。</p>
        <label>就诊用户<select v-model="visitForm.appointmentId" :disabled="appointments.length === 0"><option v-if="appointments.length === 0" value="">暂无候诊或接诊中的预约</option><option v-for="a in appointments" :key="a.appointment_id" :value="a.appointment_id">{{ a.patient_name }} · {{ shanghai(a.starts_at) }} · {{ a.state }}</option></select></label>
        <div class="grid">
          <label>过敏核对<select v-model="visitForm.allergy"><option value="无已知">无已知</option><option value="有">有</option><option value="不确定">不确定</option></select></label>
          <label>过敏快照<input v-model="visitForm.snapshot" maxlength="300" placeholder="有/不确定时必填" /></label>
        </div>
        <label>临床记录<textarea v-model="visitForm.notes" maxlength="1000" placeholder="处置与医嘱摘要"></textarea></label>
        <label v-if="selectedAppointment?.followup_plan_id">复诊计划 {{ selectedAppointment.followup_plan_id }} 的目标结果
          <select v-model="visitForm.followupOutcome"><option value="">请选择医生确认结果</option><option value="achieved">已达到，关闭计划</option><option value="continue">未达到，继续协调复诊</option></select>
        </label>
        <button :disabled="loading || selectedAppointment?.state !== '候诊中'" @click="startVisit">开始接诊</button>
        <button class="primary" :disabled="loading || selectedAppointment?.state !== '接诊中'" @click="completeVisit">完成就诊并写入记录</button>
      </section>

      <section v-else-if="active === 'visits'" class="panel">
        <h2>就诊记录</h2>
        <table><thead><tr><th>患者</th><th>医生</th><th>完成时间</th><th>过敏</th><th>记录</th></tr></thead><tbody>
          <tr v-for="v in visits" :key="v.visit_id"><td>{{ v.patient_name }}</td><td>{{ v.dentist_name }}</td><td>{{ shanghai(v.completed_at) }}</td><td>{{ v.allergy_result }}</td><td class="notes">{{ v.clinical_notes || '—' }}</td></tr>
          <tr v-if="visits.length === 0"><td colspan="5" class="empty">暂无就诊记录。</td></tr>
        </tbody></table>
      </section>

      <section v-else class="panel">
        <h2>新建复诊计划</h2>
        <p class="hint">来源只能选本人完成的就诊；普通预约不会自动关闭复诊计划。</p>
        <p v-if="planSourceVisits.length === 0" class="hint" role="status">暂无本人已完成的就诊记录，请先完成一次接诊。</p>
        <div class="grid">
          <label>来源就诊<select v-model="planForm.visitId" :disabled="planSourceVisits.length === 0"><option v-if="planSourceVisits.length === 0" value="">暂无本人已完成就诊</option><option v-for="v in planSourceVisits" :key="v.visit_id" :value="v.visit_id">{{ v.patient_name }} · {{ shanghai(v.completed_at) }} · {{ v.clinical_notes }}</option></select></label>
          <label>开始日期<input v-model="planForm.from" type="date" /></label><label>结束日期<input v-model="planForm.to" type="date" /></label>
          <label>时长（分钟）<input v-model.number="planForm.duration" type="number" min="5" max="480" /></label>
          <label>预约项目（可选）<select v-model="planForm.serviceItemId"><option value="">暂不指定</option><option v-for="item in planServices" :key="item.item_id" :value="item.item_id">{{ item.name }}</option></select></label>
          <label class="wide">目的<input v-model="planForm.purpose" maxlength="200" /></label>
        </div>
        <button class="primary" :disabled="loading || planSourceVisits.length === 0" @click="createPlan">创建复诊计划</button>
        <table class="plans"><thead><tr><th>患者</th><th>医生</th><th>建议窗口</th><th>预约项目</th><th>目的</th><th>状态</th></tr></thead><tbody>
          <tr v-for="p in plans" :key="p.plan_id"><td>{{ p.patient_name }}</td><td>{{ p.dentist_name }}</td><td>{{ p.suggested_from }} – {{ p.suggested_to }}</td><td>{{ p.service_item_name || '暂未指定' }}</td><td>{{ p.purpose }}</td><td>{{ p.state }}</td></tr>
          <tr v-if="plans.length === 0"><td colspan="6" class="empty">暂无复诊计划。</td></tr>
        </tbody></table>
      </section>
    </template>
  </main>
</template>

<style scoped>
.clinical-page { min-height: 100vh; background: #f2f8f7; padding: 22px 26px 40px; color: #193d39; }
header { display: flex; justify-content: space-between; align-items: start; gap: 14px; margin-bottom: 14px; }header a { color: #256e60; text-decoration: none; font-size: 13px; }h1 { margin: 7px 0 3px; font-size: 24px; }header p { margin: 0; color: #68847f; font-size: 13px; }.real-badge { background: #dff4e9; color: #1c6c52; border-radius: 999px; padding: 5px 9px; font-size: 11px; }
.summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px; }.summary div { background: #fff; border: 1px solid #dbeae6; border-radius: 9px; padding: 13px; display: grid; gap: 5px; }.summary span { color: #728d87; font-size: 12px; }.summary strong { font-size: 23px; color: #15594e; }
.tabs { display: flex; gap: 7px; margin-bottom: 12px; }.tabs button { border: 1px solid #c9e0da; border-radius: 7px; background: #fff; padding: 7px 13px; color: #426b64; font: inherit; font-size: 13px; cursor: pointer; }.tabs button.active { background: #197c6b; color: #fff; border-color: #197c6b; }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 10px; padding: 17px; }.panel h2 { margin: 0 0 6px; font-size: 16px; }.hint { margin: 0 0 12px; color: #718b85; font-size: 12px; }.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }.wide { grid-column: span 2; }label { display: grid; gap: 5px; color: #466a63; font-size: 12px; margin-bottom: 9px; }input, select, textarea { border: 1px solid #cfe2dd; border-radius: 6px; padding: 8px; font: inherit; font-size: 13px; background: #fff; }textarea { min-height: 70px; resize: vertical; }button { border: 1px solid #c8ded8; background: #fff; color: #246558; border-radius: 6px; padding: 8px 12px; font: inherit; font-size: 12px; cursor: pointer; }button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }button:disabled { opacity: .5; }
table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 14px; }th { text-align: left; color: #6c8983; font-weight: 500; padding: 8px; border-bottom: 1px solid #e6f0ed; }td { padding: 9px 8px; border-bottom: 1px solid #edf4f2; vertical-align: top; }.notes { max-width: 430px; white-space: pre-wrap; }.empty { text-align: center; color: #8ca19c; padding: 20px; }.error, .notice { padding: 10px 12px; border-radius: 7px; font-size: 12px; }.error { background: #fff0eb; color: #963d2e; }.notice { background: #e6f6ef; color: #1e6752; }.muted { color: #819791; font-size: 12px; }
@media (max-width: 760px) { .grid { grid-template-columns: 1fr; }.wide { grid-column: auto; }.clinical-page { padding: 15px; }.notes { max-width: 200px; } }
</style>
