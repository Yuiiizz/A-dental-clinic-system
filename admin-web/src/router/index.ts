import { createRouter, createWebHistory } from 'vue-router';
import type { RouteLocationNormalized } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import type { SessionStatus } from '@/stores/session';
import HomeView from '@/views/HomeView.vue';
import LoginView from '@/views/LoginView.vue';
import ChangePasswordView from '@/views/ChangePasswordView.vue';
import AdminAccountsView from '@/views/AdminAccountsView.vue';
import WorkspaceView from '@/views/WorkspaceView.vue';

export interface GuardTarget {
  name?: unknown;
  meta?: { public?: boolean; roles?: string[] };
}

/** 纯函数化的守卫决策，便于单测；返回路由名对象或 true（放行）。 */
export function resolveRedirect(status: SessionStatus, to: GuardTarget, roleCodes: string[] = []): { name: string } | true {
  const authed = status === 'ready' || status === 'mustChangePassword';
  const isPublic = Boolean(to.meta?.public);

  if (isPublic) {
    // 已登录访问登录页 → 去改密页或首页；未登录放行。
    if (authed) return { name: status === 'mustChangePassword' ? 'change-password' : 'home' };
    return true;
  }

  // 受保护路由。
  // guest：未登录；unverified：会话存在但身份未确认（网络/5xx/结构异常），同样不得进入业务页，
  // 回登录页由页面展示“身份确认未完成/重试”，且登录页是 public 放行，不会形成重定向循环。
  if (status === 'guest' || status === 'unverified' || status === 'checking') return { name: 'login' };
  // 强制改密账号只能进改密页，不能靠 URL 进入首页。
  if (status === 'mustChangePassword' && to.name !== 'change-password') return { name: 'change-password' };
  // 已改密的账号不再停留改密页。
  if (status === 'ready' && to.name === 'change-password') return { name: 'home' };
  if (to.meta?.roles?.length && !to.meta.roles.some((role) => roleCodes.includes(role))) return { name: 'home' };
  return true;
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/password', name: 'change-password', component: ChangePasswordView },
    { path: '/', name: 'home', component: WorkspaceView },
    { path: '/account', name: 'account', component: HomeView },
    { path: '/admin/accounts', name: 'admin-accounts', component: AdminAccountsView, meta: { roles: ['sysadmin'] } },
    { path: '/branches', name: 'branches', component: () => import('@/views/BranchesView.vue'), meta: { roles: ['owner'] } },
    { path: '/staff-members', name: 'staff-members', component: () => import('@/views/StaffMembersView.vue'), meta: { roles: ['owner'] } },
    { path: '/scheduling', name: 'scheduling', component: () => import('@/views/SchedulingConfigView.vue'), meta: { roles: ['owner'] } },
    { path: '/frontdesk-schedule', name: 'frontdesk-schedule', component: () => import('@/views/FrontdeskScheduleView.vue'), meta: { roles: ['frontdesk'] } },
    { path: '/appointments', name: 'appointments-real', component: () => import('@/views/AppointmentWorkbenchView.vue'), meta: { roles: ['frontdesk'] } },
    { path: '/internal-messages', name: 'internal-messages', component: () => import('@/views/InternalMessagesView.vue'), meta: { roles: ['frontdesk', 'dentist'] } },
    { path: '/unavailability', name: 'unavailability', component: () => import('@/views/UnavailabilityView.vue'), meta: { roles: ['owner'] } },
    { path: '/suppliers', name: 'suppliers', component: () => import('@/views/SuppliersView.vue'), meta: { roles: ['owner', 'inventory'] } },
    { path: '/inventory', name: 'inventory-real', component: () => import('@/views/InventoryView.vue'), meta: { roles: ['owner', 'inventory'] } },
    { path: '/clinical', name: 'clinical', component: () => import('@/views/ClinicalWorkbenchView.vue'), meta: { roles: ['dentist'] } },
    { path: '/billing', name: 'billing', component: () => import('@/views/BillingWorkbenchView.vue'), meta: { roles: ['frontdesk', 'finance'] } },
    { path: '/treatment-plans', name: 'treatment-plans', component: () => import('@/views/TreatmentPlanView.vue'), meta: { roles: ['dentist'] } },
    ...(import.meta.env.DEV ? [{ path: '/demo', name: 'demo', component: WorkspaceView, props: { demo: true }, meta: { public: true } }] : []),
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});

router.beforeEach(async (to: RouteLocationNormalized) => {
  const store = useSessionStore();
  if (store.status === 'checking') {
    await store.bootstrap();
  }
  // 防御：bootstrap 后不应仍为 checking；若发生按未登录处理，避免守卫循环。
  if (store.status === 'checking') return { name: 'login' };
  return resolveRedirect(store.status, to as unknown as GuardTarget,
    (store.me?.roles ?? []).map((role) => role.role_code));
});

export default router;
