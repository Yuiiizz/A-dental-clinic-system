import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../miniprogram/services/config', () => ({
  API_BASE_URL: 'http://127.0.0.1:58034/api/v1',
}));

const loginMock = vi.fn();

beforeEach(() => {
  loginMock.mockReset();
  vi.stubGlobal('wx', {
    getSystemInfoSync: () => ({ platform: 'devtools' }),
    getStorageSync: () => 'mock-local-code',
    login: loginMock,
  });
});

describe('本地 mock 微信登录边界', () => {
  it('只允许开发者工具 + loopback API', async () => {
    const { isLocalMockAllowed } = await import('../miniprogram/services/wx-login');
    expect(isLocalMockAllowed('devtools', 'http://127.0.0.1:58034/api/v1')).toBe(true);
    expect(isLocalMockAllowed('devtools', 'http://localhost:58034/api/v1')).toBe(true);
    expect(isLocalMockAllowed('devtools', 'http://192.168.1.5:58034/api/v1')).toBe(false);
    expect(isLocalMockAllowed('devtools', 'https://api.example.test/api/v1')).toBe(false);
    expect(isLocalMockAllowed('ios', 'http://127.0.0.1:58034/api/v1')).toBe(false);
    expect(isLocalMockAllowed(undefined, 'http://127.0.0.1:58034/api/v1')).toBe(false);
  });

  it('开发工具本地 API 才读取 mock code', async () => {
    const { wxLogin } = await import('../miniprogram/services/wx-login');
    await expect(wxLogin()).resolves.toBe('mock-local-code');
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('非开发者工具忽略 Storage 覆盖并走真实 wx.login', async () => {
    vi.stubGlobal('wx', {
      getSystemInfoSync: () => ({ platform: 'ios' }),
      getStorageSync: () => 'mock-local-code',
      login: loginMock.mockImplementation(({ success }: { success: (v: { code: string }) => void }) =>
        success({ code: 'real-code' })),
    });
    const { wxLogin } = await import('../miniprogram/services/wx-login');
    await expect(wxLogin()).resolves.toBe('real-code');
    expect(loginMock).toHaveBeenCalledTimes(1);
  });
});
