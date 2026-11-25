import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type HttpClient } from '@/services/http';
import {
  accountAdminErrorMessage,
  createAccountAdminApi,
  newIdempotencyKey,
  serializeRoleGrants,
  type RoleScopeGrant,
} from '@/services/account-admin';

function fakeClient() {
  return {
    request: vi.fn(),
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    list: vi.fn(),
  };
}

function asClient(fake: ReturnType<typeof fakeClient>): HttpClient {
  return fake as unknown as HttpClient;
}

describe('员工账号 API 服务', () => {
  let fake: ReturnType<typeof fakeClient>;
  let api: ReturnType<typeof createAccountAdminApi>;

  beforeEach(() => {
    fake = fakeClient();
    api = createAccountAdminApi(asClient(fake));
  });

  it('列表按 cursor/limit 调用顶层分页接口并严格解析字段', async () => {
    fake.list.mockResolvedValue({
      items: [{ account_id: '10', login_name: 'alice', enabled: true, state: 'active' }],
      nextCursor: '10',
    });

    const page = await api.list('9', 50);

    expect(fake.list).toHaveBeenCalledWith('/staff/accounts', { query: { cursor: '9', limit: 50 } });
    expect(page).toEqual({
      items: [{ account_id: '10', login_name: 'alice', enabled: true, state: 'active' }],
      nextCursor: '10',
    });
  });

  it('列表字段缺失或状态非法时拒绝解析，不把 pending 当 active', async () => {
    fake.list.mockResolvedValue({
      items: [{ account_id: '10', login_name: 'alice', enabled: false, state: 'unknown' }],
      nextCursor: null,
    });

    await expect(api.list()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it.each(['pending_activation', 'active', 'disabled'])('创建响应解析三种状态：%s', async (state) => {
    fake.post.mockResolvedValue({
      data: {
        account_id: '10',
        login_name: 'alice',
        staff_id: '1001',
        enabled: state === 'active',
        must_change_password: state === 'pending_activation',
        state,
      },
    });

    const created = await api.create({ staff_id: ' 1001 ', login_name: ' Alice ' }, 'idem-1');

    expect(fake.post).toHaveBeenCalledWith(
      '/staff/accounts',
      { staff_id: '1001', login_name: 'alice' },
      { idempotencyKey: 'idem-1' },
    );
    expect(created.state).toBe(state);
  });

  it('创建前校验员工编号、登录名和幂等键，非法时不发请求', async () => {
    await expect(api.create({ staff_id: 'abc', login_name: 'alice' }, 'idem-1')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    });
    await expect(api.create({ staff_id: '1001', login_name: 'A!' }, 'idem-1')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    });
    await expect(api.create({ staff_id: '1001', login_name: 'alice' }, '  ')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    });
    expect(fake.post).not.toHaveBeenCalled();
  });

  it('读取角色详情：严格解析 version/state/按 role 分组的角色', async () => {
    fake.get.mockResolvedValue({
      data: {
        account_id: '10',
        state: 'active',
        version: 3,
        roles: [
          { role_code: 'frontdesk', branch_ids: ['1', '2'], approval_basis: '前台批准' },
          { role_code: 'owner', branch_ids: ['2'], approval_basis: '负责人批准' },
        ],
      },
    });

    const detail = await api.getRoleScopes('10');

    expect(fake.get).toHaveBeenCalledWith('/staff/accounts/10/role-scopes');
    expect(detail.version).toBe(3);
    expect(detail.roles).toHaveLength(2);
    expect(detail.roles[0]).toEqual({
      role_code: 'frontdesk',
      branch_ids: ['1', '2'],
      approval_basis: '前台批准',
    });
  });

  it('角色详情缺版本/非法角色/敏感外字段时拒绝解析', async () => {
    fake.get.mockResolvedValue({
      data: {
        account_id: '10',
        state: 'active',
        roles: [{ role_code: 'patient', branch_ids: [], approval_basis: 'x' }],
      },
    });
    await expect(api.getRoleScopes('10')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('角色按 role 分别序列化，不合并不同角色的 branch_ids，并携带 expected_version', async () => {
    fake.put.mockResolvedValue({ data: { account_id: '10', version: 2 } });
    const roles: RoleScopeGrant[] = [
      { role_code: 'frontdesk', branch_ids: ['1', '2'], approval_basis: ' 负责人批准A ' },
      { role_code: 'owner', branch_ids: ['2', '3'], approval_basis: '负责人批准B' },
    ];

    const updated = await api.setRoleScopes('10', 1, roles);

    expect(fake.put).toHaveBeenCalledWith('/staff/accounts/10/role-scopes', {
      expected_version: 1,
      roles: [
        { role_code: 'frontdesk', branch_ids: ['1', '2'], approval_basis: '负责人批准A' },
        { role_code: 'owner', branch_ids: ['2', '3'], approval_basis: '负责人批准B' },
      ],
    });
    expect(fake.put.mock.calls[0]?.[2]).toBeUndefined();
    expect(updated.version).toBe(2);
  });

  it('sysadmin 必须空范围，且不发送其他角色门店的并集', async () => {
    fake.put.mockResolvedValue({ data: { account_id: '10', version: 2 } });
    await api.setRoleScopes('10', 1, [
      { role_code: 'sysadmin', branch_ids: [], approval_basis: '负责人批准' },
      { role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '前台批准' },
    ]);

    const body = fake.put.mock.calls[0]?.[1] as { expected_version: number; roles: RoleScopeGrant[] };
    expect(body.expected_version).toBe(1);
    expect(body.roles).toEqual([
      { role_code: 'sysadmin', branch_ids: [], approval_basis: '负责人批准' },
      { role_code: 'frontdesk', branch_ids: ['1'], approval_basis: '前台批准' },
    ]);
  });

  it('空角色列表是合法全量撤权', async () => {
    fake.put.mockResolvedValue({ data: { account_id: '10', version: 2 } });
    expect(serializeRoleGrants([])).toEqual([]);
    await api.setRoleScopes('10', 1, []);
    expect(fake.put).toHaveBeenCalledWith('/staff/accounts/10/role-scopes', {
      expected_version: 1,
      roles: [],
    });
  });

  it('缺少有效 expected_version 时不发请求', async () => {
    await expect(api.setRoleScopes('10', 0, [])).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(api.setRoleScopes('10', 1.5, [])).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    expect(fake.put).not.toHaveBeenCalled();
  });

  it('拒绝 sysadmin 非空范围、重复角色、非法门店和空批准依据', async () => {
    expect(() =>
      serializeRoleGrants([{ role_code: 'sysadmin', branch_ids: ['1'], approval_basis: '批准' }]),
    ).toThrow();
    expect(() =>
      serializeRoleGrants([
        { role_code: 'owner', branch_ids: [], approval_basis: '批准' },
        { role_code: 'owner', branch_ids: [], approval_basis: '批准' },
      ]),
    ).toThrow();
    expect(() =>
      serializeRoleGrants([{ role_code: 'owner', branch_ids: ['x'], approval_basis: '批准' }]),
    ).toThrow();
    expect(() =>
      serializeRoleGrants([{ role_code: 'owner', branch_ids: [], approval_basis: ' ' }]),
    ).toThrow();
    expect(fake.put).not.toHaveBeenCalled();
  });

  it('停用发送原因，不使用创建幂等键', async () => {
    fake.post.mockResolvedValue({ data: undefined });

    await api.disable('10', ' 离职交接完成 ');

    expect(fake.post).toHaveBeenCalledWith('/staff/accounts/10/disable', { reason: '离职交接完成' });
    expect(fake.post.mock.calls[0]?.[2]).toBeUndefined();
  });

  it('幂等键非空且每次生成不同值', () => {
    const first = newIdempotencyKey();
    const second = newIdempotencyKey();
    expect(first.length).toBeGreaterThan(0);
    expect(second.length).toBeGreaterThan(0);
    expect(first).not.toBe(second);
  });

  it('错误文案只输出安全中文，不回显服务端原文', () => {
    const secret = 'raw-secret-should-not-leak';
    const error = new ApiError('NOT_ACTIVATED', 422, secret);
    expect(accountAdminErrorMessage(error, 'disable')).toContain('待激活账号不支持停用');
    expect(accountAdminErrorMessage(error, 'disable')).not.toContain(secret);

    const forbidden = new ApiError('FORBIDDEN', 403, secret);
    expect(accountAdminErrorMessage(forbidden, 'roles')).toContain('无权限设置角色');
    expect(accountAdminErrorMessage(forbidden, 'roles')).not.toContain(secret);

    const conflict = new ApiError('VERSION_CONFLICT', 409, secret);
    expect(accountAdminErrorMessage(conflict, 'roles')).toContain('重新读取');
    expect(accountAdminErrorMessage(conflict, 'roles')).not.toContain(secret);

    const timeout = new ApiError('TIMEOUT', 0, secret);
    expect(accountAdminErrorMessage(timeout, 'create')).toContain('不会自动重发');
    expect(accountAdminErrorMessage(timeout, 'create')).not.toContain(secret);
  });
});
