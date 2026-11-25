import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { useAccountAdminStore } from '@/stores/account-admin';

const api = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  getRoleScopes: vi.fn(),
  setRoleScopes: vi.fn(),
  disable: vi.fn(),
}));

vi.mock('@/services/account-admin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/account-admin')>();
  return { ...actual, createAccountAdminApi: () => api };
});

const sysadminMe = {
  account_id: '1',
  name: '管理员',
  identity_type: 'staff' as const,
  roles: [{ role_code: 'sysadmin', branch_ids: [] }],
  must_change_password: false,
};

const alice = { account_id: '10', login_name: 'alice', enabled: true, state: 'active' as const };
const bob = { account_id: '11', login_name: 'bob', enabled: true, state: 'active' as const };
const createdAccount = {
  account_id: '20',
  login_name: 'new-staff',
  staff_id: '1001',
  enabled: false,
  must_change_password: true,
  state: 'pending_activation' as const,
};

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('账号管理 store：缓存隔离与防重', () => {
  function readySession(): ReturnType<typeof useSessionStore> {
    const session = useSessionStore();
    session.status = 'ready';
    session.me = sysadminMe;
    return session;
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    api.list.mockReset();
    api.create.mockReset();
    api.getRoleScopes.mockReset();
    api.setRoleScopes.mockReset().mockResolvedValue({ account_id: '10', version: 2 });
    api.disable.mockReset().mockResolvedValue(undefined);
  });

  it('create 重复调用只执行一次，第二次返回未执行 busy', async () => {
    readySession();
    const pending = deferred<typeof createdAccount>();
    api.create.mockReturnValue(pending.promise);
    const store = useAccountAdminStore();

    const first = store.create({ staff_id: '1001', login_name: 'new-staff' }, 'key-1');
    const second = await store.create({ staff_id: '1001', login_name: 'new-staff' }, 'key-1');

    expect(second).toMatchObject({ executed: false, stale: false, reason: 'busy' });
    expect(api.create).toHaveBeenCalledTimes(1);

    pending.resolve(createdAccount);
    expect(await first).toMatchObject({ executed: true, stale: false, data: createdAccount });
  });

  it('setRoles / disable 重复调用在 store 层被拦截', async () => {
    readySession();
    const rolesPending = deferred<{ account_id: string; version: number }>();
    const disablePending = deferred<void>();
    api.setRoleScopes.mockReturnValueOnce(rolesPending.promise);
    api.disable.mockReturnValueOnce(disablePending.promise);
    const store = useAccountAdminStore();
    store.selectAccount('10');

    const rolesFirst = store.setRoles('10', 1, []);
    const rolesSecond = await store.setRoles('10', 1, []);
    expect(rolesSecond).toMatchObject({ executed: false, reason: 'busy' });
    expect(api.setRoleScopes).toHaveBeenCalledTimes(1);
    rolesPending.resolve({ account_id: '10', version: 2 });
    expect(await rolesFirst).toMatchObject({ executed: true, stale: false });

    const disableFirst = store.disable('10', '离职');
    const disableSecond = await store.disable('10', '离职');
    expect(disableSecond).toMatchObject({ executed: false, reason: 'busy' });
    expect(api.disable).toHaveBeenCalledTimes(1);
    disablePending.resolve();
    expect(await disableFirst).toMatchObject({ executed: true, stale: false });
  });

  it('身份未确认或非管理员时变更返回未执行，不调用 API', async () => {
    const session = useSessionStore();
    session.status = 'unverified';
    const store = useAccountAdminStore();
    expect(await store.create({ staff_id: '1001', login_name: 'new-staff' }, 'key')).toMatchObject({
      executed: false,
      reason: 'not-ready',
    });
    expect(api.create).not.toHaveBeenCalled();

    session.status = 'ready';
    session.me = { ...sysadminMe, roles: [{ role_code: 'owner', branch_ids: ['1'] }] };
    expect(await store.disable('10', '离职')).toMatchObject({ executed: false, reason: 'not-admin' });
    expect(api.disable).not.toHaveBeenCalled();
  });

  it('旧列表晚到不覆盖新代次；旧 401 被丢弃', async () => {
    const session = readySession();
    const oldList = deferred<{ items: (typeof alice)[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(oldList.promise);
    const store = useAccountAdminStore();

    const oldRequest = store.load(true);
    session.clearSession();
    session.status = 'ready';
    session.me = sysadminMe;

    const page = { items: [alice], nextCursor: null };
    oldList.resolve(page);
    await oldRequest;
    expect(store.accounts).toEqual([]);

    const failedList = deferred<{ items: (typeof alice)[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(failedList.promise);
    const failedRequest = store.load(true);
    session.clearSession();
    session.status = 'ready';
    session.me = sysadminMe;
    failedList.reject(new ApiError('UNAUTHENTICATED', 401, 'raw-secret'));
    await expect(failedRequest).resolves.toBeUndefined();
  });

  it('并行 reset 只有最新结果落地', async () => {
    readySession();
    const first = deferred<{ items: (typeof alice)[]; nextCursor: null }>();
    const second = deferred<{ items: (typeof bob)[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const store = useAccountAdminStore();

    const firstRequest = store.load(true);
    const secondRequest = store.load(true);
    first.resolve({ items: [alice], nextCursor: null });
    second.resolve({ items: [bob], nextCursor: null });
    await Promise.all([firstRequest, secondRequest]);

    expect(store.accounts).toEqual([bob]);
  });

  it('分页结果不能串入新 reset 查询', async () => {
    readySession();
    api.list.mockResolvedValueOnce({ items: [alice], nextCursor: '10' });
    const store = useAccountAdminStore();
    await store.load(true);
    expect(store.nextCursor).toBe('10');

    const pagination = deferred<{ items: (typeof bob)[]; nextCursor: null }>();
    const reset = deferred<{ items: (typeof alice)[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(pagination.promise).mockReturnValueOnce(reset.promise);
    const paginationRequest = store.load(false);
    const resetRequest = store.load(true);
    pagination.resolve({ items: [bob], nextCursor: null });
    reset.resolve({ items: [alice], nextCursor: null });
    await Promise.all([paginationRequest, resetRequest]);

    expect(store.accounts).toEqual([alice]);
  });

  it('变更等待期间身份变成 unverified 返回 stale，不把结果交给当前 UI', async () => {
    const session = readySession();
    const pending = deferred<typeof createdAccount>();
    api.create.mockReturnValue(pending.promise);
    const store = useAccountAdminStore();

    const request = store.create({ staff_id: '1001', login_name: 'new-staff' }, 'key-stale');
    session.status = 'unverified';
    pending.resolve(createdAccount);
    const outcome = await request;

    expect(outcome).toMatchObject({ executed: true, stale: true });
    expect(outcome.data).toBeUndefined();
  });

  it('角色详情读取成功，切换目标后旧详情不落地', async () => {
    readySession();
    const pending = deferred<{
      account_id: string;
      state: 'active';
      version: number;
      roles: Array<{ role_code: string; branch_ids: string[]; approval_basis: string }>;
    }>();
    api.getRoleScopes.mockReturnValueOnce(pending.promise);
    const store = useAccountAdminStore();
    store.selectAccount('10');
    const request = store.loadRoleScopes('10');
    store.selectAccount('11');
    pending.resolve({
      account_id: '10',
      state: 'active',
      version: 1,
      roles: [{ role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '批准' }],
    });
    const outcome = await request;

    expect(store.roleDetail).toBeNull();
    expect(outcome).toMatchObject({
      executed: true,
      stale: true,
      requestId: expect.any(Number),
      accountId: '10',
    });
  });

  it('同目标并行读取只有最新结果落地，旧结果显式 stale', async () => {
    readySession();
    const first = deferred<{
      account_id: string;
      state: 'active';
      version: number;
      roles: Array<{ role_code: string; branch_ids: string[]; approval_basis: string }>;
    }>();
    const second = deferred<{
      account_id: string;
      state: 'active';
      version: number;
      roles: Array<{ role_code: string; branch_ids: string[]; approval_basis: string }>;
    }>();
    api.getRoleScopes.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const store = useAccountAdminStore();
    store.selectAccount('10');
    const firstRequest = store.loadRoleScopes('10');
    const secondRequest = store.loadRoleScopes('10');

    second.resolve({
      account_id: '10',
      state: 'active',
      version: 2,
      roles: [{ role_code: 'owner', branch_ids: ['2'], approval_basis: '新批准' }],
    });
    expect(await secondRequest).toMatchObject({
      executed: true,
      stale: false,
      requestId: expect.any(Number),
    });
    first.resolve({
      account_id: '10',
      state: 'active',
      version: 1,
      roles: [{ role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '旧批准' }],
    });
    expect(await firstRequest).toMatchObject({
      executed: true,
      stale: true,
      requestId: expect.any(Number),
    });
    expect(store.roleDetail?.version).toBe(2);
    expect(store.roleDetail?.roles[0]?.role_code).toBe('owner');
  });

  it('未选中目标时读取返回显式 not-selected，不调用 API', async () => {
    readySession();
    const store = useAccountAdminStore();
    const outcome = await store.loadRoleScopes('10');

    expect(outcome).toMatchObject({ executed: false, stale: false, reason: 'not-selected' });
    expect(api.getRoleScopes).not.toHaveBeenCalled();
  });

  it('角色详情成功落地；保存期间切换目标返回 stale', async () => {
    readySession();
    api.getRoleScopes.mockResolvedValue({
      account_id: '10',
      state: 'active',
      version: 1,
      roles: [{ role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '批准' }],
    });
    const store = useAccountAdminStore();
    store.selectAccount('10');
    await store.loadRoleScopes('10');
    expect(store.roleDetail?.version).toBe(1);
    expect(store.roleDetail?.roles[0]?.role_code).toBe('frontdesk');

    const pending = deferred<{ account_id: string; version: number }>();
    api.setRoleScopes.mockReturnValueOnce(pending.promise);
    const save = store.setRoles('10', 1, []);
    store.selectAccount('11');
    pending.resolve({ account_id: '10', version: 2 });
    const outcome = await save;
    expect(outcome).toMatchObject({ executed: true, stale: true });
  });

  it('退出/切换身份立即清空列表、cursor、选中项与角色详情，并递增 cacheRevision', async () => {
    readySession();
    api.list.mockResolvedValueOnce({ items: [alice], nextCursor: '10' });
    const store = useAccountAdminStore();
    await store.load(true);
    api.getRoleScopes.mockResolvedValue({
      account_id: '10',
      state: 'active',
      version: 1,
      roles: [],
    });
    store.selectAccount('10');
    await store.loadRoleScopes('10');
    expect(store.roleDetail?.account_id).toBe('10');
    const revisionBefore = store.cacheRevision;

    useSessionStore().clearSession();

    expect(store.accounts).toEqual([]);
    expect(store.nextCursor).toBeNull();
    expect(store.selectedAccountId).toBeNull();
    expect(store.roleDetail).toBeNull();
    expect(store.cacheRevision).toBeGreaterThan(revisionBefore);
  });
});
