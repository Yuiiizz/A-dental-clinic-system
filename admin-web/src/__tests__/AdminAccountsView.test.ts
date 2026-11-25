import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, ref } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { ApiError } from '@/services/http';

const mocks = vi.hoisted(() => ({
  state: {
    accounts: [] as Array<Record<string, unknown>>,
    nextCursor: null as string | null,
    selectedAccountId: null as { value: string | null } | null,
    roleDetail: null as { value: Record<string, unknown> | null } | null,
    roleDetailLoading: false,
    cacheRevision: null as { value: number } | null,
    listLoading: false,
    createSubmitting: false,
    rolesSubmitting: false,
    disableSubmitting: false,
  },
  session: {
    sessionRevision: 0,
    me: {
      account_id: '1',
      name: '管理员',
      identity_type: 'staff',
      roles: [{ role_code: 'sysadmin', branch_ids: [] }],
      must_change_password: false,
    },
    status: null as { value: string } | null,
    csrfToken: 'csrf-token',
    verificationMessage: null as string | null,
  },
  fetchMe: vi.fn(),
  load: vi.fn(),
  create: vi.fn(),
  loadRoleScopes: vi.fn(),
  setRoles: vi.fn(),
  disable: vi.fn(),
  clearCache: vi.fn(),
  selectAccount: vi.fn(),
  replace: vi.fn(),
  push: vi.fn(),
}));

vi.mock('@/stores/session', () => ({
  useSessionStore: () => ({
    get me() {
      return mocks.session.me;
    },
    get status() {
      return mocks.session.status?.value ?? 'ready';
    },
    get csrfToken() {
      return mocks.session.csrfToken;
    },
    get verificationMessage() {
      return mocks.session.verificationMessage;
    },
    get isSysadmin() {
      return mocks.session.me.roles.some((role) => role.role_code === 'sysadmin');
    },
    get sessionRevision() {
      return mocks.session.sessionRevision;
    },
    fetchMe: mocks.fetchMe,
  }),
}));

vi.mock('@/stores/account-admin', () => ({
  useAccountAdminStore: () => ({
    get accounts() {
      return mocks.state.accounts;
    },
    get nextCursor() {
      return mocks.state.nextCursor;
    },
    get selectedAccountId() {
      return mocks.state.selectedAccountId?.value ?? null;
    },
    get roleDetail() {
      return mocks.state.roleDetail?.value ?? null;
    },
    get roleDetailLoading() {
      return mocks.state.roleDetailLoading;
    },
    get cacheRevision() {
      return mocks.state.cacheRevision?.value ?? 0;
    },
    get hasMore() {
      return mocks.state.nextCursor !== null;
    },
    get listLoading() {
      return mocks.state.listLoading;
    },
    get createSubmitting() {
      return mocks.state.createSubmitting;
    },
    get rolesSubmitting() {
      return mocks.state.rolesSubmitting;
    },
    get disableSubmitting() {
      return mocks.state.disableSubmitting;
    },
    load: mocks.load,
    create: mocks.create,
    loadRoleScopes: mocks.loadRoleScopes,
    setRoles: mocks.setRoles,
    disable: mocks.disable,
    selectAccount: mocks.selectAccount,
    clearCache: mocks.clearCache,
  }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace, push: mocks.push }),
}));

import AdminAccountsView from '@/views/AdminAccountsView.vue';

const activeAccount = { account_id: '10', login_name: 'alice', enabled: true, state: 'active' };
const pendingAccount = {
  account_id: '11',
  login_name: 'bob',
  enabled: false,
  state: 'pending_activation',
};
const disabledAccount = {
  account_id: '12',
  login_name: 'carol',
  enabled: false,
  state: 'disabled',
};

function created(state: string) {
  return {
    account_id: '20',
    login_name: 'new-staff',
    staff_id: '1001',
    enabled: state === 'active',
    must_change_password: state === 'pending_activation',
    state,
  };
}

function createdOutcome(state: string) {
  return { executed: true, stale: false, data: created(state) };
}

function mutationOutcome() {
  return { executed: true, stale: false };
}

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(AdminAccountsView);
  await flushPromises();
  return wrapper;
}

async function selectAccountByIndex(wrapper: VueWrapper, index: number): Promise<void> {
  const buttons = wrapper.findAll('.manage-account');
  await buttons[index]?.trigger('click');
  await flushPromises();
}

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

function roleDetail(accountId: string, branchIds: string[] = ['1']) {
  return {
    account_id: accountId,
    state: 'active',
    version: 1,
    roles: [{ role_code: 'frontdesk', branch_ids: branchIds, approval_basis: '服务端依据' }],
  };
}

function roleDetailOutcome(detail: ReturnType<typeof roleDetail>) {
  if (mocks.state.roleDetail) mocks.state.roleDetail.value = detail;
  return {
    executed: true,
    stale: false,
    requestId: 1,
    accountId: detail.account_id,
    data: detail,
  };
}

describe('AdminAccountsView', () => {
  beforeEach(() => {
    mocks.state.accounts = [activeAccount, pendingAccount, disabledAccount];
    mocks.state.nextCursor = null;
    mocks.state.selectedAccountId = ref<string | null>(null);
    mocks.state.roleDetail = ref<Record<string, unknown> | null>(null);
    mocks.state.roleDetailLoading = false;
    mocks.state.cacheRevision = ref(0);
    mocks.state.listLoading = false;
    mocks.state.createSubmitting = false;
    mocks.state.rolesSubmitting = false;
    mocks.state.disableSubmitting = false;
    mocks.session.me = {
      account_id: '1',
      name: '管理员',
      identity_type: 'staff',
      roles: [{ role_code: 'sysadmin', branch_ids: [] }],
      must_change_password: false,
    };
    mocks.session.status = ref('ready');
    mocks.session.sessionRevision = 0;
    mocks.session.verificationMessage = null;
    mocks.fetchMe.mockReset().mockResolvedValue(true);
    mocks.load.mockReset().mockResolvedValue(undefined);
    mocks.create.mockReset();
    mocks.loadRoleScopes.mockReset().mockImplementation(async (accountId: string) => {
      if (accountId === '10') {
        mocks.state.roleDetail!.value = {
          account_id: '10',
          state: 'active',
          version: 1,
          roles: [{ role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '原批准依据' }],
        };
      } else {
        mocks.state.roleDetail!.value = {
          account_id: accountId,
          state: 'active',
          version: 1,
          roles: [],
        };
      }
      return {
        executed: true,
        stale: false,
        requestId: 1,
        accountId,
        data: mocks.state.roleDetail!.value,
      };
    });
    mocks.setRoles.mockReset().mockResolvedValue({
      executed: true,
      stale: false,
      data: { account_id: '10', version: 2 },
    });
    mocks.disable.mockReset().mockResolvedValue(mutationOutcome());
    mocks.clearCache.mockReset().mockImplementation(() => {
      mocks.state.accounts = [];
      mocks.state.nextCursor = null;
      if (mocks.state.selectedAccountId) mocks.state.selectedAccountId.value = null;
      if (mocks.state.roleDetail) mocks.state.roleDetail.value = null;
      if (mocks.state.cacheRevision) mocks.state.cacheRevision.value += 1;
    });
    mocks.selectAccount.mockReset().mockImplementation((id: string) => {
      if (mocks.state.selectedAccountId) mocks.state.selectedAccountId.value = id;
    });
    mocks.replace.mockReset();
    mocks.push.mockReset();
  });

  it('进入页面先重取身份与列表；待激活不显示为启用', async () => {
    const wrapper = await mountPage();

    expect(mocks.fetchMe).toHaveBeenCalledTimes(1);
    expect(mocks.load).toHaveBeenCalledWith(true);
    const rows = wrapper.findAll('.account-row');
    expect(rows).toHaveLength(3);
    expect(rows[1]?.text()).toContain('待激活');
    expect(rows[1]?.text()).not.toContain('启用');
    expect(rows[2]?.text()).toContain('停用');
  });

  it('创建待激活账号：规范化登录名、展示本地激活说明并刷新列表', async () => {
    mocks.create.mockResolvedValue(createdOutcome('pending_activation'));
    const wrapper = await mountPage();
    await wrapper.find('#create-staff-id').setValue(' 1001 ');
    await wrapper.find('#create-login-name').setValue(' New-Staff ');
    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();

    expect(mocks.create).toHaveBeenCalledTimes(1);
    const [input, key] = mocks.create.mock.calls[0] ?? [];
    expect(input).toEqual({ staff_id: '1001', login_name: 'new-staff' });
    expect(typeof key).toBe('string');
    expect((key as string).length).toBeGreaterThan(0);
    expect(wrapper.find('.create-notice').text()).toContain('待激活');
    expect(wrapper.find('.create-notice').text()).toContain('本地激活');
    expect(wrapper.find('.created-result').text()).toContain('待激活');
    expect(mocks.load).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['active', '启用'],
    ['disabled', '停用'],
  ])('创建重放结果按服务端状态展示：%s', async (state, label) => {
    mocks.create.mockResolvedValue(createdOutcome(state));
    const wrapper = await mountPage();
    await wrapper.find('#create-staff-id').setValue('1001');
    await wrapper.find('#create-login-name').setValue('new-staff');
    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();

    expect(wrapper.find('.create-notice').text()).toContain('请求已重放');
    expect(wrapper.find('.created-result').text()).toContain(label);
    expect(wrapper.find('.create-notice').text()).not.toContain('默认密码');
  });

  it('超时保留输入和同一幂等键，用户手动重试复用原键', async () => {
    mocks.create
      .mockRejectedValueOnce(new ApiError('TIMEOUT', 0, 'raw-secret'))
      .mockResolvedValueOnce(createdOutcome('pending_activation'));
    const wrapper = await mountPage();
    await wrapper.find('#create-staff-id').setValue('1001');
    await wrapper.find('#create-login-name').setValue('new-staff');

    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();
    expect(wrapper.find('.create-error').text()).toContain('不会自动重发');
    expect(wrapper.find('.create-error').text()).not.toContain('raw-secret');
    const firstKey = mocks.create.mock.calls[0]?.[1];

    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();
    const secondKey = mocks.create.mock.calls[1]?.[1];
    expect(secondKey).toBe(firstKey);
  });

  it('修改内容后才生成新的幂等键，409 保留输入', async () => {
    mocks.create
      .mockRejectedValueOnce(new ApiError('TIMEOUT', 0, 'raw-secret'))
      .mockRejectedValueOnce(new ApiError('ACCOUNT_EXISTS', 409, 'raw-secret'));
    const wrapper = await mountPage();
    await wrapper.find('#create-staff-id').setValue('1001');
    await wrapper.find('#create-login-name').setValue('first-name');
    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();
    const firstKey = mocks.create.mock.calls[0]?.[1];

    await wrapper.find('#create-login-name').setValue('second-name');
    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();
    const secondKey = mocks.create.mock.calls[1]?.[1];
    expect(secondKey).not.toBe(firstKey);
    expect(wrapper.find('.create-error').text()).toContain('已有账号');
    expect((wrapper.find('#create-login-name').element as HTMLInputElement).value).toBe('second-name');
  });

  it('创建提交中禁用按钮并阻止重复提交', async () => {
    mocks.state.createSubmitting = true;
    const wrapper = await mountPage();
    await wrapper.find('#create-staff-id').setValue('1001');
    await wrapper.find('#create-login-name').setValue('new-staff');

    const button = wrapper.find('.create-submit');
    expect((button.element as HTMLButtonElement).disabled).toBe(true);
    await wrapper.find('.form-grid').trigger('submit');
    await flushPromises();

    expect(mocks.create).not.toHaveBeenCalled();
  });

  it('选中账号后读取授权并回填，成功后才开放编辑', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);

    expect(mocks.loadRoleScopes).toHaveBeenCalledWith('10');
    const enabled = wrapper.find('#role-frontdesk-enabled');
    expect((enabled.element as HTMLInputElement).checked).toBe(true);
    expect((enabled.element as HTMLInputElement).disabled).toBe(false);
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1');
    expect((wrapper.find('#role-frontdesk-basis').element as HTMLInputElement).value).toBe('原批准依据');
  });

  it('编辑角色门店后展示差异，保存携带读取到的 expected_version', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#role-frontdesk-basis').setValue('修改批准');
    await wrapper.find('#roles-confirm').setValue(true);

    expect(wrapper.find('.role-diff').text()).toContain('1、2');
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    expect(mocks.setRoles).toHaveBeenCalledWith('10', 1, [
      { role_code: 'frontdesk', branch_ids: ['1', '2'], approval_basis: '修改批准' },
    ]);
    expect(wrapper.find('.roles-notice').text()).toContain('旧会话已撤销');
  });

  it('版本冲突 409 保留草稿并要求重新读取，不自动覆盖', async () => {
    mocks.setRoles.mockRejectedValue(new ApiError('VERSION_CONFLICT', 409, 'raw-secret'));
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('.version-conflict').text()).toContain('重新读取');
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect((wrapper.find('.roles-submit').element as HTMLButtonElement).disabled).toBe(true);
    expect(wrapper.find('.version-conflict').text()).not.toContain('raw-secret');
    const before = mocks.loadRoleScopes.mock.calls.length;
    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    expect(mocks.loadRoleScopes.mock.calls.length).toBe(before + 1);
    expect(wrapper.find('.version-conflict').exists()).toBe(false);
    expect((wrapper.find('#roles-confirm').element as HTMLInputElement).checked).toBe(false);
  });

  it('409 后重读失败再重读成功，草稿仍保留且需重新确认', async () => {
    mocks.setRoles.mockRejectedValue(new ApiError('VERSION_CONFLICT', 409, 'raw-secret'));
    const first = deferred<ReturnType<typeof roleDetailOutcome>>();
    const second = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      return calls === 2 ? first.promise : second.promise;
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();
    expect(wrapper.find('.version-conflict').exists()).toBe(true);

    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    first.reject(new ApiError('NETWORK_ERROR', 0, 'raw-secret'));
    await flushPromises();
    expect(wrapper.find('.role-load-error').exists()).toBe(true);
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');

    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    second.resolve(roleDetailOutcome(roleDetail('10', ['2'])));
    await flushPromises();

    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect(wrapper.find('.version-conflict').exists()).toBe(false);
    expect((wrapper.find('#roles-confirm').element as HTMLInputElement).checked).toBe(false);
    expect(wrapper.find('.roles-submit').attributes('disabled')).toBeDefined();
  });

  it('超时核对连续失败时保留草稿，不把失败当成功', async () => {
    mocks.setRoles.mockRejectedValue(new ApiError('TIMEOUT', 0, 'raw-secret'));
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      throw new ApiError('NETWORK_ERROR', 0, 'raw-secret');
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect(wrapper.find('.role-load-error').exists()).toBe(true);
    expect(wrapper.find('.roles-notice').exists()).toBe(false);

    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect(wrapper.find('.role-load-error').exists()).toBe(true);
    expect(wrapper.find('.roles-notice').exists()).toBe(false);
  });

  it('A→B→A 的旧详情晚到不回填当前编辑', async () => {
    const stale = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async () => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail('10', ['1']));
      if (calls === 2) return stale.promise;
      if (calls === 3) return roleDetailOutcome(roleDetail('11', []));
      return roleDetailOutcome(roleDetail('10', ['1']));
    });
    const wrapper = await mountPage();
    const buttons = wrapper.findAll('.manage-account');

    await buttons[0]?.trigger('click');
    await flushPromises();
    await buttons[0]?.trigger('click');
    await flushPromises();
    await buttons[1]?.trigger('click');
    await flushPromises();
    await buttons[0]?.trigger('click');
    await flushPromises();

    stale.resolve(roleDetailOutcome(roleDetail('10', ['9'])));
    await flushPromises();
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1');
  });

  it('同目标重选不清草稿，成功后仍按草稿比较', async () => {
    const pending = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      return pending.promise;
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');

    await selectAccountByIndex(wrapper, 0);
    pending.resolve(roleDetailOutcome(roleDetail('10', ['2'])));
    await flushPromises();

    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect((wrapper.find('#roles-confirm').element as HTMLInputElement).checked).toBe(false);
  });

  it('同目标并行重读时，旧完成不回填也不清掉新编辑确认', async () => {
    mocks.setRoles.mockRejectedValue(new ApiError('VERSION_CONFLICT', 409, 'raw-secret'));
    const first = deferred<ReturnType<typeof roleDetailOutcome>>();
    const second = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      return calls === 2 ? first.promise : second.promise;
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    await wrapper.find('.reload-roles').trigger('click');
    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    second.resolve(roleDetailOutcome(roleDetail('10', ['2'])));
    await flushPromises();
    first.resolve(roleDetailOutcome(roleDetail('10', ['9'])));
    await flushPromises();

    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect(wrapper.find('.version-conflict').exists()).toBe(false);
    expect((wrapper.find('#roles-confirm').element as HTMLInputElement).checked).toBe(false);
  });

  it('保存成功后重读期间切换目标，不显示旧成功提示', async () => {
    const postSave = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      if (calls === 2) return postSave.promise;
      return roleDetailOutcome(roleDetail(accountId, []));
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();
    expect(calls).toBe(2);

    await selectAccountByIndex(wrapper, 1);
    postSave.resolve(roleDetailOutcome(roleDetail('10', ['2'])));
    await flushPromises();

    expect(wrapper.text()).not.toContain('已保存并重新读取当前授权');
    expect(wrapper.find('.roles-notice').exists()).toBe(false);
  });

  it('保存成功后重读期间切换会话，不显示旧成功提示', async () => {
    const postSave = deferred<ReturnType<typeof roleDetailOutcome>>();
    let calls = 0;
    mocks.loadRoleScopes.mockImplementation(async (accountId: string) => {
      mocks.state.roleDetail!.value = null;
      calls += 1;
      if (calls === 1) return roleDetailOutcome(roleDetail(accountId, ['1']));
      return postSave.promise;
    });
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();
    expect(calls).toBe(2);

    mocks.session.sessionRevision += 1;
    postSave.resolve(roleDetailOutcome(roleDetail('10', ['2'])));
    await flushPromises();

    expect(wrapper.text()).not.toContain('已保存并重新读取当前授权');
    expect(wrapper.find('.roles-notice').exists()).toBe(false);
  });

  it('保存超时先 GET 核对，不自动重发旧全量覆盖', async () => {
    mocks.setRoles.mockRejectedValue(new ApiError('TIMEOUT', 0, 'raw-secret'));
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    const loadsBefore = mocks.loadRoleScopes.mock.calls.length;
    await wrapper.find('#role-frontdesk-branches').setValue('1, 2');
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    expect(mocks.setRoles).toHaveBeenCalledTimes(1);
    expect(mocks.loadRoleScopes.mock.calls.length).toBe(loadsBefore + 1);
    expect(wrapper.find('.roles-error').text()).toContain('已重新读取当前授权');
    expect((wrapper.find('#role-frontdesk-branches').element as HTMLInputElement).value).toBe('1, 2');
    expect(wrapper.find('.roles-error').text()).not.toContain('raw-secret');
  });

  it('撤销全部角色需要单独确认', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('#role-frontdesk-enabled').setValue(false);

    expect(wrapper.find('#revoke-all-confirm').exists()).toBe(true);
    expect((wrapper.find('.roles-submit').element as HTMLButtonElement).disabled).toBe(true);
    await wrapper.find('#revoke-all-confirm').setValue(true);
    await wrapper.find('#roles-confirm').setValue(true);
    await wrapper.find('.role-form').trigger('submit');
    await flushPromises();

    expect(mocks.setRoles).toHaveBeenCalledWith('10', 1, []);
  });

  it('授权读取失败时禁用编辑，并可从错误状态重试读取', async () => {
    mocks.loadRoleScopes.mockRejectedValueOnce(new ApiError('NETWORK_ERROR', 0, 'raw-secret'));
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);

    expect(wrapper.find('.role-load-error').text()).toContain('未收到明确结果');
    expect(wrapper.find('.role-load-error').text()).not.toContain('raw-secret');
    expect((wrapper.find('#role-frontdesk-enabled').element as HTMLInputElement).disabled).toBe(true);
    expect((wrapper.find('.roles-submit').element as HTMLButtonElement).disabled).toBe(true);

    await wrapper.find('.reload-roles').trigger('click');
    await flushPromises();
    expect(wrapper.find('.role-load-error').exists()).toBe(false);
    expect((wrapper.find('#role-frontdesk-enabled').element as HTMLInputElement).checked).toBe(true);
  });

  it('选中本人账号时提示不能修改自己的角色，服务端仍是最终拒绝方', async () => {
    mocks.state.accounts = [
      { account_id: '1', login_name: 'self-admin', enabled: true, state: 'active' },
    ];
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);

    expect(wrapper.text()).toContain('不能修改自己的角色');
    expect((wrapper.find('#role-frontdesk-enabled').element as HTMLInputElement).disabled).toBe(false);
  });

  it('停用必须先确认，取消不提交', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('离职交接完成');
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();
    expect(mocks.disable).not.toHaveBeenCalled();
    expect(wrapper.find('.disable-error').text()).toContain('请勾选确认');

    await wrapper.find('.disable-cancel').trigger('click');
    expect(wrapper.find('.disable-panel').exists()).toBe(false);
    expect(mocks.disable).not.toHaveBeenCalled();
  });

  it('确认停用后调用接口、提示成功并刷新列表', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('离职交接完成');
    await wrapper.find('#disable-confirm').setValue(true);
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();

    expect(mocks.disable).toHaveBeenCalledWith('10', '离职交接完成');
    expect(wrapper.find('.notice').text()).toContain('已停用账号 alice');
    expect(wrapper.find('.disable-panel').exists()).toBe(false);
    expect(mocks.load).toHaveBeenCalledTimes(2);
  });

  it('停用等待期间切换账号：只提示原目标，不关闭新选择的面板', async () => {
    let resolveDisable!: (value: unknown) => void;
    mocks.disable.mockReturnValue(
      new Promise((resolve) => {
        resolveDisable = resolve;
      }),
    );
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('离职交接完成');
    await wrapper.find('#disable-confirm').setValue(true);
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();

    await selectAccountByIndex(wrapper, 2);
    expect(mocks.state.selectedAccountId?.value).toBe('12');

    resolveDisable(mutationOutcome());
    await flushPromises();

    expect(wrapper.find('.notice').text()).toContain('账号 alice 的停用请求已返回');
    expect(wrapper.find('.notice').text()).toContain('当前已切换账号');
    expect(mocks.state.selectedAccountId?.value).toBe('12');
  });

  it('停用等待期间关闭并重新打开面板：旧结果不清空新草稿', async () => {
    let resolveDisable!: (value: unknown) => void;
    mocks.disable.mockReturnValue(
      new Promise((resolve) => {
        resolveDisable = resolve;
      }),
    );
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('第一次原因');
    await wrapper.find('#disable-confirm').setValue(true);
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();

    await wrapper.find('.disable-cancel').trigger('click');
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('第二次原因');
    await wrapper.find('#disable-confirm').setValue(true);

    resolveDisable(mutationOutcome());
    await flushPromises();

    expect(wrapper.find('.disable-panel').exists()).toBe(true);
    expect((wrapper.find('#disable-reason').element as HTMLInputElement).value).toBe('第二次原因');
    expect(wrapper.find('.notice').text()).toContain('已关闭停用面板');
  });

  it('停用服务端返回待激活错误时明确提示且不隐藏失败', async () => {
    mocks.disable.mockRejectedValue(new ApiError('NOT_ACTIVATED', 422, 'raw-secret'));
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('离职交接完成');
    await wrapper.find('#disable-confirm').setValue(true);
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();

    expect(wrapper.find('.disable-error').text()).toContain('待激活账号不支持停用');
    expect(wrapper.find('.disable-error').text()).not.toContain('raw-secret');
    expect(wrapper.find('.disable-panel').exists()).toBe(true);
  });

  it('停用 403 清空账号缓存、显示无权限但不登出', async () => {
    mocks.disable.mockRejectedValue(new ApiError('FORBIDDEN', 403, 'raw-secret'));
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 0);
    await wrapper.find('.disable-open').trigger('click');
    await wrapper.find('#disable-reason').setValue('离职交接完成');
    await wrapper.find('#disable-confirm').setValue(true);
    await wrapper.find('.disable-submit').trigger('click');
    await flushPromises();

    expect(mocks.clearCache).toHaveBeenCalled();
    expect(wrapper.find('.page-error').text()).toContain('无权限停用该账号');
    expect(wrapper.find('.page-error').text()).not.toContain('raw-secret');
    expect(mocks.session.status?.value).toBe('ready');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('待激活账号明确不支持停用，不显示停用入口', async () => {
    const wrapper = await mountPage();
    await selectAccountByIndex(wrapper, 1);

    expect(wrapper.find('.pending-disable-note').text()).toContain('不支持停用');
    expect(wrapper.find('.disable-open').exists()).toBe(false);
  });

  it('列表 403 明确提示、清空账号缓存且不清空会话', async () => {
    mocks.load.mockRejectedValue(new ApiError('FORBIDDEN', 403, 'raw-secret'));
    const wrapper = await mountPage();

    expect(wrapper.find('.page-error').text()).toContain('无权限访问账号管理');
    expect(wrapper.find('.page-error').text()).not.toContain('raw-secret');
    expect(mocks.clearCache).toHaveBeenCalledTimes(1);
    expect(mocks.session.status?.value).toBe('ready');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('列表 401 触发受代次保护的 fetchMe，身份失效才回登录', async () => {
    let fetchCount = 0;
    mocks.fetchMe.mockImplementation(async () => {
      fetchCount += 1;
      if (fetchCount === 1) return true;
      if (mocks.session.status) mocks.session.status.value = 'guest';
      return false;
    });
    mocks.load.mockRejectedValue(new ApiError('UNAUTHENTICATED', 401, 'raw-secret'));
    await mountPage();

    expect(mocks.fetchMe).toHaveBeenCalledTimes(2);
    expect(mocks.replace).toHaveBeenCalledWith({ name: 'login' });
  });

  it('身份核验网络失败显示未确认与重试，隐藏缓存账号并禁用操作', async () => {
    mocks.session.status = ref('unverified');
    mocks.session.verificationMessage = '身份确认未完成，请检查网络后重试。';
    mocks.fetchMe.mockResolvedValue(false);
    const wrapper = await mountPage();

    expect(wrapper.find('.identity-panel').exists()).toBe(true);
    expect(wrapper.find('.identity-panel').text()).toContain('身份确认未完成');
    expect(wrapper.find('.identity-panel').text()).not.toContain('登录已失效');
    expect(wrapper.find('.account-table').exists()).toBe(false);
    expect(wrapper.find('#create-staff-id').exists()).toBe(false);
    expect(wrapper.find('.retry-identity').exists()).toBe(true);
    expect(mocks.load).not.toHaveBeenCalled();
  });

  it('身份核验重试成功后重新加载列表', async () => {
    mocks.session.status = ref('unverified');
    mocks.session.verificationMessage = '身份确认未完成，请检查网络后重试。';
    mocks.fetchMe.mockResolvedValueOnce(false);
    const wrapper = await mountPage();
    expect(wrapper.find('.identity-panel').exists()).toBe(true);

    if (mocks.session.status) mocks.session.status.value = 'ready';
    mocks.fetchMe.mockResolvedValueOnce(true);
    await wrapper.find('.retry-identity').trigger('click');
    await flushPromises();
    await nextTick();

    expect(wrapper.find('.identity-panel').exists()).toBe(false);
    expect(mocks.load).toHaveBeenCalledWith(true);
  });

  it('刷新重新取身份和列表，加载更多使用 next_cursor', async () => {
    mocks.state.nextCursor = '12';
    const wrapper = await mountPage();
    expect(mocks.fetchMe).toHaveBeenCalledTimes(1);
    expect(mocks.load).toHaveBeenCalledWith(true);

    await wrapper.find('.refresh-accounts').trigger('click');
    await flushPromises();
    expect(mocks.fetchMe).toHaveBeenCalledTimes(2);
    expect(mocks.load).toHaveBeenCalledTimes(2);

    await wrapper.find('.load-more').trigger('click');
    await flushPromises();
    expect(mocks.load).toHaveBeenCalledWith(false);
  });
});
