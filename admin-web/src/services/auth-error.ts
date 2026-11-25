// 登录/改密/退出流程的中文错误提示映射。只输出安全、面向用户的文案，
// 不回显令牌、密码、原始 HTML 或内部实现术语；未知情况给通用兜底文案。
import { ApiError } from './http';

export type AuthErrorContext = 'login' | 'change' | 'logout';

export function authErrorMessage(error: unknown, context: AuthErrorContext): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'UNAUTHENTICATED':
        if (context === 'change') return '当前密码不正确，或登录已失效，请重新登录。';
        if (context === 'logout') return '登录已失效。';
        return '用户名或密码错误，或账号不可用。';
      case 'RATE_LIMITED':
        return '尝试次数过多，请稍后再试。';
      case 'FORBIDDEN':
        return '请求被拒绝，请刷新页面后重试。';
      case 'VALIDATION_FAILED':
        if (context === 'change') return '新密码不符合要求，请检查后重试。';
        return '输入不符合要求，请检查后重试。';
      case 'CSRF_MISSING':
        return '登录状态异常，请重新登录。';
      case 'NETWORK_ERROR':
        return '网络连接失败，请检查网络后重试。';
      case 'TIMEOUT':
        return '请求超时，请稍后重试。';
      default:
        return '操作失败，请稍后重试。';
    }
  }
  return '操作失败，请稍后重试。';
}
