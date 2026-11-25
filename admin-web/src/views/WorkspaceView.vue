<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import RealDashboardView from '@/views/RealDashboardView.vue';
import { demoModules, navGroups } from '@/demo/modules';
import {
  addDemoAppointment, addDemoPlan, addDemoPurchase, billRemainingCents, collectDemoPayment,
  demoClinics, demoDoctors, demoMaterials, demoPatients, demoServices, demoState, demoSuppliers,
  formatCents, materialById, parseMoneyToCents, patientName, runDemoAction, type DemoBill,
} from '@/demo/data';

const props = withDefaults(defineProps<{ demo?: boolean }>(), { demo: false });
const route = useRoute();
const router = useRouter();
const session = useSessionStore();

const canUseDemo = computed(() => props.demo || import.meta.env.DEV);
const storedDemo = (() => {
  if (!import.meta.env.DEV) return false;
  try { return sessionStorage.getItem('a-clinic.admin.demo') === '1'; } catch { return false; }
})();
const demoEnabled = ref(props.demo || (import.meta.env.DEV && storedDemo));
const search = ref('');
const selectedRow = ref<Record<string, unknown> | null>(null);
const toast = ref('');
const mobileNavOpen = ref(false);
const businessForm = ref<'' | 'walkin' | 'plan' | 'purchase'>('');
const businessError = ref('');
const walkin = ref({ patientId: 'PT-1005', clinicId: 'CL-02', doctorId: 'DR-04', item: '初诊检查', date: '2026-10-27', time: '11:00' });
const walkinDoctors = computed(() => demoDoctors.filter((doctor) => doctor.clinicId === walkin.value.clinicId));
watch(() => walkin.value.clinicId, () => {
  if (!walkinDoctors.value.some((doctor) => doctor.id === walkin.value.doctorId)) {
    walkin.value.doctorId = walkinDoctors.value[0]?.id ?? '';
  }
});
const planForm = ref({ patientId: 'PT-1001', item: '牙周维护', count: 4, note: '', doctorId: 'DR-01' });
const purchaseForm = ref({ supplierId: 'SP-01', materialId: 'MT-01', quantity: 1, unitPrice: '128.00' });
const paymentBill = ref<DemoBill | null>(null);
const paymentAmount = ref('');
const paymentError = ref('');
const paymentKey = ref('');
const paymentBusy = ref(false);
const moduleKey = computed(() => {
  const key = typeof route.query.module === 'string' ? route.query.module : 'dashboard';
  return key === 'dashboard' || demoModules[key] ? key : 'dashboard';
});
const current = computed(() => (demoEnabled.value && pageAllowed.value && moduleKey.value !== 'dashboard' ? demoModules[moduleKey.value] : null));
const identityName = computed(() => props.demo ? '演示访客' : (session.me?.name || '身份未确认'));
const demoGuest = computed(() => props.demo);
const roleCodes = computed(() => new Set((session.me?.roles ?? []).map((role) => role.role_code)));
function allowed(key: string): boolean {
  if (demoGuest.value) return true;
  if (key === 'dashboard') return true;
  const realAlias: Record<string, string> = {
    appointments: 'appointment-workbench', walkin: 'appointment-workbench', checkin: 'appointment-workbench',
    patients: 'appointment-workbench', contacts: 'appointment-workbench', calendar: 'frontdesk-schedule',
    visits: 'clinical', recalls: 'clinical', treatments: 'scheduling', services: 'scheduling', chairs: 'scheduling',
    bills: 'billing', payments: 'billing', refunds: 'billing', invoices: 'billing', credentials: 'staff',
  };
  const realKey = realAlias[key] ?? key;
  return (roleCodes.value.has('owner') && ['branches', 'staff', 'scheduling', 'suppliers', 'inventory', 'unavailability'].includes(realKey))
    || (roleCodes.value.has('sysadmin') && realKey === 'accounts')
    || (roleCodes.value.has('frontdesk') && ['appointment-workbench', 'frontdesk-schedule', 'internal-messages', 'billing'].includes(realKey))
    || (roleCodes.value.has('dentist') && ['internal-messages', 'clinical', 'treatment-plans'].includes(realKey))
    || (roleCodes.value.has('inventory') && ['suppliers', 'purchases', 'inventory'].includes(realKey))
    || (roleCodes.value.has('finance') && realKey === 'billing')
    || (demoEnabled.value && (
      (roleCodes.value.has('owner') && !['appointment-workbench', 'clinical', 'treatment-plans', 'billing'].includes(key))
      || (roleCodes.value.has('frontdesk') && ['appointments', 'calendar', 'walkin', 'checkin', 'patients', 'visits', 'recalls', 'contacts', 'bills', 'payments', 'refunds', 'invoices'].includes(key))
      || (roleCodes.value.has('dentist') && ['appointments', 'calendar', 'checkin', 'patients', 'visits', 'recalls', 'treatments'].includes(key))
      || (roleCodes.value.has('inventory') && ['assets', 'depreciation'].includes(key))
      || (roleCodes.value.has('finance') && ['bills', 'payments', 'refunds', 'invoices', 'depreciation'].includes(key))
    ));
}
const visibleNavGroups = computed(() => navGroups
  .map((group) => ({ ...group, items: group.items.filter((item) =>
    allowed(item.key) && (demoEnabled.value || item.key === 'dashboard' || Boolean(item.path))) }))
  .filter((group) => group.items.length > 0));
const realShortcuts = computed(() => visibleNavGroups.value.flatMap((group) => group.items)
  .filter((item) => Boolean(item.path)).map(({ key, label }) => ({ key, label })));
const pageAllowed = computed(() => demoGuest.value || allowed(moduleKey.value));
const coreModule = computed(() => moduleKey.value === 'walkin' || moduleKey.value === 'plans' || moduleKey.value === 'purchases');

watch(moduleKey, () => {
  search.value = '';
  selectedRow.value = null;
  businessForm.value = '';
  paymentBill.value = null;
});
watch(demoEnabled, (value) => {
  if (import.meta.env.DEV) {
    try { sessionStorage.setItem('a-clinic.admin.demo', value ? '1' : '0'); } catch { /* noop */ }
  }
  if (!value) {
    selectedRow.value = null;
    businessForm.value = '';
    paymentBill.value = null;
    toast.value = '';
    search.value = '';
  }
});

const weekDays = computed(() => [
  { key: '2026-10-26', label: '周一 10/26' },
  { key: '2026-10-27', label: '周二 10/27' },
  { key: '2026-10-28', label: '周三 10/28' },
  { key: '2026-10-29', label: '周四 10/29' },
  { key: '2026-10-30', label: '周五 10/30' },
  { key: '2026-10-31', label: '周六 10/31' },
  { key: '2026-11-01', label: '周日 11/01' },
]);
function dayAppointments(day: string) {
  return demoState.appointments.filter((a) => a.time.startsWith(day));
}
const todayAppointments = computed(() => demoState.appointments.filter((a) => a.time.startsWith('2026-10-27')));
const waiting = computed(() => demoState.appointments.filter((a) => a.status === '候诊' || a.status === '接诊中').length);
const pendingRecalls = computed(() => demoState.recalls.filter((r) => r.status !== '已完成').length);
const todayBills = computed(() => demoState.bills.filter((b) => b.date === '2026-10-27'));
const revenue = computed(() => todayBills.value.reduce((sum, b) => sum + Number(b.amount.replace(/[^\d.]/g, '')), 0));

const baseRows = computed<Record<string, unknown>[]>(() => {
  if (!current.value) return [];
  return current.value.rows();
});
const rows = computed(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return baseRows.value;
  return baseRows.value.filter((row) =>
    Object.entries(row).some(([key, value]) => !key.startsWith('_') && String(value).toLowerCase().includes(q)));
});

function selectModule(key: string): void {
  mobileNavOpen.value = false;
  if (key === 'login') { void router.push({ name: 'login' }); return; }
  if (key === 'dashboard') {
    void router.replace({ path: route.path, query: {} });
    return;
  }
  if (!allowed(key)) {
    toast.value = '当前角色无权查看该模块；正式权限以服务端为准。';
    return;
  }
  const group = navGroups.flatMap((g) => g.items).find((item) => item.key === key);
  if (group?.path) {
    if (props.demo && !session.me) {
      toast.value = '该入口连接真实接口，需要员工登录；演示模式不会伪造真实权限。';
      return;
    }
    void router.push(key === 'calendar' && roleCodes.value.has('owner') && !roleCodes.value.has('frontdesk') ? '/scheduling' : group.path);
    return;
  }
  void router.replace({ path: route.path, query: { module: key } });
}

const navIconPaths: Record<string, string> = {
  '工作台': 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  '预约接诊': 'M5 5h14v15H5zM8 3v4M16 3v4M5 9h14M8 13h3M8 17h6',
  '患者与复诊': 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 4-6 8-6s8 2 8 6',
  '诊疗与收费': 'M12 4v16M4 12h16',
  '采购与成本': 'M3 5h2l2.5 10h10L20 8H6M9 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM17 20a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  '基础设置': 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2',
};
function navIconPath(title: string): string { return navIconPaths[title] ?? navIconPaths['工作台']; }

const slots = ['09:00', '09:30', '10:00', '10:30', '14:00', '14:30', '15:00'];
const purchaseTotal = computed(() => {
  const cents = parseMoneyToCents(purchaseForm.value.unitPrice);
  return cents === null ? 0 : cents * purchaseForm.value.quantity;
});

function openBusinessForm(): void {
  businessError.value = '';
  businessForm.value = moduleKey.value === 'walkin' ? 'walkin'
    : moduleKey.value === 'plans' ? 'plan'
      : moduleKey.value === 'purchases' ? 'purchase' : '';
}

function submitWalkin(): void {
  businessError.value = '';
  const result = addDemoAppointment({ ...walkin.value, source: '前台代约' });
  if (!result.ok) { businessError.value = result.message; return; }
  toast.value = result.message;
  businessForm.value = '';
}

function submitPlan(): void {
  businessError.value = '';
  const result = addDemoPlan(planForm.value.patientId, planForm.value.item,
    Number(planForm.value.count), planForm.value.note, planForm.value.doctorId);
  if (!result.ok) { businessError.value = result.message; return; }
  toast.value = result.message;
  businessForm.value = '';
}

function selectMaterial(): void {
  const material = materialById(purchaseForm.value.materialId);
  if (material) purchaseForm.value.unitPrice = (material.unitPriceCents / 100).toFixed(2);
}

function submitPurchase(): void {
  businessError.value = '';
  const cents = parseMoneyToCents(purchaseForm.value.unitPrice);
  if (cents === null) { businessError.value = '请输入大于 0 且最多两位小数的单价。'; return; }
  const result = addDemoPurchase(purchaseForm.value.supplierId, purchaseForm.value.materialId,
    Number(purchaseForm.value.quantity), cents);
  if (!result.ok) { businessError.value = result.message; return; }
  toast.value = result.message;
  businessForm.value = '';
}

function billPaid(row: Record<string, unknown>): boolean {
  const bill = demoState.bills.find((b) => b.id === String(row._actionId ?? row._id));
  return !bill || bill.paidCents >= bill.totalCents;
}

function actionDisabled(action: { key: string }, row: Record<string, unknown>): boolean {
  const id = String(row._actionId ?? row._id ?? '');
  if (action.key === 'collect') return billPaid(row);
  if (action.key === 'accept') {
    const purchase = demoState.purchases.find((p) => p.id === id);
    return !purchase || purchase.status !== '待验收（演示）';
  }
  const appointment = demoState.appointments.find((a) => a.id === id);
  if (!appointment) return true;
  if (appointment.status.includes('取消') || appointment.status.includes('完成')) return true;
  if (action.key === 'checkin') return appointment.status !== '已确认';
  if (action.key === 'confirm') return appointment.status !== '待确认';
  if (action.key === 'cancel') return appointment.status !== '待确认' && appointment.status !== '已确认';
  return false;
}

function openPayment(row: Record<string, unknown>): void {
  const bill = demoState.bills.find((b) => b.id === String(row._actionId ?? row._id));
  if (!bill) { toast.value = '找不到账单，收款未执行。'; return; }
  if (bill.paidCents >= bill.totalCents) { toast.value = '该账单已付清，不能重复收款。'; return; }
  paymentBill.value = bill;
  paymentAmount.value = (billRemainingCents(bill) / 100).toFixed(2);
  paymentError.value = '';
  paymentKey.value = `demo-pay-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function submitPayment(): void {
  if (!paymentBill.value || paymentBusy.value) return;
  paymentBusy.value = true;
  paymentError.value = '';
  try {
    const result = collectDemoPayment(paymentBill.value.id, paymentAmount.value, paymentKey.value);
    if (!result.ok) { paymentError.value = result.message; return; }
    toast.value = result.message;
    paymentBill.value = null;
    paymentKey.value = '';
  } finally {
    paymentBusy.value = false;
  }
}

function act(key: string, row: Record<string, unknown>): void {
  if (key === 'collect') { openPayment(row); return; }
  const id = String(row._actionId ?? row._id ?? '');
  toast.value = runDemoAction(key, id);
}

function openAppointment(id: string): void {
  const row = demoState.appointments.find((a) => a.id === id);
  if (!row) return;
  selectedRow.value = {
    _id: row.id, 时间: row.time, 患者: patientName(row.patientId), 医生: row.doctorId,
    门店: row.clinicId, 项目: row.item, 来源: row.source, 状态: row.status,
  };
}

function statusClass(value: unknown): string {
  const text = String(value);
  if (text.includes('取消') || text.includes('未通过') || text.includes('拒绝')) return 'status bad';
  if (text.includes('待') || text.includes('演示') || text.includes('未确认')) return 'status warn';
  if (text.includes('完成') || text.includes('已确认') || text.includes('在用') || text.includes('启用')) return 'status good';
  return 'status';
}
function cellText(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value);
}

async function logout(): Promise<void> {
  if (props.demo) { await router.push({ name: 'login' }); return; }
  const outcome = await session.logout();
  if (outcome.executed && outcome.ok) await router.replace({ name: 'login' });
}
</script>

<template>
  <div class="admin-shell">
    <aside class="sidebar" :class="{ open: mobileNavOpen }">
      <div class="brand">
        <div class="brand-mark">A</div>
        <div>
          <strong>A口腔诊所</strong>
          <span>管理端 · 业务工作台</span>
        </div>
      </div>
      <nav class="nav">
        <div v-for="group in visibleNavGroups" :key="group.title" class="nav-group">
          <p><svg class="nav-group-icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="navIconPath(group.title)" /></svg>{{ group.title }}</p>
          <button
            v-for="item in group.items"
            :key="item.key"
            type="button"
            :class="{ active: !item.path && moduleKey === item.key, real: item.path }"
            @click="selectModule(item.key)"
          >
            <span>{{ item.label }}</span>
            <em v-if="item.path">真实</em>
            <em v-else-if="demoEnabled">演示</em>
          </button>
        </div>
      </nav>
      <div class="sidebar-foot">
        <span>{{ props.demo ? '演示访客' : identityName }}</span>
        <button type="button" class="link" @click="logout">{{ props.demo ? '去登录' : '退出登录' }}</button>
      </div>
    </aside>
    <div v-if="mobileNavOpen" class="nav-overlay" @click="mobileNavOpen = false" />

    <main class="content">
      <header class="topbar">
        <button class="mobile-menu" type="button" aria-label="打开导航菜单" :aria-expanded="mobileNavOpen" @click="mobileNavOpen = !mobileNavOpen">☰ 导航</button>
        <div>
          <h1>{{ current?.title ?? '经营工作台' }}</h1>
          <p>{{ current?.subtitle ?? (demoEnabled ? '今日预约、候诊、复诊与经营概览一屏掌握。' : '查看获授权的真实业务与今日预约。') }}</p>
        </div>
        <div class="top-actions">
          <span v-if="demoEnabled" class="demo-chip">演示数据 · 本地模拟</span>
          <span v-else class="live-chip">真实接口页面仍按服务端权限</span>
          <label v-if="!props.demo && canUseDemo" class="switch">
            <input v-model="demoEnabled" type="checkbox" /> 演示模式
          </label>
        </div>
      </header>

      <div v-if="demoEnabled" class="demo-banner">
        当前为<strong>本地演示模式</strong>：下方数据为虚构样例，操作只保存在当前页面内存中，<strong>未写入后台</strong>；不发起真实支付、不生成正式发票、不代表真实经营收入。
      </div>
      <div v-else-if="moduleKey !== 'dashboard'" class="off-banner">
        <template v-if="canUseDemo">
          <strong>演示模式未开启。</strong> 真实页面（机构门店、员工管理、账号角色）继续走服务端授权；演示页面需显式开启本地演示后才能查看样例。
          <button type="button" @click="demoEnabled = true">开启本地演示</button>
        </template>
        <template v-else>
          <strong>演示模式仅在本地开发服务器可用。</strong> 正式环境不提供演示开关，也不会读取旧的演示 cookie/本地开关；请使用真实员工登录后的授权页面。
        </template>
      </div>

      <section v-if="!pageAllowed" class="panel module-panel off-panel">
        <h2>当前角色不可见</h2>
        <p>导航已按当前员工角色与门店范围收敛；服务端仍是最终授权依据。</p>
      </section>

      <template v-else-if="moduleKey === 'dashboard'">
        <RealDashboardView v-if="!demoEnabled" :shortcuts="realShortcuts" @open="selectModule" />
        <section v-if="demoEnabled" class="dash">
          <div class="welcome-hero">
            <div class="welcome-copy">
              <span class="welcome-eyebrow">A DENTAL · CARE WORKSPACE</span>
              <h2>把每一次就诊，安排得更从容。</h2>
              <p>排班、预约与复诊在这里衔接。先看今日动态，再进入各业务工作台。</p>
              <div class="welcome-links">
                <button type="button" @click="selectModule('appointments')">查看今日预约 <span aria-hidden="true">↗</span></button>
                <button type="button" @click="selectModule('calendar')">查看医生排班 <span aria-hidden="true">↗</span></button>
              </div>
            </div>
          </div>
          <div class="metric-grid">
            <article class="metric"><span>今日预约</span><strong>{{ todayAppointments.length }}</strong><small>其中 {{ waiting }} 位候诊/接诊中</small></article>
            <article class="metric"><span>复诊待办</span><strong>{{ pendingRecalls }}</strong><small>复诊优先原主治医生</small></article>
            <article class="metric"><span>今日演示账单</span><strong>{{ todayBills.length }}</strong><small>演示金额 ¥{{ revenue.toFixed(2) }}</small></article>
            <article class="metric"><span>在册患者</span><strong>{{ demoPatients.length }}</strong><small>虚构患者样例</small></article>
          </div>

          <div class="dash-grid">
            <section class="panel">
              <div class="panel-head"><div><h2>今日预约</h2><p>10 月 27 日 · 云栖总院 / 滨江分院</p></div><button type="button" @click="selectModule('appointments')">全部预约 →</button></div>
              <table>
                <thead><tr><th>时间</th><th>患者</th><th>医生</th><th>项目</th><th>状态</th></tr></thead>
                <tbody>
                  <tr v-for="a in todayAppointments" :key="a.id">
                    <td>{{ a.time.slice(11) }}</td><td>{{ patientName(a.patientId) }}</td><td>{{ demoDoctors.find(d => d.id === a.doctorId)?.name }}</td><td>{{ a.item }}</td>
                    <td><span :class="statusClass(a.status)">{{ a.status }}</span></td>
                  </tr>
                </tbody>
              </table>
            </section>

            <section class="panel side-panel">
              <div class="panel-head"><div><h2>候诊与复诊</h2><p>前台可继续签到、确认</p></div></div>
              <div v-for="a in todayAppointments.filter(x => x.status === '候诊' || x.status === '接诊中')" :key="a.id" class="list-row">
                <span class="avatar">{{ patientName(a.patientId).slice(0, 1) }}</span>
                <div><strong>{{ patientName(a.patientId) }}</strong><p>{{ a.item }} · {{ a.chair }}</p></div>
                <span :class="statusClass(a.status)">{{ a.status }}</span>
              </div>
              <div class="divider" />
              <div v-for="r in demoState.recalls" :key="r.id" class="list-row">
                <span class="avatar soft">复</span>
                <div><strong>{{ patientName(r.patientId) }}</strong><p>{{ r.reason }} · {{ r.due }}</p></div>
                <span :class="statusClass(r.status)">{{ r.status }}</span>
              </div>
            </section>
          </div>

          <div class="dash-grid lower">
            <section class="panel">
              <div class="panel-head"><div><h2>本周排班概览</h2><p>点击查看日历排班</p></div><button type="button" @click="selectModule('calendar')">打开日历 →</button></div>
              <div class="mini-calendar">
                <div v-for="day in weekDays" :key="day.key" class="day-col">
                  <span>{{ day.label }}</span>
                  <button v-for="a in dayAppointments(day.key)" :key="a.id" type="button" @click="openAppointment(a.id)">
                    {{ a.time.slice(11) }} {{ patientName(a.patientId) }}
                  </button>
                  <small v-if="dayAppointments(day.key).length === 0">空闲</small>
                </div>
              </div>
            </section>
            <section class="panel">
              <div class="panel-head"><div><h2>快捷入口</h2><p>演示操作不会写入后台</p></div></div>
              <div class="quick-grid">
                <button type="button" @click="selectModule('walkin')">前台代预约</button>
                <button type="button" @click="selectModule('checkin')">签到候诊</button>
                <button type="button" @click="selectModule('patients')">患者档案</button>
                <button type="button" @click="selectModule('bills')">账单与收款</button>
              </div>
              <p class="tiny">门店与医生：{{ demoClinics.map(c => c.name).join(' / ') }} · {{ demoDoctors.map(d => d.name).join(' / ') }}</p>
            </section>
          </div>
        </section>
      </template>

      <template v-else-if="current && demoEnabled">
        <section class="panel module-panel">
          <div class="module-toolbar">
            <input v-model="search" placeholder="在当前演示列表中搜索…" />
            <div>
              <span class="result-count">{{ rows.length }} 条演示记录</span>
              <button v-if="coreModule" type="button" class="primary" @click="openBusinessForm">
                {{ moduleKey === 'walkin' ? '新增代预约' : moduleKey === 'plans' ? '新增治疗计划' : '新增采购单' }}
              </button>
            </div>
          </div>

          <div v-if="moduleKey === 'calendar'" class="week-board">
            <div v-for="day in weekDays" :key="day.key" class="board-day">
              <h3>{{ day.label }}</h3>
              <button v-for="a in dayAppointments(day.key)" :key="a.id" type="button" class="appt-card" @click="openAppointment(a.id)">
                <strong>{{ a.time.slice(11) }}</strong>
                <span>{{ patientName(a.patientId) }} · {{ demoDoctors.find(d => d.id === a.doctorId)?.name }}</span>
                <small>{{ a.item }} · {{ a.chair }}</small>
              </button>
              <p v-if="dayAppointments(day.key).length === 0" class="empty-day">暂无预约</p>
            </div>
          </div>

          <div v-else class="table-wrap">
            <table>
              <thead><tr><th v-for="col in current.columns" :key="col.key">{{ col.label }}</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="row in rows" :key="String(row._id)">
                  <td v-for="col in current.columns" :key="col.key">
                    <span v-if="col.key.includes('状态')" :class="statusClass(row[col.key])">{{ cellText(row[col.key]) }}</span>
                    <span v-else>{{ cellText(row[col.key]) }}</span>
                  </td>
                  <td class="row-actions">
                    <button type="button" @click="selectedRow = row">详情</button>
                    <button v-for="action in current.actions" :key="action.key" type="button" :class="action.tone" :disabled="actionDisabled(action, row)" @click="act(action.key, row)">{{ action.label }}</button>
                  </td>
                </tr>
                <tr v-if="rows.length === 0"><td :colspan="current.columns.length + 1" class="empty">没有匹配的演示记录。</td></tr>
              </tbody>
            </table>
          </div>
          <p class="module-note">{{ current.note ?? '所有“新增/操作”均为本地演示，不调用真实接口。' }}</p>
        </section>
      </template>

      <section v-else-if="!demoEnabled" class="panel module-panel off-panel">
        <h2>{{ canUseDemo ? '该页面需要显式开启本地演示' : '演示模式仅在本地开发服务器可用' }}</h2>
        <p>{{ canUseDemo ? '关闭演示后不会继续展示样例数据，避免把模拟记录误认为真实结果。' : '正式环境不提供演示入口，请使用真实员工登录后的授权页面。' }}</p>
        <button v-if="canUseDemo" type="button" class="primary" @click="demoEnabled = true">开启演示查看</button>
      </section>

      <p v-if="toast" class="toast" role="status">{{ toast }}</p>
    </main>

    <div v-if="selectedRow" class="drawer-mask" @click.self="selectedRow = null">
      <aside class="drawer">
        <div class="drawer-head"><h2>演示记录详情</h2><button type="button" @click="selectedRow = null">关闭</button></div>
        <p class="demo-chip">本地模拟，未写入后台</p>
        <dl>
          <template v-for="(value, key) in selectedRow" :key="key">
            <template v-if="!String(key).startsWith('_')"><dt>{{ key }}</dt><dd>{{ cellText(value) }}</dd></template>
          </template>
        </dl>
        <button type="button" @click="selectedRow = null">返回列表</button>
      </aside>
    </div>

    <div v-if="businessForm" class="drawer-mask" @click.self="businessForm = ''">
      <form class="dialog business-dialog" @submit.prevent="businessForm === 'walkin' ? submitWalkin() : businessForm === 'plan' ? submitPlan() : submitPurchase()">
        <template v-if="businessForm === 'walkin'">
          <h2>新增前台代预约</h2>
          <p>本地演示，不占真实号源；新增后会同步到预约列表、工作台和日历。</p>
          <label>患者<select v-model="walkin.patientId"><option v-for="p in demoPatients" :key="p.id" :value="p.id">{{ p.name }} · {{ p.id }}</option></select></label>
          <label>门店<select v-model="walkin.clinicId"><option v-for="c in demoClinics" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
          <label>医生<select v-model="walkin.doctorId"><option v-for="d in walkinDoctors" :key="d.id" :value="d.id">{{ d.name }} · {{ d.title }}</option></select></label>
          <label>项目<select v-model="walkin.item"><option v-for="s in demoServices" :key="s.id" :value="s.name">{{ s.name }} · {{ s.duration }}</option></select></label>
          <div class="form-grid">
            <label>日期<input v-model="walkin.date" type="date" required /></label>
            <label>时段<select v-model="walkin.time"><option v-for="slot in slots" :key="slot" :value="slot">{{ slot }}</option></select></label>
          </div>
        </template>
        <template v-else-if="businessForm === 'plan'">
          <h2>新增治疗计划</h2>
          <p>本地演示计划，不写入真实病历或收费目录。</p>
          <label>患者<select v-model="planForm.patientId"><option v-for="p in demoPatients" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
          <label>治疗项目<select v-model="planForm.item"><option v-for="s in demoServices" :key="s.id" :value="s.name">{{ s.name }}</option></select></label>
          <div class="form-grid">
            <label>计划次数<input v-model.number="planForm.count" type="number" min="1" max="99" step="1" required /></label>
            <label>负责医生<select v-model="planForm.doctorId"><option v-for="d in demoDoctors" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
          </div>
          <label>说明<textarea v-model="planForm.note" maxlength="120" placeholder="例如：复诊优先原主治医生"></textarea></label>
        </template>
        <template v-else>
          <h2>新增采购单</h2>
          <p>本地演示采购，不生成应付款、不自动入库。</p>
          <label>供应商<select v-model="purchaseForm.supplierId"><option v-for="s in demoSuppliers" :key="s.id" :value="s.id">{{ s.name }}</option></select></label>
          <label>物料<select v-model="purchaseForm.materialId" @change="selectMaterial"><option v-for="m in demoMaterials" :key="m.id" :value="m.id">{{ m.name }} · {{ m.unit }}</option></select></label>
          <div class="form-grid">
            <label>数量<input v-model.number="purchaseForm.quantity" type="number" min="1" max="1000" step="1" required /></label>
            <label>单价（元）<input v-model="purchaseForm.unitPrice" inputmode="decimal" required /></label>
          </div>
          <div class="calc">合计：<strong>{{ formatCents(purchaseTotal) }}</strong><span>（本地演示）</span></div>
        </template>
        <p v-if="businessError" class="form-error" role="alert">{{ businessError }}</p>
        <div class="dialog-actions">
          <button type="button" @click="businessForm = ''">取消</button>
          <button class="primary" type="submit">加入本地演示</button>
        </div>
      </form>
    </div>

    <div v-if="paymentBill" class="drawer-mask" @click.self="paymentBill = null">
      <form class="dialog" @submit.prevent="submitPayment">
        <h2>模拟收款</h2>
        <p>账单 {{ paymentBill.id }} · 总额 {{ paymentBill.amount }} · 待付 {{ formatCents(billRemainingCents(paymentBill)) }}</p>
        <p class="demo-chip">本地模拟，未发起真实支付；不会自动生成发票。</p>
        <label>本次收款金额（元）<input v-model="paymentAmount" inputmode="decimal" required /></label>
        <p v-if="paymentError" class="form-error" role="alert">{{ paymentError }}</p>
        <div class="dialog-actions">
          <button type="button" @click="paymentBill = null">取消</button>
          <button class="primary" type="submit" :disabled="paymentBusy">{{ paymentBusy ? '处理中…' : '确认模拟收款' }}</button>
        </div>
      </form>
    </div>

  </div>
</template>

<style scoped>
.admin-shell { min-height: 100vh; background: #f2f8f7; color: #183b39; display: grid; grid-template-columns: 238px minmax(0, 1fr); }
.sidebar { background: #0f3f3b; color: #e8f5f1; padding: 18px 12px; display: flex; flex-direction: column; gap: 18px; position: sticky; top: 0; height: 100vh; overflow: auto; }
.brand { display: flex; gap: 10px; align-items: center; padding: 4px 8px 12px; border-bottom: 1px solid #2b5d56; }
.brand-mark { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; background: #5fd0b2; color: #073c36; font-weight: 800; }
.brand strong { display: block; font-size: 15px; }.brand span { display: block; color: #9bc8c0; font-size: 11px; margin-top: 2px; }
.nav-group { margin-bottom: 13px; }.nav-group p { margin: 0 8px 5px; color: #87b8af; font-size: 11px; letter-spacing: .08em; }
.nav-group button { width: 100%; display: flex; justify-content: space-between; align-items: center; background: transparent; border: 0; color: #d7ebe6; padding: 7px 9px; border-radius: 7px; font: inherit; font-size: 13px; cursor: pointer; text-align: left; }
.nav-group button:hover, .nav-group button.active { background: #1c5b53; color: #fff; }.nav-group button.real { color: #b6e6d9; }
.nav-group em { font-style: normal; font-size: 9px; padding: 2px 4px; border: 1px solid #477b72; border-radius: 4px; color: #a6d5ca; }
.sidebar-foot { margin-top: auto; border-top: 1px solid #2b5d56; padding: 12px 8px 2px; display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.link { background: none; border: 0; color: #9ee2d1; cursor: pointer; padding: 0; }
.content { padding: 20px 24px 40px; min-width: 0; }
.topbar { display: flex; justify-content: space-between; gap: 18px; align-items: start; margin-bottom: 14px; }
.topbar h1 { margin: 0; font-size: 24px; letter-spacing: -.02em; }.topbar p { margin: 5px 0 0; color: #5b7774; font-size: 13px; }
.top-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: end; }
.demo-chip, .live-chip { font-size: 11px; padding: 5px 8px; border-radius: 999px; background: #fff2d7; color: #8a5b10; white-space: nowrap; }
.live-chip { background: #e2f6ed; color: #26664e; }.switch { font-size: 12px; display: flex; gap: 5px; align-items: center; color: #466763; }
.demo-banner, .off-banner { padding: 10px 13px; border-radius: 8px; font-size: 12px; line-height: 1.6; margin-bottom: 16px; background: #fff7e7; color: #795218; border: 1px solid #f1ddae; }
.off-banner { background: #eef7f5; color: #315f59; border-color: #cde5df; }.off-banner button { margin-left: 8px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.metric { background: #fff; border: 1px solid #dbeae6; border-radius: 11px; padding: 16px; display: grid; gap: 5px; }
.metric span { font-size: 12px; color: #64817c; }.metric strong { font-size: 26px; color: #0f4b43; }.metric small { font-size: 11px; color: #8aa19d; }
.dash-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .9fr); gap: 14px; margin-bottom: 14px; }.dash-grid.lower { grid-template-columns: minmax(0, 1.4fr) minmax(280px, .8fr); }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 11px; padding: 16px; min-width: 0; }.panel-head { display: flex; justify-content: space-between; gap: 10px; align-items: start; margin-bottom: 10px; }
.panel h2 { margin: 0; font-size: 16px; }.panel-head p { margin: 4px 0 0; color: #73908a; font-size: 11px; }.panel-head button, .row-actions button, .quick-grid button, .module-toolbar button, .drawer button, .dialog button { border: 1px solid #c4ddd7; background: #fff; color: #1e5a50; border-radius: 6px; padding: 6px 9px; font: inherit; font-size: 12px; cursor: pointer; }
button.primary, .quick-grid button.primary, .dialog button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }.row-actions button.danger { color: #a74334; border-color: #ebc6be; }.row-actions button.primary { color: #fff; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }.table-wrap { overflow: auto; }th { color: #6e8985; font-weight: 500; text-align: left; padding: 9px 8px; border-bottom: 1px solid #e7f0ed; white-space: nowrap; }td { padding: 10px 8px; border-bottom: 1px solid #edf4f2; vertical-align: middle; }.row-actions { white-space: nowrap; display: flex; gap: 4px; }.empty { text-align: center; color: #8da19d; padding: 28px; }
.status { display: inline-block; padding: 3px 7px; border-radius: 999px; background: #edf4f2; color: #5d7773; font-size: 11px; white-space: nowrap; }.status.good { background: #dff4ea; color: #1c6e52; }.status.warn { background: #fff2d7; color: #8a5b10; }.status.bad { background: #ffebe5; color: #9a3d2e; }
.side-panel .list-row { display: flex; align-items: center; gap: 9px; padding: 9px 0; border-bottom: 1px solid #eff5f3; }.avatar { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; background: #d6f2e8; color: #1c6a56; font-size: 12px; font-weight: 600; flex: 0 0 auto; }.avatar.soft { background: #e8f0ff; color: #355d9b; }.list-row div { flex: 1; min-width: 0; }.list-row strong { font-size: 12px; }.list-row p { margin: 3px 0 0; color: #7d9691; font-size: 11px; overflow-wrap: anywhere; }.divider { height: 1px; background: #e6efec; margin: 8px 0; }
.mini-calendar { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 6px; }.day-col { min-height: 122px; background: #f6fbf9; border-radius: 7px; padding: 7px; display: flex; flex-direction: column; gap: 5px; }.day-col > span { font-size: 10px; color: #6d8b85; }.day-col button { font-size: 10px; text-align: left; border: 0; border-radius: 4px; padding: 4px; background: #d9f0e8; color: #1c5d51; cursor: pointer; }.day-col small { color: #a1b6b1; font-size: 10px; }
.quick-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.quick-grid button { padding: 13px 8px; background: #f2faf7; }.tiny { color: #8ca19c; font-size: 11px; line-height: 1.6; margin-top: 12px; }
.module-toolbar { display: flex; justify-content: space-between; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }.module-toolbar input { border: 1px solid #cfe1dc; border-radius: 7px; padding: 8px 10px; min-width: 230px; font: inherit; font-size: 12px; }.module-toolbar > div { display: flex; align-items: center; gap: 10px; }.result-count { color: #78928d; font-size: 11px; }.module-note { color: #839b96; font-size: 11px; margin: 12px 0 0; }
.week-board { display: grid; grid-template-columns: repeat(7, minmax(120px, 1fr)); gap: 8px; overflow: auto; padding-bottom: 4px; }.board-day { background: #f7fbfa; border: 1px solid #e5f0ed; border-radius: 8px; padding: 9px; min-height: 190px; }.board-day h3 { margin: 0 0 8px; font-size: 11px; color: #5e7d77; }.appt-card { width: 100%; display: grid; gap: 3px; text-align: left; border: 0; border-left: 3px solid #43b99b; border-radius: 4px; background: #e8f8f1; color: #225e52; padding: 7px; margin-bottom: 6px; cursor: pointer; font: inherit; }.appt-card strong { font-size: 11px; }.appt-card span, .appt-card small { font-size: 10px; }.empty-day { color: #a4b7b2; font-size: 10px; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 20; background: #123f39; color: #e8fff8; padding: 10px 14px; border-radius: 8px; font-size: 12px; box-shadow: 0 8px 25px #0c332f33; }
.drawer-mask { position: fixed; inset: 0; background: #092a2633; z-index: 30; display: flex; justify-content: flex-end; }.drawer { background: #fff; width: min(390px, 92vw); height: 100%; padding: 20px; overflow: auto; }.drawer-head { display: flex; justify-content: space-between; align-items: center; }.drawer h2 { margin: 0; font-size: 18px; }.drawer dl { display: grid; grid-template-columns: 92px 1fr; gap: 9px; font-size: 12px; }.drawer dt { color: #77918b; }.drawer dd { margin: 0; overflow-wrap: anywhere; }.drawer > button { margin-top: 15px; }
.dialog { margin: auto; background: #fff; width: min(420px, 92vw); border-radius: 10px; padding: 20px; display: grid; gap: 12px; }.dialog h2 { margin: 0; font-size: 18px; }.dialog p { margin: 0; color: #718b85; font-size: 12px; }.dialog label { display: grid; gap: 5px; font-size: 12px; }.dialog input { border: 1px solid #cfe1dc; border-radius: 6px; padding: 8px; font: inherit; }.dialog-actions { display: flex; justify-content: end; gap: 8px; }
.business-dialog { width: min(520px, 94vw); }.dialog select, .dialog textarea { border: 1px solid #cfe1dc; border-radius: 6px; padding: 8px; font: inherit; background: #fff; }.dialog textarea { min-height: 58px; resize: vertical; }.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.calc { padding: 9px 11px; border-radius: 7px; background: #f1faf7; color: #3e665f; font-size: 12px; }.calc strong { color: #146455; font-size: 15px; }.calc span { color: #8a9f9a; margin-left: 5px; }.form-error { color: #9b382b !important; background: #fff0eb; padding: 8px 10px; border-radius: 6px; }.row-actions button:disabled, .dialog button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 1120px) { .metric-grid { grid-template-columns: repeat(2, 1fr); }.dash-grid, .dash-grid.lower { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .admin-shell { grid-template-columns: 1fr; }.nav { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 10px; }.sidebar-foot { margin-top: 0; }.content { padding: 14px; }.topbar { display: block; }.top-actions { justify-content: start; margin-top: 10px; }.metric-grid { grid-template-columns: 1fr 1fr; }.mini-calendar { grid-template-columns: repeat(2, 1fr); } }

/* Shared visual language with the patient mini program. */
.admin-shell { background: #f5faf8; color: #24413e; grid-template-columns: 252px minmax(0, 1fr); font-family: "Microsoft YaHei", "PingFang SC", system-ui, sans-serif; }
.sidebar { background: linear-gradient(170deg, #f7fcfb 0%, #edf8f6 58%, #eff5fc 100%); color: #24534f; padding: 24px 14px 18px; gap: 25px; border-right: 1px solid #d9eae7; box-shadow: 8px 0 30px #1b555009; }
.brand { padding: 0 10px 22px; border-color: #d9eae7; gap: 12px; }
.brand-mark { width: 42px; height: 42px; border-radius: 14px; background: linear-gradient(145deg, #b5ece7, #90d4d0); color: #245a58; font-size: 22px; box-shadow: 0 8px 22px #4a9a8e1a; }
.brand strong { font-size: 16px; letter-spacing: .025em; }
.brand span { color: #728e89; font-size: 11px; margin-top: 5px; }
.nav-group { margin-bottom: 19px; }
.nav-group p { color: #789690; font-size: 10px; font-weight: 700; letter-spacing: .15em; margin: 0 11px 8px; }
.nav-group button { min-height: 38px; padding: 9px 11px; margin: 2px 0; border-radius: 10px; color: #416c67; transition: background .18s ease, color .18s ease, transform .18s ease; }
.nav-group button:hover { background: #e5f4f0; transform: translateX(2px); }
.nav-group button.active { background: linear-gradient(90deg, #d4f1ea 0%, #dceff3 100%); color: #185f59; box-shadow: inset 3px 0 #48b6a6; font-weight: 700; }
.nav-group button.real { color: #286b63; }
.nav-group em { border: 0; background: #deeeed; border-radius: 999px; padding: 3px 7px; color: #537d78; }
.sidebar-foot { border-color: #d9eae7; padding: 16px 10px 3px; color: #557b75; }
.sidebar-foot .link { color: #267d75; }
.content { padding: 30px clamp(22px, 3vw, 46px) 60px; }
.topbar { align-items: center; margin-bottom: 21px; }
.topbar h1 { color: #173d3a; font-size: 27px; font-weight: 750; letter-spacing: -.03em; }
.topbar p { color: #758e87; font-size: 12px; margin-top: 8px; }
.demo-chip, .live-chip { padding: 8px 12px; font-weight: 700; letter-spacing: .01em; }
.demo-banner, .off-banner { border-radius: 12px; padding: 11px 15px; margin-bottom: 18px; box-shadow: 0 2px 10px #6d6a2f09; }
.welcome-hero { min-height: 238px; border-radius: 22px; overflow: hidden; margin-bottom: 18px; position: relative; display: flex; align-items: center; background: #e6f6f3 url('/dental-hero.png') right center / auto 115% no-repeat; box-shadow: 0 15px 35px #57a6a01a; }
.welcome-hero::before { content: ""; position: absolute; inset: 0; background: linear-gradient(90deg, #ebf9f5 0%, #eff9f7 46%, #eff9f7bb 64%, transparent 100%); }
.welcome-copy { position: relative; z-index: 1; max-width: 580px; padding: 28px 38px; }
.welcome-eyebrow { color: #288b78; font-size: 10px; font-weight: 800; letter-spacing: .18em; }
.welcome-copy h2 { color: #15564c; font-size: clamp(22px, 2.2vw, 31px); line-height: 1.25; margin: 12px 0 10px; letter-spacing: -.035em; }
.welcome-copy p { color: #527d72; max-width: 430px; line-height: 1.65; margin: 0 0 18px; font-size: 13px; }
.welcome-links { display: flex; gap: 10px; flex-wrap: wrap; }
.welcome-links button { border: 1px solid #8bcbb9; background: #fffdf9cf; color: #166454; border-radius: 999px; padding: 9px 14px; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; transition: background .18s, transform .18s; }
.welcome-links button:first-child { background: #278e84; border-color: #278e84; color: white; }
.welcome-links button:hover { transform: translateY(-2px); background: #e6f7ef; }
.welcome-links button:first-child:hover { background: #116e5e; }
.welcome-links span { margin-left: 6px; }
.metric-grid, .dash-grid { gap: 16px; margin-bottom: 17px; }
.metric, .panel { border: 1px solid #e4efeb; border-radius: 17px; box-shadow: 0 7px 24px #174c3f0b; }
.metric { padding: 20px 22px; min-height: 110px; position: relative; overflow: hidden; }
.metric::after { content: ""; position: absolute; top: -21px; right: -20px; width: 80px; height: 80px; background: #dff5ed; border-radius: 50%; }
.metric:nth-child(2)::after { background: #e4f1fa; }
.metric:nth-child(3)::after { background: #fbefdc; }
.metric:nth-child(4)::after { background: #f0eafa; }
.metric > * { position: relative; z-index: 1; }
.metric span { color: #63817a; font-size: 12px; }
.metric strong { color: #155d51; font-size: 31px; line-height: 1.2; }
.metric small { color: #879e97; }
.panel { padding: 21px 22px; }
.panel h2 { color: #254d46; font-size: 16px; }
.panel-head { align-items: center; margin-bottom: 14px; }
.panel-head p { margin-top: 5px; }
.panel-head button, .row-actions button, .quick-grid button, .module-toolbar button, .drawer button, .dialog button { border-radius: 9px; transition: background .16s, border-color .16s, transform .16s; }
.panel-head button:hover, .quick-grid button:hover, .module-toolbar button:hover, .row-actions button:hover { background: #e9f8f2; border-color: #9fd7c7; }
.quick-grid button { background: #eff9f5; padding: 16px 12px; font-weight: 700; }
th { background: #f8fbfa; color: #6c8a81; font-weight: 700; }
tbody tr:hover { background: #f7fcf9; }
td { border-color: #edf2ef; }
.day-col, .board-day { background: #f7fbf9; border: 1px solid #e9f2ee; border-radius: 11px; }
.day-col button { border-radius: 6px; }
.drawer, .dialog { box-shadow: -18px 0 45px #173e3b1c; }
.dialog { border-radius: 18px; }
.module-toolbar input, .dialog input, .dialog select, .dialog textarea { border-radius: 9px; }
@media (max-width: 760px) { .admin-shell { grid-template-columns: 1fr; }.content { padding: 18px 15px 38px; }.welcome-hero { min-height: 230px; background-size: auto 100%; background-position: right center; }.welcome-copy { padding: 24px; max-width: 85%; }.welcome-hero::before { background: linear-gradient(90deg, #e6f8f2 0%, #e6f8f2e8 65%, transparent 100%); } }

/* Mobile navigation: the sidebar becomes an on-demand drawer instead of pushing the work area below the fold. */
.mobile-menu { display: none; align-items: center; gap: 6px; min-height: 40px; padding: 8px 12px; border: 1px solid #cfe3dd; border-radius: 10px; background: #fff; color: #1d675c; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }
.nav-overlay { display: none; }
@media (max-width: 760px) {
  .admin-shell { display: grid; grid-template-columns: minmax(0, 1fr); }
  .sidebar { position: fixed; inset: 0 auto 0 0; z-index: 60; width: min(82vw, 318px); height: 100vh; max-height: none; padding: 20px 14px 18px; overflow: auto; transform: translateX(-102%); transition: transform .22s ease; border-right: 1px solid #d9eae7; box-shadow: 18px 0 45px rgba(19, 64, 57, .16); }
  .sidebar.open { transform: translateX(0); }
  .nav-overlay { display: block; position: fixed; inset: 0; z-index: 55; background: rgba(14, 48, 43, .34); }
  .nav { display: block; }
  .sidebar-foot { margin-top: 24px; }
  .content { padding: 18px 15px 40px; min-width: 0; }
  .topbar { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
  .topbar > div { flex: 1; min-width: 0; }
  .mobile-menu { display: inline-flex; flex: none; }
  .top-actions { width: 100%; justify-content: flex-start; margin-top: 2px; }
  .topbar h1 { font-size: 22px; }
  .welcome-copy { max-width: 100%; }
}


.nav-group > p { display: flex; align-items: center; gap: 7px; }
.nav-group-icon { width: 13px; height: 13px; flex: none; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; opacity: .78; }
.nav-group button.active + .nav-group-icon, .nav-group button.active .nav-group-icon { opacity: 1; }

</style>
