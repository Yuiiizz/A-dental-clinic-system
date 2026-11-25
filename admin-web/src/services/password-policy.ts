// 新密码前端即时校验，口径与后端保持一致：
//  - 下限按 Unicode 码点计 12 个（Array.from 按码点拆分，emoji 不会被算成两个）。
//  - 上限按 UTF-8 字节计 72 个（TextEncoder），不能用 maxlength=72 的 UTF-16 单元冒充。
// 这里只做即时提示，服务端校验仍是最终依据；不保存、不记录密码。

export const MIN_PASSWORD_CODE_POINTS = 12;
export const MAX_PASSWORD_UTF8_BYTES = 72;

/** Unicode 码点数量（emoji/代理对按 1 个码点计）。 */
export function passwordCodePoints(value: string): number {
  return Array.from(value).length;
}

/** UTF-8 字节数，与后端按字节限制的口径一致。 */
export function passwordUtf8Bytes(value: string): number {
  return new TextEncoder().encode(value).length;
}

/** 返回第一条不满足的策略提示；全部满足返回 null。 */
export function validateNewPassword(next: string, confirm: string): string | null {
  if (passwordCodePoints(next) < MIN_PASSWORD_CODE_POINTS) {
    return `新密码至少需要 ${MIN_PASSWORD_CODE_POINTS} 个字符。`;
  }
  if (passwordUtf8Bytes(next) > MAX_PASSWORD_UTF8_BYTES) {
    return `新密码最多 ${MAX_PASSWORD_UTF8_BYTES} 个 UTF-8 字节（中文、emoji 等字符会占用多个字节）。`;
  }
  if (next !== confirm) return '两次输入的新密码不一致。';
  return null;
}
