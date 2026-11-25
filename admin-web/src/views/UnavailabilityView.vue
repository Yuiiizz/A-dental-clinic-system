<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ApiError, createHttpClient } from '@/services/http';
import {
  createUnavailabilityApi,
  type DentistUnavailability,
  type ResourceUnavailability,
  type UnavailabilityBranch,
  type UnavailabilityDentist,
  type UnavailabilityResource,
} from '@/services/unavailability';
import { useSessionStore } from '@/stores/session';

const session = useSessionStore();
const api = createUnavailabilityApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));

type Tab = 'doctor' | 'resource';
const activeTab = ref<Tab>('doctor');
const loading = ref(false);
const error = ref('');
const notice = ref('');
const doctorForbidden = ref(false);
const resourceForbidden = ref(false);
const branches = ref<UnavailabilityBranch[]>([]);
const dentists = ref<UnavailabilityDentist[]>([]);
const doctors = ref<DentistUnavailability[]>([]);
const resources = ref<UnavailabilityResource[]>([]);
const resourceItems = ref<ResourceUnavailability[]>([]);
const branchId = ref('');
const doctorState = ref<'有效' | '已撤销' | ''>('');
const resourceState = ref<'有效' | '已撤销' | ''>('');
let generation = 0;
let branchRequest = 0;
let dentistRequest = 0;
let doctorRequest = 0;
let resourceRequest = 0;

function defaultTime(offsetMinutes: number): string {
  const d = new Date(Date.now() + offsetMinutes * 60_000);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(d).map((p) => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
const doctorForm = ref({
  doctorId: '',
  startsAt: defaultTime(60),
  endsAt: defaultTime(120),
  reason: '',
});
const resourceForm = ref({
  kind: '维护' as '维护' | '故障',
  resourceId: '',
  startsAt: defaultTime(60),
  endsAt: defaultTime(120),
  reason: '',
});
const doctorBusy = ref(false);
const resourceBusy = ref(false);
const doctorKey = ref('');
const doctorFingerprint = ref('');
const resourceKey = ref('');
const resourceFingerprint = ref('');
const resolveTarget = ref<{ kind: Tab; id: string; label: string } | null>(null);
const resolveBasis = ref('');
const resolveType = ref<'误登记撤销' | '已修复'>('已修复');
const resolveKey = ref('');
const resolveBusy = ref(false);
const resolveError = ref('');
let doctorOperation = 0;
let resourceOperation = 0;
let resolveOperation = 0;

const activeDoctorCount = computed(() => doctors.value.filter((item) => item.state === '有效').length);
const activeResourceCount = computed(() => resourceItems.value.filter((item) => item.state === '有效').length);
const affectedCount = computed(() => {
  const rows = activeTab.value === 'doctor'
    ? doctors.value.filter((item) => item.state === '有效')
    : resourceItems.value.filter((item) => item.state === '有效');
  return rows.reduce((sum, item) => sum + item.affected_count, 0);
});
const selectedBranch = computed(() => branches.value.find((item) => item.branch_id === branchId.value));

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}
function apiError(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE') {
      return '结果未确认，请保留原输入并用相同幂等键重试，或刷新后核对。';
    }
    if (cause.status === 403) return '当前账号没有该操作权限；服务端未执行。';
    if (cause.status === 409) return '记录已变化或请求冲突；请刷新读取最新状态后再操作。';
    return cause.message;
  }
  return '结果未确认，请保留原输入并用相同幂等键重试。';
}
function newKey(): string {
  return crypto.randomUUID();
}
function ensureKey(kind: Tab, content: string): string {
  if (kind === 'doctor') {
    if (doctorFingerprint.value !== content) {
      doctorFingerprint.value = content;
      doctorKey.value = newKey();
    }
    return doctorKey.value;
  }
  if (resourceFingerprint.value !== content) {
    resourceFingerprint.value = content;
    resourceKey.value = newKey();
  }
  return resourceKey.value;
}
function resetDoctorForm(): void {
  doctorForm.value = { doctorId: '', startsAt: defaultTime(60), endsAt: defaultTime(120), reason: '' };
  doctorKey.value = '';
  doctorFingerprint.value = '';
}
function resetResourceForm(): void {
  resourceForm.value = { kind: '维护', resourceId: resources.value[0]?.resource_id ?? '', startsAt: defaultTime(60), endsAt: defaultTime(120), reason: '' };
  resourceKey.value = '';
  resourceFingerprint.value = '';
}
function localInstant(value: string): string {
  return `${value}:00+08:00`;
}
function validateWindow(start: string, end: string): string {
  if (!start || !end) return '请填写开始和结束时间。';
  if (localInstant(end) <= localInstant(start)) return '结束时间必须晚于开始时间。';
  return '';
}
function filteredDoctors(): DentistUnavailability[] {
  return doctorState.value === '' ? doctors.value : doctors.value.filter((item) => item.state === doctorState.value);
}
function filteredResources(): ResourceUnavailability[] {
  return resourceState.value === '' ? resourceItems.value : resourceItems.value.filter((item) => item.state === resourceState.value);
}

async function loadBranches(): Promise<void> {
  const own = ++branchRequest;
  const gen = generation;
  try {
    const rows = await api.branches();
    if (own !== branchRequest || gen !== generation) return;
    branches.value = rows;
    if (!branchId.value || !branches.value.some((item) => item.branch_id === branchId.value)) {
      branchId.value = branches.value[0]?.branch_id ?? '';
    }
  } catch (cause) {
    if (own === branchRequest && gen === generation) error.value = apiError(cause);
  }
}
async function loadDentists(): Promise<void> {
  const own = ++dentistRequest;
  const gen = generation;
  try {
    const all = await api.dentists();
    if (own !== dentistRequest || gen !== generation) return;
    const seen = new Set<string>();
    dentists.value = all.filter((item) => {
      if (seen.has(item.staff_id)) return false;
      seen.add(item.staff_id);
      return true;
    });
    if (!doctorForm.value.doctorId) doctorForm.value.doctorId = dentists.value[0]?.staff_id ?? '';
  } catch (cause) {
    if (own !== dentistRequest || gen !== generation) return;
    if (cause instanceof ApiError && cause.status === 403) {
      doctorForbidden.value = true;
      dentists.value = [];
    } else {
      error.value = apiError(cause);
    }
  }
}
async function loadDoctors(): Promise<void> {
  const own = ++doctorRequest;
  const gen = generation;
  doctorForbidden.value = false;
  try {
    const rows = await api.doctorList();
    if (own !== doctorRequest || gen !== generation) return;
    doctors.value = rows;
  } catch (cause) {
    if (own !== doctorRequest || gen !== generation) return;
    if (cause instanceof ApiError && cause.status === 403) {
      doctorForbidden.value = true;
      doctors.value = [];
      return;
    }
    error.value = apiError(cause);
  }
}
async function loadResources(): Promise<void> {
  if (!branchId.value) return;
  const own = ++resourceRequest;
  const gen = generation;
  resourceForbidden.value = false;
  try {
    const [options, rows] = await Promise.all([
      api.resources(branchId.value),
      api.resourceList(branchId.value),
    ]);
    if (own !== resourceRequest || gen !== generation) return;
    resources.value = options;
    resourceItems.value = rows;
    if (!resources.value.some((item) => item.resource_id === resourceForm.value.resourceId)) {
      resourceForm.value.resourceId = resources.value[0]?.resource_id ?? '';
    }
  } catch (cause) {
    if (own !== resourceRequest || gen !== generation) return;
    if (cause instanceof ApiError && cause.status === 403) {
      resourceForbidden.value = true;
      resources.value = [];
      resourceItems.value = [];
      return;
    }
    error.value = apiError(cause);
  }
}
async function init(): Promise<void> {
  const own = ++generation;
  loading.value = true;
  error.value = '';
  notice.value = '';
  await loadBranches();
  if (own !== generation) return;
  await Promise.all([loadDoctors(), loadDentists(), loadResources()]);
  if (own !== generation) return;
  loading.value = false;
}
async function changeTab(tab: Tab): Promise<void> {
  activeTab.value = tab;
  error.value = '';
  notice.value = '';
  if (tab === 'doctor' && doctors.value.length === 0) await loadDoctors();
  if (tab === 'resource' && resourceItems.value.length === 0) await loadResources();
}
async function changeBranch(): Promise<void> {
  error.value = '';
  notice.value = '';
  await loadResources();
}
async function createDoctor(): Promise<void> {
  if (doctorBusy.value || doctorForbidden.value) return;
  const windowError = validateWindow(doctorForm.value.startsAt, doctorForm.value.endsAt);
  if (!doctorForm.value.doctorId) { error.value = '请选择医生。'; return; }
  if (!doctorForm.value.reason.trim()) { error.value = '请填写停诊原因。'; return; }
  if (windowError) { error.value = windowError; return; }
  const content = JSON.stringify({ ...doctorForm.value, reason: doctorForm.value.reason.trim() });
  const key = ensureKey('doctor', content);
  const gen = generation;
  const operation = ++doctorOperation;
  doctorBusy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.createDoctor({
      doctorId: doctorForm.value.doctorId,
      startsAt: localInstant(doctorForm.value.startsAt),
      endsAt: localInstant(doctorForm.value.endsAt),
      reason: doctorForm.value.reason.trim(),
    }, key);
    if (gen !== generation || operation !== doctorOperation) return;
    notice.value = '医生停诊已登记；受影响预约仅为实时提示，尚未取消、释放或通知。';
    resetDoctorForm();
    await loadDoctors();
  } catch (cause) {
    if (gen === generation && operation === doctorOperation) error.value = apiError(cause);
  } finally {
    if (gen === generation && operation === doctorOperation) doctorBusy.value = false;
  }
}
async function createResource(): Promise<void> {
  if (resourceBusy.value || resourceForbidden.value) return;
  const windowError = validateWindow(resourceForm.value.startsAt, resourceForm.value.endsAt);
  if (!branchId.value) { error.value = '请选择门店。'; return; }
  if (!resourceForm.value.resourceId) { error.value = '请选择诊椅或设备。'; return; }
  if (!resourceForm.value.reason.trim()) { error.value = '请填写维护或故障原因。'; return; }
  if (windowError) { error.value = windowError; return; }
  const content = JSON.stringify({ branchId: branchId.value, ...resourceForm.value, reason: resourceForm.value.reason.trim() });
  const key = ensureKey('resource', content);
  const gen = generation;
  const operation = ++resourceOperation;
  resourceBusy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.createResource({
      branchId: branchId.value,
      resourceId: resourceForm.value.resourceId,
      kind: resourceForm.value.kind,
      startsAt: localInstant(resourceForm.value.startsAt),
      endsAt: localInstant(resourceForm.value.endsAt),
      reason: resourceForm.value.reason.trim(),
    }, key);
    if (gen !== generation || operation !== resourceOperation) return;
    notice.value = '资源维护/故障已登记；受影响预约仅为实时提示，尚未取消、释放或通知。';
    resetResourceForm();
    await loadResources();
  } catch (cause) {
    if (gen === generation && operation === resourceOperation) error.value = apiError(cause);
  } finally {
    if (gen === generation && operation === resourceOperation) resourceBusy.value = false;
  }
}
function openResolve(kind: Tab, id: string, label: string): void {
  resolveTarget.value = { kind, id, label };
  resolveBasis.value = '';
  resolveType.value = '已修复';
  resolveKey.value = newKey();
  resolveError.value = '';
}
function closeResolve(): void {
  if (resolveBusy.value) return;
  resolveTarget.value = null;
}
async function submitResolve(): Promise<void> {
  if (!resolveTarget.value || resolveBusy.value) return;
  if (!resolveBasis.value.trim()) { resolveError.value = '请填写人工核实依据。'; return; }
  const target = resolveTarget.value;
  const gen = generation;
  const operation = ++resolveOperation;
  resolveBusy.value = true;
  resolveError.value = '';
  try {
    if (target.kind === 'doctor') {
      await api.resolveDoctor(target.id, resolveBasis.value.trim(), resolveKey.value);
    } else {
      await api.resolveResource(target.id, resolveType.value, resolveBasis.value.trim(), resolveKey.value);
    }
    if (gen !== generation || operation !== resolveOperation) return;
    notice.value = '记录已解除；预约状态和占用未被自动改写。';
    resolveTarget.value = null;
    if (target.kind === 'doctor') await loadDoctors();
    else await loadResources();
  } catch (cause) {
    if (gen === generation && operation === resolveOperation) resolveError.value = apiError(cause);
  } finally {
    if (gen === generation && operation === resolveOperation) resolveBusy.value = false;
  }
}
function refresh(): Promise<void> {
  notice.value = '';
  if (activeTab.value === 'doctor') return loadDoctors();
  return loadResources();
}
onMounted(() => { void init(); });
watch(() => session.sessionRevision, () => {
  generation++;
  doctorOperation++; resourceOperation++; resolveOperation++;
  doctorBusy.value = false; resourceBusy.value = false; resolveBusy.value = false;
  doctors.value = [];
  resources.value = [];
  resourceItems.value = [];
  dentists.value = [];
  branches.value = [];
  branchId.value = '';
  doctorForbidden.value = false;
  resourceForbidden.value = false;
  doctorKey.value = '';
  resourceKey.value = '';
  doctorFingerprint.value = '';
  resourceFingerprint.value = '';
  doctorState.value = '';
  resourceState.value = '';
  error.value = '';
  notice.value = '';
  resolveTarget.value = null;
  resetDoctorForm();
  resetResourceForm();
  void init();
});
</script>

<template>
  <main class="unavailability-page">
    <header>
      <div>
        <RouterLink to="/">← 工作台</RouterLink>
        <h1>停诊与资源维护</h1>
        <p>真实接口 · 医生停诊仅唯一组织负责人；诊椅/设备维护仅本店配置负责人。登记和解除不自动改预约，也不代表已通知。</p>
      </div>
      <span class="real-badge">实时接口</span>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="loading" class="muted">正在读取停诊与资源维护记录…</p>

    <div class="summary">
      <div><span>当前有效停诊</span><strong>{{ activeDoctorCount }}</strong></div>
      <div><span>当前有效资源禁用</span><strong>{{ activeResourceCount }}</strong></div>
      <div><span>受影响预约</span><strong>{{ affectedCount }}</strong><small>仅实时提示，不代表已通知</small></div>
    </div>

    <div class="tabs">
      <button :class="{ active: activeTab === 'doctor' }" type="button" @click="changeTab('doctor')">医生停诊</button>
      <button :class="{ active: activeTab === 'resource' }" type="button" @click="changeTab('resource')">诊椅 / 设备维护</button>
      <button class="refresh" type="button" :disabled="loading" @click="refresh">刷新当前列表</button>
    </div>

    <section v-if="activeTab === 'doctor'" class="panel">
      <div class="panel-head">
        <div><h2>登记医生停诊</h2><p>仅唯一组织负责人可登记和解除；停诊表是全局记录。</p></div>
      </div>
      <div v-if="doctorForbidden" class="error" role="alert">仅唯一组织负责人可查看或登记医生停诊；本店资源维护仍可继续使用。</div>
      <template v-else>
        <fieldset :disabled="doctorBusy" class="form-grid">
          <label>医生<select v-model="doctorForm.doctorId"><option value="">请选择医生</option><option v-for="d in dentists" :key="d.staff_id" :value="d.staff_id">{{ d.name }} · {{ d.branch_name }}</option></select></label>
          <label>开始<input v-model="doctorForm.startsAt" type="datetime-local" /></label>
          <label>结束<input v-model="doctorForm.endsAt" type="datetime-local" /></label>
          <label class="wide">原因<input v-model="doctorForm.reason" maxlength="300" placeholder="例如：临时停诊；不要填写证件号或患者资料" /></label>
        </fieldset>
        <p class="hint">登记只关闭相交时段的新预约入口；已有预约保持原状态和占用。</p>
        <div class="row-actions"><button class="primary" type="button" :disabled="doctorBusy" @click="createDoctor">{{ doctorBusy ? '登记中…' : '登记停诊' }}</button></div>

        <div class="panel-head sub"><div><h2>停诊记录</h2><p>解除只更新停诊记录，不会自动取消预约或释放资源。</p></div><select v-model="doctorState" aria-label="停诊状态筛选"><option value="">全部状态</option><option value="有效">有效</option><option value="已撤销">已撤销</option></select></div>
        <div class="table-wrap"><table><thead><tr><th>停诊编号</th><th>医生</th><th>时间</th><th>原因</th><th>状态</th><th>受影响预约</th><th>操作</th></tr></thead><tbody>
          <template v-for="item in filteredDoctors()" :key="item.absence_id">
          <tr>
            <td>{{ item.absence_id }}</td><td>{{ item.doctor_name }}</td>
            <td>{{ formatTime(item.starts_at) }}<br />至 {{ formatTime(item.ends_at) }}</td>
            <td>{{ item.reason }}</td>
            <td><span :class="['badge', item.state === '有效' ? 'warn' : 'good']">{{ item.state }}</span></td>
            <td>
              <span v-if="item.affected_count" class="badge warn">待人工协调 · {{ item.affected_count }}</span>
              <span v-else class="muted">{{ item.state === '有效' ? '当前无受影响预约' : '已解除' }}</span>
            </td>
            <td><button v-if="item.state === '有效'" type="button" @click="openResolve('doctor', item.absence_id, item.doctor_name)">解除停诊</button><span v-else class="muted">已解除</span></td>
          </tr>
          <tr v-if="item.affected_count" class="affected-row">
            <td colspan="7">
              <div class="affected-head">受影响预约 · 待人工协调，尚未通知</div>
              <div v-for="affected in item.affected_appointments" :key="affected.appointment_id" class="affected-item">
                <strong>预约 {{ affected.appointment_id }}</strong>
                <span>{{ formatTime(affected.starts_at) }} · {{ affected.branch_name }}</span>
                <span>{{ affected.item_name }} · {{ affected.state }}</span>
                <span>协调状态：{{ affected.coordination_state }}</span>
              </div>
            </td>
          </tr>
          </template>
          <tr v-if="filteredDoctors().length === 0"><td colspan="7" class="empty">暂无停诊记录。</td></tr>
        </tbody></table></div>
        <p v-if="doctors.some(item => item.affected_count)" class="hint">受影响预约需前台按预约编号人工核对和联系；页面不会显示患者姓名或电话，也不表示已通知。</p>
      </template>
    </section>

    <section v-else class="panel">
      <div class="panel-head">
        <div><h2>登记诊椅 / 设备维护</h2><p>仅本店配置负责人可登记和解除；资源状态保持原样。</p></div>
        <button type="button" :disabled="loading" @click="loadResources">刷新资源</button>
      </div>
      <div v-if="resourceForbidden" class="error" role="alert">当前账号没有本店 branch.configure 权限或任职未核验；服务端拒绝操作。</div>
      <template v-else>
        <fieldset :disabled="resourceBusy" class="form-grid">
          <label>门店<select v-model="branchId" :disabled="resourceBusy" @change="changeBranch"><option value="">请选择门店</option><option v-for="b in branches" :key="b.branch_id" :value="b.branch_id">{{ b.name }}</option></select></label>
          <label>资源<select v-model="resourceForm.resourceId"><option value="">请选择诊椅或设备</option><option v-for="r in resources" :key="r.resource_id" :value="r.resource_id">{{ r.name }} · {{ r.resource_type }} · {{ r.operating_state }}</option></select></label>
          <label>类型<select v-model="resourceForm.kind"><option value="维护">维护</option><option value="故障">故障</option></select></label>
          <label>开始<input v-model="resourceForm.startsAt" type="datetime-local" /></label>
          <label>结束<input v-model="resourceForm.endsAt" type="datetime-local" /></label>
          <label class="wide">原因<input v-model="resourceForm.reason" maxlength="200" placeholder="例如：诊椅维护；不填写患者资料" /></label>
        </fieldset>
        <p class="hint">预计结束时间不代表自动恢复；只有人工解除后才重新开放新占用。</p>
        <div class="row-actions"><button class="primary" type="button" :disabled="resourceBusy" @click="createResource">{{ resourceBusy ? '登记中…' : '登记维护 / 故障' }}</button></div>

        <div class="panel-head sub"><div><h2>本店资源记录</h2><p>{{ selectedBranch?.name || '本店' }} · 解除后重新读取服务端状态。</p></div><select v-model="resourceState" aria-label="资源状态筛选"><option value="">全部状态</option><option value="有效">有效</option><option value="已撤销">已撤销</option></select></div>
        <div class="table-wrap"><table><thead><tr><th>编号</th><th>资源</th><th>类型</th><th>时间</th><th>原因</th><th>状态</th><th>受影响预约</th><th>操作</th></tr></thead><tbody>
          <template v-for="item in filteredResources()" :key="item.unavailable_id">
          <tr>
            <td>{{ item.unavailable_id }}</td><td>{{ item.resource_name }}</td><td>{{ item.kind }}</td>
            <td>{{ formatTime(item.starts_at) }}<br />至 {{ formatTime(item.ends_at) }}</td>
            <td>{{ item.reason }}</td>
            <td><span :class="['badge', item.state === '有效' ? 'warn' : 'good']">{{ item.state }}</span></td>
            <td><span v-if="item.affected_count" class="badge warn">待人工协调 · {{ item.affected_count }}</span><span v-else class="muted">{{ item.state === '有效' ? '当前无受影响预约' : '已解除' }}</span></td>
            <td><button v-if="item.state === '有效'" type="button" @click="openResolve('resource', item.unavailable_id, item.resource_name)">解除</button><span v-else class="muted">已解除</span></td>
          </tr>
          <tr v-if="item.affected_count" class="affected-row">
            <td colspan="8">
              <div class="affected-head">受影响预约 · 待人工协调，尚未通知</div>
              <div v-for="affected in item.affected_appointments" :key="affected.appointment_id" class="affected-item">
                <strong>预约 {{ affected.appointment_id }}</strong>
                <span>{{ formatTime(affected.starts_at) }} · {{ affected.branch_name }}</span>
                <span>{{ affected.item_name }} · {{ affected.state }}</span>
                <span>协调状态：{{ affected.coordination_state }}</span>
              </div>
            </td>
          </tr>
          </template>
          <tr v-if="filteredResources().length === 0"><td colspan="8" class="empty">该门店暂无资源维护记录。</td></tr>
        </tbody></table></div>
      </template>
    </section>

    <div v-if="resolveTarget" class="dialog-mask" @click.self="closeResolve">
      <section class="dialog" role="dialog" aria-modal="true" :aria-label="`解除${resolveTarget.label}`">
        <div class="dialog-head"><div><h2>解除{{ resolveTarget.kind === 'doctor' ? '医生停诊' : '资源维护' }}：{{ resolveTarget.label }}</h2><p>只解除所选记录；不自动改预约、不释放占用、不发送通知。</p></div><button type="button" :disabled="resolveBusy" @click="closeResolve">关闭</button></div>
        <label v-if="resolveTarget.kind === 'resource'">解除类型<select v-model="resolveType" :disabled="resolveBusy"><option value="已修复">已修复</option><option value="误登记撤销">误登记撤销</option></select></label>
        <label>人工核实依据<textarea v-model="resolveBasis" :disabled="resolveBusy" maxlength="300" placeholder="例如：现场确认设备已修复，负责人核实" /></label>
        <p v-if="resolveError" class="error" role="alert">{{ resolveError }}</p>
        <div class="row-actions"><button type="button" :disabled="resolveBusy" @click="closeResolve">取消</button><button class="primary" type="button" :disabled="resolveBusy" @click="submitResolve">{{ resolveBusy ? '解除中…' : '确认解除' }}</button></div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.unavailability-page { min-height: 100vh; padding: 30px clamp(22px, 3vw, 46px) 64px; background: #f4faf7; color: #173b37; }
.unavailability-page header { display: flex; justify-content: space-between; align-items: center; gap: 18px; padding-bottom: 18px; margin-bottom: 20px; border-bottom: 1px solid #d3e6df; }
h1 { margin: 5px 0; font-size: clamp(25px, 2.2vw, 31px); font-weight: 760; letter-spacing: -.025em; }
header p { margin: 0; color: #5e7a73; font-size: 14px; line-height: 1.6; }
header a { color: #17685d; font-size: 13.5px; font-weight: 650; }
.real-badge { padding: 7px 12px; border-radius: 999px; background: #fff0dd; color: #8a5314; font-size: 12px; font-weight: 700; white-space: nowrap; }
.panel { margin-top: 17px; padding: 22px 24px; border: 1px solid #d3e6df; border-radius: 16px; background: #fff; box-shadow: 0 10px 30px #17463b0b; }
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: 14px; margin-bottom: 15px; }
.panel-head.sub { margin-top: 26px; padding-top: 18px; border-top: 1px solid #e0eee9; }
.panel h2 { margin: 0 0 4px; font-size: 19px; font-weight: 730; }
.panel p, .hint, .muted { color: #5e7a73; font-size: 13px; line-height: 1.6; }
.form-grid { display: grid; grid-template-columns: 1.15fr 1fr 1fr; gap: 13px 15px; align-items: end; }
.form-grid .wide { grid-column: span 2; }
label { display: grid; gap: 5px; color: #31574f; font-size: 13px; font-weight: 650; }
input, select, textarea { min-height: 39px; box-sizing: border-box; padding: 8px 10px; border: 1px solid #b9d8ce; border-radius: 9px; background: #fff; color: #173b37; font: inherit; font-size: 14px; }
textarea { min-height: 82px; resize: vertical; }
input:focus, select:focus, textarea:focus { outline: 3px solid #278e8430; border-color: #278e84; }
button { min-height: 37px; padding: 8px 13px; border: 1px solid #b9d8ce; border-radius: 9px; background: #fff; color: #1c5e54; font: inherit; font-size: 13px; font-weight: 650; white-space: nowrap; cursor: pointer; }
button:hover:not(:disabled) { background: #e8f7f1; border-color: #8bcbb9; }
button.primary { background: #187d6c; border-color: #187d6c; color: #fff; }
button.primary:hover:not(:disabled) { background: #116b5c; border-color: #116b5c; }
button:disabled { opacity: .5; cursor: not-allowed; }
.row-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 13px; margin: 18px 0; }
.summary > div { display: grid; gap: 5px; min-height: 94px; padding: 17px 19px; border: 1px solid #d3e6df; border-radius: 15px; background: linear-gradient(145deg, #fff, #f1faf6); box-shadow: 0 6px 18px #17463b08; }
.summary span { color: #5e7a73; font-size: 13px; font-weight: 600; }
.summary strong { color: #146254; font-size: 29px; line-height: 1.1; }
.summary small { color: #617e77; font-size: 12px; }
.tabs { display: flex; gap: 8px; align-items: center; margin: 18px 0; }
.tabs button.active { background: #187d6c; border-color: #187d6c; color: #fff; }
.tabs .refresh { margin-left: auto; }
.table-wrap { overflow-x: auto; border-radius: 10px; }
table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 14px; }
th { padding: 11px 10px; border-bottom: 1px solid #b9d8ce; background: #f4faf7; color: #42675f; font-size: 13px; font-weight: 750; text-align: left; white-space: nowrap; }
td { padding: 11px 10px; border-bottom: 1px solid #e7f1ed; font-size: 14px; line-height: 1.5; vertical-align: middle; }
tbody tr:hover { background: #f7fcf9; }
.badge { display: inline-flex; align-items: center; min-height: 24px; padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.badge.warn { background: #fff0dd; color: #8a5314; }
.badge.good { background: #d9f3e6; color: #176143; }
.error, .notice { padding: 11px 13px; border-radius: 10px; font-size: 13px; line-height: 1.6; }
.error { background: #fff0ec; color: #933629; }
.notice { background: #e5f7ef; color: #1d6351; }
.empty { padding: 26px 10px; text-align: center; color: #6d8780; }
.affected-row td { padding: 0 10px 14px; background: #fffaf0; }
.form-grid { border: 0; margin: 0; padding: 0; min-width: 0; }
.affected-head { padding: 10px 2px 7px; color: #8a5314; font-size: 12.5px; font-weight: 750; }
.affected-item { display: grid; grid-template-columns: 112px 1.25fr 1fr .8fr; gap: 8px; padding: 7px 2px; border-top: 1px dashed #eddcc2; color: #496a62; font-size: 13px; }
.affected-item strong { color: #173b37; }
@media (max-width: 700px) { .affected-item { grid-template-columns: 1fr; } }
.dialog-mask { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 18px; background: #0e2d27a8; }
.dialog { width: min(100%, 520px); padding: 22px; border-radius: 18px; background: #fff; box-shadow: 0 24px 70px #09271f40; }
.dialog-head { display: flex; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.dialog h2 { margin: 0 0 4px; font-size: 19px; }
.dialog p { margin: 0; }
.dialog label { margin-bottom: 12px; }
.dialog textarea { width: 100%; }
@media (max-width: 1000px) { table { min-width: 900px; } }
@media (max-width: 900px) { .form-grid { grid-template-columns: 1fr 1fr; } .form-grid .wide { grid-column: span 2; } .summary { grid-template-columns: 1fr 1fr; } .affected-item { grid-template-columns: 112px 1fr; } }
@media (max-width: 600px) { .unavailability-page { padding: 18px 14px 42px; } header { display: block; } .form-grid, .summary { grid-template-columns: 1fr; } .form-grid .wide { grid-column: auto; } .panel { padding: 17px 15px; } .tabs { flex-wrap: wrap; } .tabs .refresh { margin-left: 0; } .panel-head { display: block; } .panel-head > * + * { margin-top: 8px; } }
</style>
