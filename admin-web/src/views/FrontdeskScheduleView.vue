<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ApiError, createHttpClient } from '@/services/http';
import { createAppointmentApi, type BookingOverview } from '@/services/appointments';
import { frontdeskSchedule, type ScheduleDay } from '@/services/frontdesk-schedule';
import { useSessionStore } from '@/stores/session';

const session = useSessionStore();
const api = createAppointmentApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1', timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));
const overview = ref<BookingOverview | null>(null);
const branchId = ref('');
const loading = ref(false);
const error = ref('');
let request = 0;
let active = true;
const days = computed(() => overview.value ? frontdeskSchedule(overview.value, branchId.value) : []);
const daysByDate = computed(() => new Map(days.value.map((day) => [day.date, day])));
const branches = computed(() => overview.value?.branches ?? []);
const halves = [{ key: 'morning', label: '上午' }, { key: 'afternoon', label: '下午' }] as const;
const dateFormat = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' });
function shanghaiDate(value: Date): string {
  const p = Object.fromEntries(dateFormat.formatToParts(value).map((part) => [part.type, part.value]));
  return `${p.year}-${p.month}-${p.day}`;
}
const calendarDates = Array.from({ length: 21 }, (_, index) => shanghaiDate(new Date(Date.now() + index * 86400000)));
const weekIndex = ref(0);
const selectedDate = ref(calendarDates[0] ?? '');
const visibleDates = computed(() => calendarDates.slice(weekIndex.value * 7, weekIndex.value * 7 + 7));
function selectDate(value: string): void {
  const index = calendarDates.indexOf(value);
  if (index < 0) return;
  selectedDate.value = value;
  weekIndex.value = Math.floor(index / 7);
}
function changeWeek(index: number): void {
  if (index < 0 || index > 2) return;
  weekIndex.value = index;
  selectedDate.value = calendarDates[index * 7] ?? selectedDate.value;
}
function shiftsFor(date: string, half: 'morning' | 'afternoon'): ScheduleDay['morning'] {
  return daysByDate.value.get(date)?.[half] ?? [];
}
function assistantsFor(date: string, half: 'morning' | 'afternoon') {
  const day = daysByDate.value.get(date);
  return half === 'morning' ? day?.morningAssistants ?? [] : day?.afternoonAssistants ?? [];
}
function dayLabel(date: string): string {
  const today = shanghaiDate(new Date());
  const tomorrow = shanghaiDate(new Date(Date.now() + 86400000));
  const relative = date === today ? '今天' : date === tomorrow ? '明天' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', weekday: 'short' }).format(new Date(`${date}T04:00:00Z`));
  return `${date} · ${relative}`;
}

async function load(): Promise<void> {
  const current = ++request;
  const revision = session.sessionRevision;
  loading.value = true; error.value = '';
  try {
    const result = await api.overview();
    if (!active || current !== request || revision !== session.sessionRevision) return;
    overview.value = result;
    if (!result.branches.some((branch) => branch.branch_id === branchId.value)) branchId.value = result.branches[0]?.branch_id ?? '';
  } catch (cause) {
    if (active && current === request && revision === session.sessionRevision) {
      overview.value = null;
      error.value = cause instanceof ApiError ? cause.message : '排班读取失败，请稍后重试。';
    }
  } finally { if (active && current === request) loading.value = false; }
}
watch(() => session.sessionRevision, () => { request++; overview.value = null; branchId.value = ''; error.value = ''; loading.value = false; void load(); });
onMounted(() => { void load(); });
onUnmounted(() => { active = false; request++; });
</script>

<template>
  <main class="board-page">
    <header class="board-header">
      <div><RouterLink to="/">← 工作台</RouterLink><h1>医生排班表</h1><p>查看本店已发布的出诊安排，方便向患者说明医生何时坐诊。</p></div>
      <button type="button" :disabled="loading" @click="load">刷新排班</button>
    </header>
    <p v-if="error" class="board-error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取排班…</p>
    <template v-if="overview">
      <div class="board-tools"><label>门店 <select v-model="branchId"><option v-for="branch in branches" :key="branch.branch_id" :value="branch.branch_id">{{ branch.name }}</option></select></label><RouterLink to="/appointments">前往预约工作台 →</RouterLink></div>
      <div class="calendar-controls">
        <div class="week-buttons"><button type="button" :disabled="weekIndex === 0" @click="changeWeek(weekIndex - 1)">← 前七天</button><strong>{{ visibleDates[0] }} 至 {{ visibleDates[6] }}</strong><button type="button" :disabled="weekIndex === 2" @click="changeWeek(weekIndex + 1)">后七天 →</button></div>
        <label>选择日期 <input type="date" :min="calendarDates[0]" :max="calendarDates[20]" :value="selectedDate" @change="selectDate(($event.target as HTMLInputElement).value)" /></label>
      </div>
      <p class="board-note">可查看未来三周的已发布排班。预约窗口目前为 7 天；“当前有可约时段”仅表示此刻可选，最终以预约提交结果为准。</p>
      <p class="scroll-hint">表格可左右滚动查看一周内的全部日期。</p>
      <div class="calendar-scroll" role="region" aria-label="未来三周医生排班，可横向滚动" tabindex="0">
        <div class="calendar-grid" role="table" aria-label="医生排班课程表">
          <div class="calendar-corner" role="columnheader">时段</div>
          <button v-for="date in visibleDates" :key="'date-'+date" type="button" class="calendar-date" :class="{ selected: selectedDate === date }" role="columnheader" @click="selectDate(date)">{{ dayLabel(date) }}</button>
          <template v-for="half in halves" :key="half.key">
            <strong class="calendar-half" role="rowheader">{{ half.label }}<small>{{ half.key === 'morning' ? '09:00–12:00' : '13:00–17:00' }}</small></strong>
            <div v-for="date in visibleDates" :key="half.key+'-'+date" class="calendar-cell" :class="{ selected: selectedDate === date }" role="cell">
              <div v-for="shift in shiftsFor(date, half.key)" :key="shift.id" class="shift">
                <div class="shift-top"><strong>{{ shift.dentist }}</strong><span>{{ shift.time }}</span></div>
                <small>{{ shift.hasSlots ? '当前有可约时段' : '已排班 · 暂无可约时段' }}</small>
                <p v-if="shift.services.length">项目：{{ shift.services.join('、') }}</p>
              </div>
              <span v-if="shiftsFor(date, half.key).length === 0" class="empty">暂无医生排班</span>
              <div v-if="assistantsFor(date, half.key).length" class="assistant-list">
                <strong>当班助手</strong>
                <div v-for="assistant in assistantsFor(date, half.key)" :key="assistant.id">{{ assistant.name }} · {{ assistant.title }} <span>{{ assistant.time }}</span></div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </template>
  </main>
</template>

<style scoped>
.board-page{min-height:100vh;padding:28px clamp(16px,4vw,48px) 70px;background:#f3f9f7;color:#234842}.board-header,.board-tools{display:flex;justify-content:space-between;align-items:center;gap:20px}.board-header{padding:28px 30px;border-radius:22px;background:linear-gradient(110deg,#e4f4ef,#fff);border:1px solid #d6e9e1}.board-header h1{font-size:32px;margin:12px 0 5px}.board-header p,.board-note{color:#5c7972}.board-header a,.board-tools a{color:#197c70}.board-header button,.board-tools select{padding:10px 14px;border:1px solid #bddbd1;border-radius:10px;background:white;color:#245b52;font:inherit}.board-tools{margin:20px 0 8px}.board-tools label{font-weight:700}.board-tools select{margin-left:8px}.board-note{font-size:14px}.schedule-table{margin-top:18px;background:white;border:1px solid #d8e9e3;border-radius:18px;overflow:hidden}.schedule-heading,.schedule-row{display:grid;grid-template-columns:170px minmax(0,1fr) minmax(0,1fr)}.schedule-heading{background:#e7f3ef}.schedule-heading>*{padding:16px}.schedule-row{border-top:1px solid #e6efeb}.date{padding:20px 16px}.shift-cell{padding:10px;border-left:1px solid #e6efeb}.shift{padding:12px 14px;border-radius:12px;background:#f2faf7;margin:4px 0}.shift-top{display:flex;justify-content:space-between;gap:10px}.shift small{display:block;margin-top:6px;color:#187d69}.shift p{margin:6px 0 0;color:#5b736d;font-size:13px}.empty,.board-empty{color:#829791}.board-empty{padding:40px;text-align:center;background:white;border-radius:16px}.board-error{padding:12px;color:#a04138;background:#fff0ed;border-radius:10px}@media(max-width:760px){.board-header,.board-tools{align-items:stretch;flex-direction:column}.schedule-heading{display:none}.schedule-row{display:block;padding:12px}.date{display:block;padding:8px}.shift-cell{border:0;padding:4px}.shift-cell:before{content:attr(data-label);font-weight:700}}
.assistant-list{margin-top:10px;padding:10px 12px;border:1px solid #deebe8;border-radius:10px;background:#f8fbfb;font-size:13px}.assistant-list strong{display:block;margin-bottom:5px;color:#466862}.assistant-list div{display:flex;justify-content:space-between;gap:8px;padding:3px 0}.assistant-list span{color:#67847d}
.calendar-controls,.week-buttons{display:flex;align-items:center;justify-content:space-between;gap:12px}.calendar-controls{margin-top:22px;flex-wrap:wrap}.week-buttons button,.calendar-controls input{padding:9px 12px;border:1px solid #bad6cd;border-radius:9px;background:#fff;color:#245b52;font:inherit}.week-buttons button:disabled{opacity:.4}.calendar-controls label{display:flex;align-items:center;gap:8px;font-weight:700}.calendar-scroll{margin-top:14px;overflow-x:auto;border:1px solid #d8e9e3;border-radius:16px;background:#fff}.calendar-grid{display:grid;grid-template-columns:95px repeat(7,minmax(160px,1fr));min-width:1215px}.calendar-corner,.calendar-date,.calendar-half,.calendar-cell{border-right:1px solid #e3eeea;border-bottom:1px solid #e3eeea}.calendar-corner,.calendar-half{padding:16px 12px;background:#e7f3ef}.calendar-date{padding:15px 8px;border-top:0;border-left:0;background:#e7f3ef;color:#234842;font:inherit;font-weight:700;text-align:center;cursor:pointer}.calendar-date.selected,.calendar-cell.selected{background:#f0faf6}.calendar-half small{display:block;margin-top:6px;color:#617e76;font-size:12px}.calendar-cell{min-height:190px;padding:8px}.calendar-cell .shift{margin:3px 0 8px}.calendar-cell .shift-top{flex-wrap:wrap}.calendar-cell .shift p{line-height:1.5}.calendar-cell .assistant-list div{flex-wrap:wrap}.calendar-cell .empty{display:block;padding:12px 4px;font-size:13px}@media(max-width:760px){.calendar-controls,.week-buttons{align-items:stretch;flex-direction:column}.calendar-controls label{justify-content:space-between}.calendar-grid{grid-template-columns:72px repeat(7,minmax(150px,1fr));min-width:1122px}.calendar-half{padding:12px 7px}.calendar-cell{padding:5px}}
.scroll-hint{display:none;color:#617e76;font-size:13px}@media(max-width:1215px){.scroll-hint{display:block}}
</style>
