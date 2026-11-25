// 管理端员工账号 API：严格按 OpenAPI / AccountAdminController 的真实字段封装。
// 不读取 Cookie、不发 Authorization: Bearer；CSRF 由调用方注入的 HttpClient 提供器从会话 store 读取。
import { ApiError, type HttpClient, type PageResult } from './http';

/** 员工可授予的角色；patient 不可授予员工，故不在列表。 */
export const STAFF_ROLE_CODES = ['sysadmin', 'owner', 'dentist', 'frontdesk', 'inventory', 'finance'] as const;
export type StaffRoleCode = (typeof STAFF_ROLE_CODES)[number];

export type AccountState = 'pending_activation' | 'active' | 'disabled';

/** GET /staff/accounts 的列表项（不返回角色明细）。 */
export interface StaffAccount {
  account_id: string;
  login_name: string;
  enabled: boolean;
  state: AccountState;
}

/** POST /staff/accounts 的返回；比列表项多 staff_id/must_change_password。 */
export interface CreatedStaffAccount extends StaffAccount {
  staff_id: string;
  must_change_password: boolean;
}

/** PUT /staff/accounts/{id}/role-scopes 的逐角色授权。 */
export interface RoleScopeGrant {
  role_code: StaffRoleCode;
  branch_ids: string[];
  approval_basis: string;
}

/** GET /staff/accounts/{id}/role-scopes 的详情；version 是并发令牌，不是登录凭证。 */
export interface RoleScopeDetail {
  account_id: string;
  state: AccountState;
  version: number;
  roles: RoleScopeGrant[];
}

/** PUT role-scopes 成功后的权威版本。 */
export interface RoleScopeUpdateResult {
  account_id: string;
  version: number;
}

export interface CreateAccountInput {
  staff_id: string;
  login_name: string;
}

export type AccountAdminAction = 'list' | 'create' | 'roles' | 'disable';

export interface AccountAdminApi {
  list(cursor?: string, limit?: number): Promise<PageResult<StaffAccount>>;
  create(input: CreateAccountInput, idempotencyKey: string): Promise<CreatedStaffAccount>;
  getRoleScopes(accountId: string): Promise<RoleScopeDetail>;
  setRoleScopes(
    accountId: string,
    expectedVersion: number,
    roles: readonly RoleScopeGrant[],
  ): Promise<RoleScopeUpdateResult>;
  disable(accountId: string, reason: string): Promise<void>;
}

export const STAFF_LOGIN_NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{2,99}$/;
export const MAX_ROLE_GRANTS = 7;
export const MAX_BRANCH_IDS = 100;

export function normalizeStaffLoginName(value: string): string {
  return value.trim().toLowerCase();
}

export function isPositiveIdText(value: string): boolean {
  return /^[1-9]\d*$/.test(value.trim());
}

export function isValidStaffLoginName(value: string): boolean {
  return STAFF_LOGIN_NAME_PATTERN.test(normalizeStaffLoginName(value));
}

/** 生成一次逻辑创建的稳定幂等键；页面只在内容变化时换键。 */
export function newIdempotencyKey(): string {
  const cryptoObject = globalThis.crypto;
  if (cryptoObject && typeof cryptoObject.randomUUID === 'function') {
    return cryptoObject.randomUUID();
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`;
}

function invalidResponse(): ApiError {
  return new ApiError('INVALID_RESPONSE', 0, '服务器返回了无法解析的响应。');
}

function validationError(message: string): ApiError {
  return new ApiError('VALIDATION_FAILED', 0, message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseState(value: unknown): AccountState | null {
  return value === 'pending_activation' || value === 'active' || value === 'disabled' ? value : null;
}

function parseAccount(value: unknown): StaffAccount {
  if (!isRecord(value)) throw invalidResponse();
  const accountId = value['account_id'];
  const loginName = value['login_name'];
  const enabled = value['enabled'];
  const state = parseState(value['state']);
  if (typeof accountId !== 'string' || accountId === '') throw invalidResponse();
  if (typeof loginName !== 'string') throw invalidResponse();
  if (typeof enabled !== 'boolean') throw invalidResponse();
  if (state === null) throw invalidResponse();
  return { account_id: accountId, login_name: loginName, enabled, state };
}

function parseCreatedAccount(value: unknown): CreatedStaffAccount {
  const account = parseAccount(value);
  if (!isRecord(value)) throw invalidResponse();
  const staffId = value['staff_id'];
  const mustChange = value['must_change_password'];
  if (typeof staffId !== 'string' || staffId === '') throw invalidResponse();
  if (typeof mustChange !== 'boolean') throw invalidResponse();
  return { ...account, staff_id: staffId, must_change_password: mustChange };
}

function isStaffRoleCode(value: string): value is StaffRoleCode {
  return (STAFF_ROLE_CODES as readonly string[]).includes(value);
}

function parseRoleGrant(value: unknown): RoleScopeGrant {
  if (!isRecord(value)) throw invalidResponse();
  const roleCode = value['role_code'];
  const branchIds = value['branch_ids'];
  const basis = value['approval_basis'];
  if (typeof roleCode !== 'string' || !isStaffRoleCode(roleCode)) throw invalidResponse();
  if (!Array.isArray(branchIds) || branchIds.some((id) => typeof id !== 'string')) throw invalidResponse();
  if (typeof basis !== 'string') throw invalidResponse();
  return { role_code: roleCode, branch_ids: [...(branchIds as string[])], approval_basis: basis };
}

function parseRoleScopeDetail(value: unknown): RoleScopeDetail {
  if (!isRecord(value)) throw invalidResponse();
  const accountId = value['account_id'];
  const state = parseState(value['state']);
  const version = value['version'];
  const roles = value['roles'];
  if (typeof accountId !== 'string' || accountId === '') throw invalidResponse();
  if (state === null) throw invalidResponse();
  if (typeof version !== 'number' || !Number.isInteger(version) || version < 1) throw invalidResponse();
  if (!Array.isArray(roles)) throw invalidResponse();
  return {
    account_id: accountId,
    state,
    version,
    roles: roles.map(parseRoleGrant),
  };
}

function parseRoleUpdateResult(value: unknown): RoleScopeUpdateResult {
  if (!isRecord(value)) throw invalidResponse();
  const accountId = value['account_id'];
  const version = value['version'];
  if (typeof accountId !== 'string' || accountId === '') throw invalidResponse();
  if (typeof version !== 'number' || !Number.isInteger(version) || version < 1) throw invalidResponse();
  return { account_id: accountId, version };
}

/** 逐角色序列化：每个 role 保留自己的 branch_ids，绝不合并成一组。 */
export function serializeRoleGrants(roles: readonly RoleScopeGrant[]): RoleScopeGrant[] {
  if (roles.length > MAX_ROLE_GRANTS) throw validationError('角色数量超过上限。');
  const seen = new Set<string>();
  return roles.map((role) => {
    if (!isStaffRoleCode(role.role_code)) throw validationError('存在不支持的角色代码。');
    if (seen.has(role.role_code)) throw validationError('同一角色不能重复提交。');
    seen.add(role.role_code);

    if (!Array.isArray(role.branch_ids) || role.branch_ids.length > MAX_BRANCH_IDS) {
      throw validationError('门店范围数量不符合要求。');
    }
    const branchIds: string[] = [];
    const branchSeen = new Set<string>();
    for (const raw of role.branch_ids) {
      const branchId = raw.trim();
      if (!isPositiveIdText(branchId) || branchSeen.has(branchId)) {
        throw validationError('门店编号必须是不重复的正整数编号。');
      }
      branchSeen.add(branchId);
      branchIds.push(branchId);
    }
    if (role.role_code === 'sysadmin' && branchIds.length > 0) {
      throw validationError('系统管理员不能绑定门店范围。');
    }
    const basis = role.approval_basis.trim();
    if (!basis || basis.length > 300) throw validationError('批准依据必须为 1-300 个字符。');
    return { role_code: role.role_code, branch_ids: branchIds, approval_basis: basis };
  });
}

export function createAccountAdminApi(client: HttpClient): AccountAdminApi {
  return {
    async list(cursor?: string, limit?: number): Promise<PageResult<StaffAccount>> {
      const page = await client.list<unknown>('/staff/accounts', {
        query: { cursor, limit },
      });
      return {
        items: page.items.map(parseAccount),
        nextCursor: page.nextCursor,
      };
    },

    async create(input: CreateAccountInput, idempotencyKey: string): Promise<CreatedStaffAccount> {
      const staffId = input.staff_id.trim();
      if (!isPositiveIdText(staffId)) throw validationError('员工编号必须为正整数。');
      const loginName = normalizeStaffLoginName(input.login_name);
      if (!isValidStaffLoginName(loginName)) throw validationError('登录名格式不符合要求。');
      const key = idempotencyKey.trim();
      if (!key) throw validationError('缺少幂等键。');
      const result = await client.post<unknown>(
        '/staff/accounts',
        { staff_id: staffId, login_name: loginName },
        { idempotencyKey: key },
      );
      return parseCreatedAccount(result.data);
    },

    async getRoleScopes(accountId: string): Promise<RoleScopeDetail> {
      if (!isPositiveIdText(accountId)) throw validationError('账号编号不符合要求。');
      const result = await client.get<unknown>(
        `/staff/accounts/${encodeURIComponent(accountId)}/role-scopes`,
      );
      return parseRoleScopeDetail(result.data);
    },

    async setRoleScopes(
      accountId: string,
      expectedVersion: number,
      roles: readonly RoleScopeGrant[],
    ): Promise<RoleScopeUpdateResult> {
      if (!isPositiveIdText(accountId)) throw validationError('账号编号不符合要求。');
      if (!Number.isInteger(expectedVersion) || expectedVersion < 1) {
        throw validationError('缺少有效的授权版本。');
      }
      const serialized = serializeRoleGrants(roles);
      const result = await client.put<unknown>(
        `/staff/accounts/${encodeURIComponent(accountId)}/role-scopes`,
        { expected_version: expectedVersion, roles: serialized },
      );
      return parseRoleUpdateResult(result.data);
    },

    async disable(accountId: string, reason: string): Promise<void> {
      if (!isPositiveIdText(accountId)) throw validationError('账号编号不符合要求。');
      const normalizedReason = reason.trim();
      if (!normalizedReason || normalizedReason.length > 300) {
        throw validationError('停用原因必须为 1-300 个字符。');
      }
      await client.post<unknown>(
        `/staff/accounts/${encodeURIComponent(accountId)}/disable`,
        { reason: normalizedReason },
      );
    },
  };
}

/** 只输出安全中文文案；不回显服务端原始 message、请求体或账号敏感信息。 */
export function accountAdminErrorMessage(error: unknown, action: AccountAdminAction): string {
  if (!(error instanceof ApiError)) return '操作失败，请稍后重试。';

  if (error.code === 'NOT_ACTIVATED') return '待激活账号不支持停用；请按现有本地激活流程处理。';
  if (error.code === 'ACCOUNT_EXISTS') return '该员工或登录名已有账号；请核对后修改内容再提交。';
  if (error.code === 'IDEMPOTENCY_CONFLICT') return '同一幂等键的请求内容已变化；请核对并修改内容后再提交。';
  if (error.code === 'VERSION_CONFLICT') return '授权已被其他操作更新，请重新读取并比较后再提交。';
  if (error.code === 'FORBIDDEN') {
    if (action === 'roles') return '无权限设置角色，或不能修改自己的角色/移除最后一个有效管理员。';
    if (action === 'disable') return '无权限停用该账号，或不能停用最后一个有效管理员。';
    if (action === 'create') return '无权限创建员工账号。';
    return '无权限访问账号管理。';
  }
  if (error.status === 401 || error.code === 'UNAUTHENTICATED') return '登录已失效，请重新登录。';
  if (error.status === 404 || error.code === 'NOT_FOUND') return '账号或员工不存在，或不可访问。';
  if (error.status === 409) return '数据冲突，请刷新后重试。';
  if (error.code === 'TIMEOUT' || error.code === 'NETWORK_ERROR') {
    return '未收到明确结果；请保持内容不变后重试，系统不会自动重发。';
  }
  if (error.status === 422 || error.code === 'VALIDATION_FAILED' || error.code === 'INVALID_REQUEST') {
    if (action === 'disable') return '停用原因或账号状态不符合要求；请检查后重试。';
    if (action === 'roles') return '角色门店内容不符合要求；请检查门店编号、批准依据及系统管理员空范围。';
    if (action === 'create') return '员工编号或登录名不符合要求；请检查后重试。';
    return '请求内容不符合要求；请检查后重试。';
  }
  if (error.status === 429 || error.code === 'RATE_LIMITED') return '操作过于频繁，请稍后重试。';
  return '操作失败，请稍后重试。';
}
