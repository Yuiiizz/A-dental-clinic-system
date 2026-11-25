// 管理端会话相关类型与常量，严格对齐后端 /auth/* 真实响应字段，不额外臆造字段。
//
// 字段来源（勿改字段名，否则与后端契约失配）：
//  - POST /auth/staff-login  → { data: { must_change_password, csrf_token } }
//  - GET  /auth/me           → { data: { account_id, name, identity_type, roles, must_change_password } }
//  - POST /auth/password-change → { data: { must_change_password, csrf_token } }
//  - POST /auth/logout       → 204 无正文

/** 角色门店范围（后端 authorization.me 中 roles[i] 的结构）。 */
export interface RoleScope {
  role_code: string;
  branch_ids: string[];
}

/** GET /auth/me 返回的当前身份。 */
export interface Me {
  account_id: string;
  name: string;
  identity_type: 'staff' | 'patient';
  roles: RoleScope[];
  must_change_password: boolean;
}

/** 登录 / 改密响应的 data。 */
export interface AuthSessionResult {
  must_change_password: boolean;
  csrf_token: string;
}

/** 登录 / 改密同构响应。 */
export type LoginResult = AuthSessionResult;
export type PasswordChangeResult = AuthSessionResult;

/** 仅保存 CSRF（刷新恢复用），绝不保存密码、会话令牌或 Cookie。 */
export const SESSION_CSRF_STORAGE_KEY = 'a-clinic.session.csrf';

const ROLE_LABELS: Record<string, string> = {
  sysadmin: '系统管理员',
  owner: '经营负责人',
  frontdesk: '前台',
  dentist: '牙医',
  inventory: '库管',
  finance: '财务',
  patient: '患者',
};

/** 把后端角色代码转成界面可读名称；未知代码原样展示，避免臆造翻译。 */
export function roleLabel(code: string): string {
  return ROLE_LABELS[code] ?? code;
}

/** 把门店范围转成界面可读文本；空范围表示未限定门店。 */
export function branchScopeText(branchIds: string[]): string {
  if (!branchIds || branchIds.length === 0) return '未限定门店';
  return branchIds.join('、');
}
