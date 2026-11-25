import { computed, onScopeDispose, ref, watch } from 'vue';
import { useSessionStore } from '@/stores/session';
import { ApiError, createHttpClient } from '@/services/http';
import { newIdempotencyKey } from '@/services/account-admin';
import { createStaffApi, type StaffApi, type StaffCreateInput, type StaffDetail, type StaffRow } from '@/services/staff';

export type CreateStatus = 'created' | 'unknown' | 'failed' | 'forbidden' | 'unauthorized' | 'conflict' | 'stale';

export interface CreateOutcome {
  executed: boolean;
  applied: boolean;
  status: CreateStatus;
  staff?: StaffDetail;
  message: string;
}

/**
 * 员工页面状态：列表、详情、建档各自有代次；旧请求不能覆盖新身份/新目标。
 * 建档幂等键只在内容变化或明确开始新建时更换；网络/超时/5xx 保留原键。
 */
export function useStaff(injected?: StaffApi) {
  const session = useSessionStore();
  const api = injected ?? createStaffApi(createHttpClient({
    baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
    timeoutMs: 30000,
    getCsrfToken: () => session.csrfToken,
  }));

  const rows = ref<StaffRow[]>([]);
  const nextCursor = ref<string | null>(null);
  const selectedId = ref('');
  const detail = ref<StaffDetail | null>(null);
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const createBusy = ref(false);
  const listError = ref('');
  const detailError = ref('');
  const createError = ref('');
  const message = ref('');
  const resetRevision = ref(0);

  const ready = computed(() => session.status === 'ready' && session.me?.identity_type === 'staff');
  let alive = true;
  let listGeneration = 0;
  let detailGeneration = 0;
  let createGeneration = 0;
  let createKey: string | null = null;
  let createFingerprint = '';

  const identity = () => JSON.stringify([
    session.sessionRevision,
    session.status,
    session.me?.account_id,
    session.me?.must_change_password,
    session.me?.roles,
  ]);

  function clear(): void {
    listGeneration++;
    detailGeneration++;
    createGeneration++;
    rows.value = [];
    nextCursor.value = null;
    selectedId.value = '';
    detail.value = null;
    listLoading.value = false;
    detailLoading.value = false;
    createBusy.value = false;
    listError.value = '';
    detailError.value = '';
    createError.value = '';
    message.value = '';
    createKey = null;
    createFingerprint = '';
    resetRevision.value++;
  }

  watch(identity, clear, { flush: 'sync' });
  onScopeDispose(() => {
    alive = false;
    clear();
  });

  function current(generation: number, who: string): boolean {
    return alive && generation === listGeneration && who === identity();
  }

  function currentDetail(generation: number, who: string): boolean {
    return alive && generation === detailGeneration && who === identity();
  }

  /** 列表/详情读取权限被拒绝：让所有在途读取失效，保留建档草稿与幂等键。 */
  function invalidateReads(reason: string): void {
    listGeneration++;
    detailGeneration++;
    rows.value = [];
    nextCursor.value = null;
    selectedId.value = '';
    detail.value = null;
    listLoading.value = false;
    detailLoading.value = false;
    listError.value = reason;
    detailError.value = reason;
  }

  async function handleAuth(e: ApiError, isCurrent: () => boolean, setError: (v: string) => void): Promise<void> {
    if (e.status !== 401) return;
    await session.fetchMe();
    if (isCurrent()) setError('会话状态已重新核对，请确认登录后重试。');
  }

  async function load(reset = true): Promise<boolean> {
    if (!ready.value || !alive) return false;
    if (!reset && (!nextCursor.value || listLoading.value)) return false;
    const generation = ++listGeneration;
    const who = identity();
    const cursor = reset ? null : nextCursor.value;
    listLoading.value = true;
    listError.value = '';
    try {
      const page = await api.list(cursor ?? undefined);
      if (!current(generation, who)) return false;
      const merged = reset ? page.items : [...rows.value, ...page.items];
      const unique = new Map<string, StaffRow>();
      for (const row of merged) unique.set(row.staff_id, row);
      rows.value = [...unique.values()];
      nextCursor.value = page.nextCursor;
      return true;
    } catch (e) {
      if (!current(generation, who)) return false;
      if (e instanceof ApiError) {
        if (e.status === 401) {
          await handleAuth(e, () => current(generation, who), v => { listError.value = v; });
        } else if (e.status === 403 || e.status === 404) {
          invalidateReads('当前无权读取该员工范围，或列表已不可见。请重新加载。');
        } else {
          listError.value = e.status === 0 || e.status >= 500
            ? '员工列表结果未确认；当前显示的是上次已确认数据。请稍后刷新。'
            : e.message;
        }
      } else {
        listError.value = '员工列表结果未确认，请稍后刷新。';
      }
      return false;
    } finally {
      if (current(generation, who)) listLoading.value = false;
    }
  }

  async function select(staffId: string): Promise<boolean> {
    if (!ready.value || !alive || !/^[1-9]\d*$/.test(staffId.trim())) return false;
    const id = staffId.trim();
    const generation = ++detailGeneration;
    const who = identity();
    selectedId.value = id;
    detail.value = null;
    detailError.value = '';
    detailLoading.value = true;
    try {
      const value = await api.detail(id);
      if (!currentDetail(generation, who)) return false;
      // A→B→A: only the latest target may land.
      if (selectedId.value !== id) return false;
      detail.value = value;
      return true;
    } catch (e) {
      if (!currentDetail(generation, who)) return false;
      if (e instanceof ApiError) {
        if (e.status === 401) {
          await handleAuth(e, () => currentDetail(generation, who), v => { detailError.value = v; });
        } else if (e.status === 403 || e.status === 404) {
          if (e.status === 403) {
            invalidateReads('当前无权读取该员工范围。请重新加载。');
          } else {
            detail.value = null;
            selectedId.value = '';
            detailError.value = '该员工不可见或不存在，请重新加载列表。';
          }
        } else {
          detailError.value = e.status === 0 || e.status >= 500
            ? '员工详情结果未确认，请重试。'
            : e.message;
        }
      } else {
        detailError.value = '员工详情结果未确认，请重试。';
      }
      return false;
    } finally {
      if (currentDetail(generation, who)) detailLoading.value = false;
    }
  }

  function beginNewCreate(): void {
    if (createBusy.value) return;
    createKey = null;
    createFingerprint = '';
    createError.value = '';
    message.value = '';
  }

  async function create(input: StaffCreateInput): Promise<CreateOutcome> {
    if (!ready.value || !alive || createBusy.value || session.authSubmitting) {
      return { executed: false, applied: false, status: 'failed', message: '当前不可提交建档。' };
    }
    const fingerprint = JSON.stringify(input);
    if (createKey === null || createFingerprint !== fingerprint) {
      createKey = newIdempotencyKey();
      createFingerprint = fingerprint;
    }
    const key = createKey;
    const generation = ++createGeneration;
    const who = identity();
    createBusy.value = true;
    createError.value = '';
    message.value = '';
    try {
      const staff = await api.create(input, key);
      if (!alive || generation !== createGeneration || who !== identity()) {
        return { executed: true, applied: false, status: 'stale', message: '建档结果属于旧会话，已忽略。' };
      }
      createKey = null;
      createFingerprint = '';
      message.value = '建档已确认，正在刷新详情与列表。';
      return { executed: true, applied: true, status: 'created', staff, message: message.value };
    } catch (e) {
      if (!alive || generation !== createGeneration || who !== identity()) {
        return { executed: true, applied: false, status: 'stale', message: '建档结果属于旧会话，已忽略。' };
      }
      if (e instanceof ApiError) {
        if (e.code === 'VALIDATION_FAILED') {
          createError.value = e.message || '请修正建档输入后再提交。';
          return { executed: true, applied: false, status: 'failed', message: createError.value };
        }
        if (e.status === 401) {
          await handleAuth(e, () => alive && generation === createGeneration && who === identity(),
            v => { createError.value = v; });
          return { executed: true, applied: false, status: 'unauthorized', message: createError.value };
        }
        if (e.status === 403) {
          createError.value = '仅组织负责人可提交建档；当前输入已保留，服务端仍是最终判定。';
          return { executed: true, applied: false, status: 'forbidden', message: createError.value };
        }
        if (e.code === 'INVALID_RESPONSE' || e.code === 'TIMEOUT' || e.code === 'NETWORK_ERROR'
          || e.status === 0 || e.status >= 500) {
          createError.value = '建档结果未确认。已保留原幂等键和当前输入；请勿自动重复提交，先核对结果。';
          return { executed: true, applied: false, status: 'unknown', message: createError.value };
        }
        if (e.status === 409 && e.code === 'IDEMPOTENCY_CONFLICT') {
          createKey = null;
          createFingerprint = '';
          createError.value = '同一幂等键的内容已变化，请重新核对后再提交。';
          return { executed: true, applied: false, status: 'conflict', message: createError.value };
        }
        createError.value = e.message;
        return { executed: true, applied: false, status: 'failed', message: createError.value };
      }
      createError.value = '建档结果未确认。已保留原幂等键和当前输入；请勿自动重复提交。';
      return { executed: true, applied: false, status: 'unknown', message: createError.value };
    } finally {
      if (alive && generation === createGeneration && who === identity()) createBusy.value = false;
    }
  }

  return {
    session, ready, rows, nextCursor, selectedId, detail, listLoading, detailLoading, createBusy,
    listError, detailError, createError, message, resetRevision,
    load, refresh: () => load(true), loadMore: () => load(false), select, create, beginNewCreate,
  };
}
