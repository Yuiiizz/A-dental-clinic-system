// wx.login 的薄封装。只返回一次性 code，不读取/伪造 openid；失败的 errMsg 不向上透传。
//
// 本地开发覆盖只允许同时满足：
//  1. 运行在微信开发者工具（platform=devtools）；
//  2. API 基址是本机 loopback。
// 正式、体验、真机和远程 API 都不能读取 Storage 的 mock code，避免本地联调开关变成生产后门。
import { API_BASE_URL } from './config';

export class WxLoginError extends Error {
  readonly code = 'WX_LOGIN_FAILED';

  constructor() {
    super('微信登录未完成，请重试。');
    this.name = 'WxLoginError';
  }
}

export function isLocalMockAllowed(platform: string | undefined, apiBaseUrl: string): boolean {
  if (platform !== 'devtools') return false;
  return /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(?::\d+)?(?:\/|$)/i.test(apiBaseUrl);
}

function currentPlatform(): string | undefined {
  try {
    return (wx as unknown as { getSystemInfoSync?: () => { platform?: string } })
      .getSystemInfoSync?.()?.platform;
  } catch {
    return undefined;
  }
}

export function wxLogin(): Promise<string> {
  try {
    if (isLocalMockAllowed(currentPlatform(), API_BASE_URL)) {
      const mockCode = wx.getStorageSync('a-clinic.mockWxCode');
      if (typeof mockCode === 'string' && mockCode.trim()) return Promise.resolve(mockCode.trim());
    }
  } catch { /* ignore local override */ }
  return new Promise<string>((resolve, reject) => {
    wx.login({
      success(res) {
        if (typeof res.code === 'string' && res.code.trim() !== '') {
          resolve(res.code);
          return;
        }
        reject(new WxLoginError());
      },
      fail() {
        reject(new WxLoginError());
      },
    });
  });
}
