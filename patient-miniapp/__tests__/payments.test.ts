import { beforeEach, describe, expect, it, vi } from 'vitest';

const flowMock = vi.hoisted(() => ({ hasSession: true, sessionRevision: 1 }));
vi.mock('../miniprogram/services/auth-flow', () => ({ authFlow: flowMock }));

const apiMock = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../miniprogram/services/http', () => ({ createHttpClient: () => ({ get: apiMock.get }) }));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  [key: string]: unknown;
}

function body(id: string) {
  return {
    data: {
      bills: [{ bill_id: id, total_cents: 100, paid_cents: 0, remaining_cents: 100 }],
      payments: [],
      refunds: [],
      invoices: [],
    },
  };
}

async function loadPage(): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => { config = value; });
  vi.stubGlobal('wx', { navigateTo: vi.fn() });
  vi.resetModules();
  await import('../miniprogram/pages/payments/payments');
  if (!config) throw new Error('payments page missing');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch) { Object.assign(this.data, patch); },
  };
  for (const [key, value] of Object.entries(source)) {
    if (typeof value === 'function') page[key] = (value as (...args: unknown[]) => unknown).bind(page);
  }
  return page;
}

describe('费用页跨会话响应边界', () => {
  beforeEach(() => {
    flowMock.hasSession = true;
    flowMock.sessionRevision = 1;
    apiMock.get.mockReset();
  });

  it('A 请求未返回时切换身份，迟到账单不能写回页面', async () => {
    let resolveA!: (value: unknown) => void;
    apiMock.get.mockReturnValueOnce(new Promise((resolve) => { resolveA = resolve; }));
    const page = await loadPage();
    const load = page['load'] as () => Promise<void>;
    const first = load();

    let resolveB!: (value: unknown) => void;
    apiMock.get.mockReturnValueOnce(new Promise((resolve) => { resolveB = resolve; }));
    flowMock.sessionRevision = 2;
    const second = load();
    expect(page.data.bills).toEqual([]);

    resolveB(body('B'));
    await second;
    expect((page.data.bills as Array<{ bill_id: string }>)[0].bill_id).toBe('B');

    resolveA(body('A'));
    await first;
    expect((page.data.bills as Array<{ bill_id: string }>)[0].bill_id).toBe('B');
  });

  it('旧身份读取失败也不能写错误提示', async () => {
    let rejectA!: (reason: unknown) => void;
    apiMock.get.mockReturnValueOnce(new Promise((_, reject) => { rejectA = reject; }));
    const page = await loadPage();
    const first = (page['load'] as () => Promise<void>)();
    flowMock.sessionRevision = 2;
    rejectA(new Error('旧会话失败'));
    await first;
    expect(page.data.error).toBe('');
    expect(page.data.bills).toEqual([]);
  });

  it('同页两次读取乱序返回时只保留最后一次', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    apiMock.get
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve; }));
    const page = await loadPage();
    const load = page['load'] as () => Promise<void>;
    const first = load();
    const second = load();
    resolveSecond(body('B'));
    await second;
    resolveFirst(body('A'));
    await first;
    expect((page.data.bills as Array<{ bill_id: string }>)[0].bill_id).toBe('B');
  });
});
