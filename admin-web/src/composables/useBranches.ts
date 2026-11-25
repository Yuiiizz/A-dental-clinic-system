import { computed, onScopeDispose, ref, watch } from 'vue';
import { useSessionStore } from '@/stores/session';
import { ApiError, createHttpClient } from '@/services/http';
import { newIdempotencyKey } from '@/services/account-admin';
import { createBranchApi, type BranchApi, type BranchDetail, type BranchRow, type Opening, type BranchAction } from '@/services/branches';

/** 每个页面实例独立；全操作互斥，并按身份代次丢弃迟到结果。 */
export function useBranches(injected?: BranchApi) {
  const session = useSessionStore();
  const api = injected ?? createBranchApi(createHttpClient({ baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1', timeoutMs: 30000, getCsrfToken: () => session.csrfToken }));
  const rows = ref<BranchRow[]>([]), cursor = ref<string | null>(null), selected = ref('');
  const detail = ref<BranchDetail | null>(null), hours = ref<Opening | null>(null);
  const busy = ref(false), message = ref(''), error = ref(''), resetRevision = ref(0);
  const ready = computed(() => session.status === 'ready' && session.me?.identity_type === 'staff');
  let generation = 0, alive = true;
  const keys = new Map<string, { fingerprint: string; key: string }>();
  const identity = () => JSON.stringify([session.sessionRevision, session.status, session.me?.account_id, session.me?.roles]);
  function clear() { generation++; rows.value = []; cursor.value = null; selected.value = ''; detail.value = null; hours.value = null; busy.value = false; message.value = ''; error.value = ''; keys.clear(); resetRevision.value++; }
  watch(identity, clear, { flush: 'sync' });
  onScopeDispose(() => { alive = false; clear(); });
  function keyFor(operation: string, body: object) {
    const fingerprint = JSON.stringify(body), old = keys.get(operation);
    if (old?.fingerprint === fingerprint) return old.key;
    const key = newIdempotencyKey(); keys.set(operation, { fingerprint, key }); return key;
  }
  async function run(work: (current: () => boolean) => Promise<void>) {
    if (busy.value || !ready.value || session.authSubmitting) return false;
    const g = ++generation, who = identity();
    const current = () => alive && g === generation && who === identity();
    busy.value = true; error.value = ''; message.value = '';
    try { await work(current); return current(); }
    catch (e) {
      if (!current()) return false;
      if (e instanceof ApiError && e.status === 401) {
        await session.fetchMe();
        if (current()) error.value = '会话状态已重新核对，请确认登录后重试。';
      } else if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
        clear(); error.value = '当前无权访问该门店，或门店已不可见。请重新加载列表。';
      } else {
        error.value = e instanceof ApiError && e.status === 409
          ? '数据版本或业务条件已变化。草稿已保留，请重新读取并核对，不能直接覆盖。'
          : e instanceof ApiError && e.status === 422 ? e.message
          : '请求结果未确认。请重新读取核对；不要把超时当作保存失败。';
      }
      return false;
    } finally { if (current()) busy.value = false; }
  }
  const list = (more = false) => run(async current => {
    const page = await api.list(more ? cursor.value ?? undefined : undefined);
    if (!current()) return;
    rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor;
  });
  const select = (id: string) => run(async current => {
    selected.value = id; detail.value = null; hours.value = null;
    const d = await api.detail(id); if (current()) detail.value = d;
  });
  const create = (body: object) => run(async current => {
    const result = await api.create(body, keyFor('create', body));
    if (!current()) return;
    if (result.reauthentication_required) {
      session.clearSession(); message.value = '门店已创建。授权范围已更新，请重新登录后继续配置。';
    } else message.value = '已确认创建结果，请刷新门店列表。';
  });
  const mutate = (action: 'patch' | BranchAction, fields: object) => {
    if (!detail.value) return Promise.resolve(false);
    const id = selected.value, body = { ...fields, expected_version: detail.value.version };
    return run(async current => {
      const key = keyFor(`${id}/${action}`, body);
      detail.value = null; hours.value = null;
      await api.mutate(id, action, body, key);
      if (!current()) return;
      // 旧动作重放可能返回历史结果；始终GET当前权威详情。
      const d = await api.detail(id);
      if (current()) { detail.value = d; rows.value = rows.value.map(row => row.branch_id === d.branch_id ? d : row); message.value = '操作已完成，已读取最新门店资料。'; }
    });
  };
  const readHours = (from: string, to: string) => run(async current => {
    hours.value = null;
    const h = await api.getOpening(selected.value, from, to);
    if (current()) { hours.value = h; detail.value = null; }
  });
  const saveHours = (intervals: Opening['intervals']) => {
    if (!hours.value) return Promise.resolve(false);
    const h = hours.value, body = { from_date: h.from_date, to_date: h.to_date, intervals, expected_version: h.version };
    return run(async current => {
      hours.value = null; detail.value = null;
      const updated = await api.putOpening(h.branch_id, body, keyFor(`${h.branch_id}/hours`, body));
      if (current()) { hours.value = updated; message.value = '营业时间已保存，显示当前权威结果。'; }
    });
  };
  return { session, ready, rows, cursor, selected, detail, hours, busy, message, error, resetRevision, list, select, create, mutate, readHours, saveHours };
}
