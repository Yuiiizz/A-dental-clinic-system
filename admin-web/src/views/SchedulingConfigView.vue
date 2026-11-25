<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { createSchedulingApi, type SchedulingOverview } from '@/services/scheduling';

const session = useSessionStore();
const route = useRoute();
const api = createSchedulingApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));
const data = ref<SchedulingOverview | null>(null);
const loading = ref(false);
const message = ref('');
const error = ref('');
function shanghaiToday(): string {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}
const schedule = ref({ dentistId: '', branchId: '', date: shanghaiToday(), start: '09:00', end: '12:00' });
const selectedItems = ref<string[]>([]);
const service = ref({ name: '', price: '', minutes: 30, branchId: '' });
const resource = ref({ name: '', branchId: '' });
const scopeDentistId = ref('');
const scopeItemIds = ref<string[]>([]);
const scopeBasis = ref('');
function requestedTab(): 'schedules' | 'catalog' | 'chairs' | 'scope' {
  return route.query.tab === 'catalog' || route.query.tab === 'chairs' || route.query.tab === 'scope' ? route.query.tab : 'schedules';
}
const activeTab = ref<'schedules' | 'catalog' | 'chairs' | 'scope'>(requestedTab());
watch(() => route.query.tab, () => { activeTab.value = requestedTab(); });

const branches = computed(() => data.value?.branches ?? []);
const dentists = computed(() => data.value?.dentists ?? []);
const distinctDentists = computed(() => dentists.value.filter((d, index, all) => all.findIndex(x => x.staff_id === d.staff_id) === index));
const serviceItems = computed(() => data.value?.service_items ?? []);
const schedules = computed(() => data.value?.schedules ?? []);
const scopes = computed(() => data.value?.scopes ?? []);
const resources = computed(() => data.value?.resources ?? []);
function currentScope(staffId: string): string[] {
  return scopes.value.filter(s => s.staff_id === staffId).map(s => s.item_id).sort();
}
watch(scopeDentistId, id => { scopeItemIds.value = currentScope(id); scopeBasis.value = ''; });

function displayInstant(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}
function dentistFor(itemId: string): string {
  return scopes.value.filter((s) => s.item_id === itemId).map((s) => dentists.value.find((d) => d.staff_id === s.staff_id)?.name).filter(Boolean).join('、') || '未配置';
}
async function load(): Promise<void> {
  if (!session.me) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.overview();
    data.value = result;
    if (!schedule.value.branchId) schedule.value.branchId = result.branches[0]?.branch_id ?? '';
    if (!schedule.value.dentistId) schedule.value.dentistId = result.dentists[0]?.staff_id ?? '';
    if (!service.value.branchId) service.value.branchId = result.branches[0]?.branch_id ?? '';
    if (!resource.value.branchId) resource.value.branchId = result.branches[0]?.branch_id ?? '';
    if (!scopeDentistId.value) scopeDentistId.value = result.dentists[0]?.staff_id ?? '';
    if (selectedItems.value.length === 0) selectedItems.value = result.service_items.slice(0, 2).map((s) => s.item_id);
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '排班配置读取失败。';
  } finally {
    loading.value = false;
  }
}
async function createSchedule(): Promise<void> {
  message.value = '';
  error.value = '';
  if (!schedule.value.dentistId || !schedule.value.branchId || !schedule.value.date || !schedule.value.start || !schedule.value.end) {
    error.value = '请完整选择医生、门店、日期和起止时间。'; return;
  }
  if (schedule.value.date < shanghaiToday()) { error.value = '不能创建过去日期的排班，请重新选择日期。'; return; }
  if (schedule.value.end <= schedule.value.start) { error.value = '结束时间必须晚于开始时间。'; return; }
  if (selectedItems.value.length === 0) { error.value = '至少选择一个服务项目。'; return; }
  loading.value = true;
  try {
    await api.createSchedule({ ...schedule.value, itemIds: selectedItems.value });
    message.value = '排班草稿已创建；核对后发布。';
    await load();
  } catch (e) { error.value = e instanceof ApiError ? e.message : '排班创建失败。'; }
  finally { loading.value = false; }
}
async function publish(id: string): Promise<void> {
  loading.value = true; error.value = ''; message.value = '';
  try { await api.publish(id); message.value = '排班已发布，可供预约。'; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '发布失败。'; }
  finally { loading.value = false; }
}
async function createService(): Promise<void> {
  error.value = ''; message.value = '';
  if (!service.value.name.trim() || !service.value.price.trim() || !service.value.branchId) { error.value = '请填写项目名称、价格和门店。'; return; }
  if (!/^\d+(\.\d{1,2})?$/.test(service.value.price.trim())) { error.value = '价格必须为最多两位小数的非负数。'; return; }
  loading.value = true;
  try { await api.createService({ ...service.value, name: service.value.name.trim(), price: service.value.price.trim(), minutes: Number(service.value.minutes) }); message.value = '服务项目已创建并加入门店。'; service.value = { name: '', price: '', minutes: 30, branchId: data.value?.branches[0]?.branch_id ?? '' }; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '创建项目失败。'; }
  finally { loading.value = false; }
}
async function createResource(): Promise<void> {
  error.value = ''; message.value = '';
  if (!resource.value.name.trim() || !resource.value.branchId) { error.value = '请填写诊椅名称和门店。'; return; }
  loading.value = true;
  try { await api.createResource({ ...resource.value, name: resource.value.name.trim() }); message.value = '诊椅资源已创建。'; resource.value = { name: '', branchId: data.value?.branches[0]?.branch_id ?? '' }; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '创建诊椅失败。'; }
  finally { loading.value = false; }
}
async function saveScope(): Promise<void> {
  error.value = ''; message.value = '';
  if (!scopeDentistId.value) { error.value = '请先选择牙医。'; return; }
  if (!scopeBasis.value.trim()) { error.value = '请填写服务范围核实依据。'; return; }
  const before = currentScope(scopeDentistId.value);
  const after = [...new Set(scopeItemIds.value)].sort();
  if (before.join(',') === after.join(',')) { message.value = '医生服务范围没有变化。'; return; }
  if (before.some(id => !after.includes(id)) && !window.confirm('移除服务可能影响现有排班。服务端会拒绝影响未结束排班的变更；确认提交吗？')) return;
  loading.value = true;
  try {
    await api.setDoctorServices(scopeDentistId.value, after, scopeBasis.value.trim());
    message.value = '医生服务范围已保存。新排班仍需核对门店、任职、资格和营业时间。';
    await load();
    scopeItemIds.value = currentScope(scopeDentistId.value);
    scopeBasis.value = '';
  } catch (e) { error.value = e instanceof ApiError ? e.message : '保存医生服务范围失败；请刷新后核对。'; }
  finally { loading.value = false; }
}
onMounted(() => { void load(); });
</script>

<template>
  <main class="scheduling-page">
    <header>
      <div>
        <RouterLink to="/">← 工作台</RouterLink>
        <h1>排班与基础配置</h1>
        <p>真实接口页面 · 组织负责人维护服务目录、诊椅和医生排班。</p>
      </div>
      <span class="real-badge">真实接口</span>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="loading" class="muted">正在读取排班配置…</p>
    <template v-if="data">
      <div class="summary">
        <div><span>营业门店</span><strong>{{ branches.length }}</strong></div>
        <div><span>可排班医生</span><strong>{{ dentists.length }}</strong></div>
        <div><span>服务项目</span><strong>{{ serviceItems.length }}</strong></div>
        <div><span>已发布排班</span><strong>{{ schedules.filter(s => s.state === '已发布').length }}</strong></div>
      </div>

      <div class="tabs">
        <button :class="{ active: activeTab === 'schedules' }" @click="activeTab = 'schedules'">排班</button>
        <button :class="{ active: activeTab === 'catalog' }" @click="activeTab = 'catalog'">服务目录</button>
        <button :class="{ active: activeTab === 'chairs' }" @click="activeTab = 'chairs'">诊椅资源</button>
        <button :class="{ active: activeTab === 'scope' }" @click="activeTab = 'scope'">医生服务范围</button>
      </div>

      <section v-if="activeTab === 'schedules'" class="panel">
        <div class="panel-head"><div><h2>新建排班</h2><p>先建草稿；发布后患者端才可预约。</p></div></div>
        <div class="form-grid">
          <label>医生<select v-model="schedule.dentistId"><option v-for="d in dentists" :key="d.staff_id" :value="d.staff_id">{{ d.name }} · {{ d.job_title }}</option></select></label>
          <label>门店<select v-model="schedule.branchId"><option v-for="b in branches" :key="b.branch_id" :value="b.branch_id">{{ b.name }}</option></select></label>
          <label>日期<input v-model="schedule.date" type="date" :min="shanghaiToday()" /></label>
          <label>开始<input v-model="schedule.start" type="time" /></label>
          <label>结束<input v-model="schedule.end" type="time" /></label>
        </div>
        <fieldset class="items"><legend>可接诊项目</legend>
          <label v-for="item in serviceItems" :key="item.item_id" class="check"><input v-model="selectedItems" type="checkbox" :value="item.item_id" /> {{ item.name }} · {{ item.minutes }}分钟 · ¥{{ item.price }}</label>
        </fieldset>
        <button class="primary" :disabled="loading" @click="createSchedule">创建排班草稿</button>

        <h2 class="list-title">排班列表</h2>
        <div class="table-wrap">
          <table>
            <thead><tr><th>医生</th><th>门店</th><th>时间（上海）</th><th>项目</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="s in schedules" :key="s.schedule_id">
                <td>{{ s.dentist_name }}</td><td>{{ s.branch_name }}</td>
                <td>{{ displayInstant(s.starts_at) }} – {{ displayInstant(s.ends_at) }}</td>
                <td>{{ s.item_ids.map(id => serviceItems.find(i => i.item_id === id)?.name).filter(Boolean).join('、') }}</td>
                <td><span :class="['badge', s.state === '已发布' ? 'good' : 'warn']">{{ s.state }}</span></td>
                <td>v{{ s.version }}</td>
                <td><button v-if="s.state === '草稿'" :disabled="loading" @click="publish(s.schedule_id)">发布</button><span v-else class="muted">已发布</span></td>
              </tr>
              <tr v-if="schedules.length === 0"><td colspan="7" class="empty">暂无排班。</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else-if="activeTab === 'catalog'" class="panel">
        <div class="panel-head"><div><h2>新增服务项目</h2><p>项目会同时加入所选门店。</p></div></div>
        <div class="form-grid">
          <label>名称<input v-model="service.name" maxlength="100" /></label>
          <label>价格（元）<input v-model="service.price" inputmode="decimal" /></label>
          <label>时长（分钟）<input v-model.number="service.minutes" type="number" min="5" max="480" /></label>
          <label>门店<select v-model="service.branchId"><option v-for="b in branches" :key="b.branch_id" :value="b.branch_id">{{ b.name }}</option></select></label>
        </div>
        <button class="primary" :disabled="loading" @click="createService">创建服务项目</button>
        <table class="catalog-table"><thead><tr><th>项目</th><th>分类</th><th>价格</th><th>时长</th><th>可执行医生</th></tr></thead><tbody>
          <tr v-for="item in serviceItems" :key="item.item_id"><td>{{ item.name }}</td><td>{{ item.category_name }}</td><td>¥{{ item.price }}</td><td>{{ item.minutes }} 分钟</td><td>{{ dentistFor(item.item_id) }}</td></tr>
        </tbody></table>
      </section>

      <section v-else-if="activeTab === 'chairs'" class="panel">
        <div class="panel-head"><div><h2>新增诊椅</h2><p>真实资源记录，可用于后续资源占用。</p></div></div>
        <div class="form-grid">
          <label>诊椅名称<input v-model="resource.name" maxlength="80" /></label>
          <label>门店<select v-model="resource.branchId"><option v-for="b in branches" :key="b.branch_id" :value="b.branch_id">{{ b.name }}</option></select></label>
        </div>
        <button class="primary" :disabled="loading" @click="createResource">创建诊椅</button>
        <table class="catalog-table"><thead><tr><th>诊椅</th><th>类型</th><th>门店</th><th>状态</th></tr></thead><tbody>
          <tr v-for="r in resources" :key="r.resource_id"><td>{{ r.name }}</td><td>{{ r.type }}</td><td>{{ r.branch_name }}</td><td>{{ r.state }}</td></tr>
        </tbody></table>
      </section>
      <section v-else class="panel">
        <div class="panel-head"><div><h2>医生服务范围</h2><p>按牙医维护全局可开展项目；门店是否开放项目、资格和排班另行核对。</p></div></div>
        <label>牙医<select v-model="scopeDentistId" :disabled="loading"><option v-for="d in distinctDentists" :key="d.staff_id" :value="d.staff_id">{{ d.name }} · {{ d.job_title }}</option></select></label>
        <fieldset class="items" :disabled="loading || !scopeDentistId"><legend>可开展项目</legend>
          <label v-for="item in serviceItems" :key="item.item_id" class="check"><input v-model="scopeItemIds" type="checkbox" :value="item.item_id" />{{ item.name }}</label>
        </fieldset>
        <label>核实依据<input v-model="scopeBasis" maxlength="300" placeholder="填写实际核对的材料或批准记录，不填证件号码" /></label>
        <p class="muted">取消项目会先检查该医生未结束的相关排班；有影响时返回冲突且不保存。保存后刷新可核对服务器记录。</p>
        <button class="primary" :disabled="loading || !scopeDentistId || !scopeBasis.trim()" @click="saveScope">保存服务范围</button>
      </section>
    </template>
  </main>
</template>

<style scoped>
.scheduling-page { min-height: 100vh; background: #f2f8f7; padding: 22px 26px 40px; color: #193d39; }
header { display: flex; justify-content: space-between; align-items: start; gap: 14px; margin-bottom: 14px; }
header a { color: #256e60; text-decoration: none; font-size: 13px; } h1 { margin: 7px 0 3px; font-size: 24px; } header p { margin: 0; color: #68847f; font-size: 13px; }
.real-badge { background: #dff4e9; color: #1c6c52; border-radius: 999px; padding: 5px 9px; font-size: 11px; }
.summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }.summary div { background: #fff; border: 1px solid #dbeae6; border-radius: 9px; padding: 13px; display: grid; gap: 5px; }.summary span { color: #728d87; font-size: 12px; }.summary strong { font-size: 23px; color: #15594e; }
.tabs { display: flex; gap: 7px; margin-bottom: 12px; }.tabs button { border: 1px solid #c9e0da; border-radius: 7px; background: #fff; padding: 7px 13px; color: #426b64; font: inherit; font-size: 13px; cursor: pointer; }.tabs button.active { background: #197c6b; color: #fff; border-color: #197c6b; }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 10px; padding: 17px; }.panel-head h2 { margin: 0; font-size: 16px; }.panel-head p { margin: 4px 0 12px; color: #718b85; font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }label { display: grid; gap: 5px; color: #466a63; font-size: 12px; }input, select { border: 1px solid #cfe2dd; border-radius: 6px; padding: 8px; font: inherit; font-size: 13px; background: #fff; }
.items { border: 1px solid #dbeae6; border-radius: 8px; padding: 10px; margin-bottom: 12px; display: flex; flex-wrap: wrap; gap: 8px 16px; }.items legend { padding: 0 5px; color: #5a7770; font-size: 12px; }.check { display: flex; align-items: center; gap: 5px; font-size: 12px; }
button { border: 1px solid #c8ded8; background: #fff; color: #246558; border-radius: 6px; padding: 7px 11px; font: inherit; font-size: 12px; cursor: pointer; }button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }button:disabled { opacity: .5; cursor: wait; }
.table-wrap { overflow: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 14px; }.list-title { font-size: 15px; margin: 20px 0 0; }th { text-align: left; color: #6c8983; font-weight: 500; padding: 8px; border-bottom: 1px solid #e6f0ed; white-space: nowrap; }td { padding: 9px 8px; border-bottom: 1px solid #edf4f2; }.badge { padding: 3px 7px; border-radius: 999px; background: #edf4f2; font-size: 11px; }.badge.good { background: #dff4e9; color: #1c6c52; }.badge.warn { background: #fff2d7; color: #875b12; }.muted { color: #819791; font-size: 12px; }.error, .notice { padding: 10px 12px; border-radius: 7px; font-size: 12px; }.error { background: #fff0eb; color: #963d2e; }.notice { background: #e6f6ef; color: #1e6752; }.catalog-table { margin-top: 16px; }.empty { text-align: center; color: #8ca19c; padding: 20px; }
@media (max-width: 800px) { .form-grid { grid-template-columns: 1fr; }.summary { grid-template-columns: 1fr 1fr; }.scheduling-page { padding: 15px; } }
</style>
