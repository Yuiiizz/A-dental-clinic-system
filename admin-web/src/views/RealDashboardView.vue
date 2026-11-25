<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ApiError, http } from '@/services/http';
import { createAppointmentApi, type AppointmentRow } from '@/services/appointments';
import { useSessionStore } from '@/stores/session';

const props = defineProps<{ shortcuts: Array<{ key: string; label: string }> }>();
const emit = defineEmits<{ open: [key: string] }>();
const session = useSessionStore();
const api = createAppointmentApi(http);
const canReadAppointments = computed(() => session.status === 'ready'
  && (session.me?.roles.some((role) => role.role_code === 'frontdesk') ?? false));
const authIdentity = computed(() => session.status === 'ready' && session.me?.identity_type === 'staff'
  ? JSON.stringify([session.sessionRevision, session.me.account_id, session.me.roles]) : '');
const appointments = ref<AppointmentRow[] | null>(null);
const loading = ref(false);
const error = ref('');
const date = ref('');
const loadedAt = ref('');
let requestId = 0;
let active = true;

function localParts(value: Date): Record<string, string> {
  return Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(value).map((part) => [part.type, part.value]));
}
function localDate(value: Date): string {
  const p = localParts(value);
  return `${p.year}-${p.month}-${p.day}`;
}
function localTime(value: string): string {
  const p = localParts(new Date(value));
  return `${p.hour === '24' ? '00' : p.hour}:${p.minute}`;
}
const activeAppointments = computed(() => (appointments.value ?? []).filter((row) =>
  ['已确认', '候诊中', '接诊中'].includes(row.state)));
const waitingAppointments = computed(() => (appointments.value ?? []).filter((row) => row.state === '候诊中'));
const coordinationAppointments = computed(() => activeAppointments.value.filter((row) => row.coordination_state === '待协调'));
const todayRows = computed(() => [...(appointments.value ?? [])]
  .sort((a, b) => a.starts_at.localeCompare(b.starts_at)).slice(0, 8));

async function refresh(): Promise<void> {
  const request = ++requestId;
  const revision = session.sessionRevision;
  appointments.value = null;
  error.value = '';
  loadedAt.value = '';
  if (!canReadAppointments.value) { loading.value = false; return; }
  const today = localDate(new Date());
  date.value = today;
  loading.value = true;
  try {
    const result = await api.listForDate(today);
    if (!active || request !== requestId || revision !== session.sessionRevision || !canReadAppointments.value) return;
    appointments.value = result;
    loadedAt.value = localTime(new Date().toISOString());
  } catch (cause) {
    if (!active || request !== requestId || revision !== session.sessionRevision || !canReadAppointments.value) return;
    if (cause instanceof ApiError && cause.status === 401) {
      void session.fetchMe();
    } else if (cause instanceof ApiError && cause.status === 403) {
      error.value = '当前账号无权读取预约概览，请联系管理员核对门店授权。';
    } else {
      error.value = '今日预约读取失败，请稍后重试。';
    }
  } finally {
    if (active && request === requestId && revision === session.sessionRevision) loading.value = false;
  }
}

watch(authIdentity, () => {
  ++requestId;
  appointments.value = null;
  loading.value = false;
  error.value = '';
  if (canReadAppointments.value) void refresh();
}, { immediate: true, flush: 'pre' });
onUnmounted(() => { active = false; ++requestId; });
</script>

<template>
  <div class="real-dashboard">
    <section class="real-intro">
      <div>
        <span>A DENTAL · CARE WORKSPACE</span>
        <h2>把每一次就诊，安排得更从容。</h2>
        <p>从这里进入当前账号可用的业务页面。预约信息取自服务端，以服务端权限和提交结果为准。</p>
      </div>
    </section>

    <template v-if="canReadAppointments">
      <div class="real-metrics">
        <article><span>今日有效预约</span><strong>{{ appointments ? activeAppointments.length : '—' }}</strong><small>已确认、候诊中、接诊中</small></article>
        <article><span>今日候诊</span><strong>{{ appointments ? waitingAppointments.length : '—' }}</strong><small>仅统计候诊中</small></article>
        <article><span>今日待协调</span><strong>{{ appointments ? coordinationAppointments.length : '—' }}</strong><small>请在预约工作台核对</small></article>
      </div>
      <section class="real-panel">
        <div class="real-panel-head">
          <div><h3>今日预约</h3><p>{{ date }} · {{ loadedAt ? `更新于 ${loadedAt}` : '等待读取' }}</p></div>
          <button type="button" :disabled="loading" @click="refresh">{{ loading ? '读取中…' : '刷新' }}</button>
        </div>
        <p v-if="error" class="real-error" role="alert">{{ error }}</p>
        <p v-else-if="loading" class="real-empty">正在读取今日预约…</p>
        <p v-else-if="appointments && todayRows.length === 0" class="real-empty">今日暂无预约记录。</p>
        <div v-else-if="appointments" class="real-table-wrap">
          <table><thead><tr><th>时间</th><th>门店</th><th>用户</th><th>医生</th><th>项目</th><th>状态</th></tr></thead>
            <tbody><tr v-for="row in todayRows" :key="row.appointment_id">
              <td>{{ localTime(row.starts_at) }}</td><td>{{ row.branch_name }}</td><td>{{ row.patient_name }}</td>
              <td>{{ row.dentist_name || '待安排' }}</td><td>{{ row.item_name || '—' }}</td><td>{{ row.state }}</td>
            </tr></tbody>
          </table>
          <button v-if="appointments.length > todayRows.length" type="button" class="real-more" @click="emit('open', 'appointment-workbench')">查看全部 {{ appointments.length }} 条记录</button>
        </div>
      </section>
    </template>
    <section v-else class="real-panel real-role-note">
      <template v-if="session.status !== 'ready'">登录状态未确认，请重新登录。<button type="button" @click="emit('open', 'login')">前往登录</button></template>
      <template v-else>当前角色不读取预约概览。请从下方入口进入获授权的业务页面。</template>
    </section>

    <section class="real-panel">
      <div class="real-panel-head"><div><h3>快捷入口</h3><p>仅展示当前角色对应的真实业务页面；具体权限由服务端核验。</p></div></div>
      <div class="real-links">
        <button v-for="link in props.shortcuts" :key="link.key" type="button" @click="emit('open', link.key)">{{ link.label }} <span aria-hidden="true">↗</span></button>
        <p v-if="props.shortcuts.length === 0">当前账号暂无可用业务入口。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.real-dashboard { display: grid; gap: 18px; }
.real-intro { min-height: 190px; border-radius: 20px; display: flex; align-items: center; padding: 30px 36px; background: #e6f6f3 url('/dental-hero.png') right center / auto 115% no-repeat; box-shadow: 0 12px 30px #57a6a01a; position: relative; overflow: hidden; }
.real-intro::before { content: ''; position: absolute; inset: 0; background: linear-gradient(90deg, #ebf9f5 0%, #eff9f7 48%, #eff9f7bd 68%, transparent 100%); }
.real-intro > div { position: relative; max-width: 560px; }
.real-intro span { color: #288b78; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.real-intro h2 { color: #15564c; font-size: clamp(22px, 2.2vw, 31px); margin: 12px 0 10px; }
.real-intro p { color: #527d72; line-height: 1.7; font-size: 13px; margin: 0; }
.real-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.real-metrics article, .real-panel { background: #fff; border: 1px solid #e4efeb; border-radius: 17px; box-shadow: 0 7px 24px #174c3f0b; padding: 20px 22px; }
.real-metrics article { display: grid; gap: 5px; }
.real-metrics span { color: #63817a; font-size: 12px; }.real-metrics strong { color: #155d51; font-size: 30px; }.real-metrics small { color: #879e97; font-size: 11px; }
.real-panel-head { display: flex; justify-content: space-between; align-items: start; gap: 15px; margin-bottom: 12px; }.real-panel h3 { margin: 0; color: #254d46; font-size: 16px; }.real-panel-head p { margin: 5px 0 0; color: #73908a; font-size: 11px; }
.real-panel button { border: 1px solid #c4ddd7; background: #f2faf7; color: #1e5a50; border-radius: 9px; padding: 8px 12px; font: inherit; font-size: 12px; cursor: pointer; }.real-panel button:disabled { opacity: .5; cursor: default; }
.real-table-wrap { overflow: auto; }.real-table-wrap table { border-collapse: collapse; width: 100%; font-size: 12px; }.real-table-wrap th, .real-table-wrap td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #edf4f2; white-space: nowrap; }.real-table-wrap th { color: #6e8985; background: #f8fbfa; }.real-table-wrap td { color: #24413e; }
.real-error { color: #a74334; background: #fff0eb; padding: 10px; border-radius: 8px; }.real-empty, .real-role-note { color: #6f8882; font-size: 13px; }.real-more { margin-top: 12px; }
.real-links { display: flex; flex-wrap: wrap; gap: 10px; }.real-links button { min-width: 135px; display: flex; justify-content: space-between; gap: 14px; }.real-links p { color: #6f8882; font-size: 13px; }
@media (max-width: 800px) { .real-metrics { grid-template-columns: 1fr; }.real-intro { padding: 24px; background-size: auto 100%; }.real-intro::before { background: #e6f8f2e8; } }
</style>
