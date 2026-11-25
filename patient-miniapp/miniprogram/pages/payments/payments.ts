import { authFlow } from '../../services/auth-flow';
import { createHttpClient } from '../../services/http';
import { API_BASE_URL } from '../../services/config';
import { patientSession } from '../../services/session';

type Row = Record<string, unknown>;
const client = createHttpClient({ baseUrl: API_BASE_URL, timeoutMs: 15000, getBearerToken: () => patientSession.token });
const money = (value: unknown) => {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error('费用金额无法解析。');
  return `¥ ${(value / 100).toFixed(2)}`;
};
const rows = (value: unknown): Row[] => {
  if (!Array.isArray(value) || value.some((x) => x === null || typeof x !== 'object' || Array.isArray(x)))
    throw new Error('费用记录无法解析。');
  return value as Row[];
};

Page({
  data: { hasSession: false, loadRequestId: 0, identityRevision: -1, loading: false, error: '', bills: [] as Row[], payments: [] as Row[], refunds: [] as Row[], invoices: [] as Row[], tab: 'bills' },

  onShow() {
    void this.load();
  },
  onHide() {
    this.setData({ loadRequestId: this.data.loadRequestId + 1, loading: false });
  },
  onUnload() {
    this.setData({ loadRequestId: this.data.loadRequestId + 1 });
  },

  isCurrent(requestId: number, revision: number): boolean {
    return this.data.loadRequestId === requestId &&
      this.data.identityRevision === revision &&
      authFlow.sessionRevision === revision &&
      authFlow.hasSession;
  },

  switchTab(e: WechatMiniprogram.BaseEvent) {
    this.setData({ tab: String((e.currentTarget.dataset as Record<string, unknown>).tab ?? 'bills') });
  },
  async load() {
    const revision = authFlow.sessionRevision;
    const requestId = this.data.loadRequestId + 1;
    const identityChanged = this.data.identityRevision !== revision || this.data.hasSession !== authFlow.hasSession;
    const patch: Record<string, unknown> = {
      loadRequestId: requestId,
      identityRevision: revision,
      hasSession: authFlow.hasSession,
      loading: authFlow.hasSession,
      error: '',
    };
    if (identityChanged) {
      Object.assign(patch, { bills: [], payments: [], refunds: [], invoices: [] });
    }
    if (!authFlow.hasSession) {
      Object.assign(patch, {
        loading: false,
        error: '请先登录后查看本人费用记录。',
      });
      this.setData(patch);
      return;
    }
    this.setData(patch);
    try {
      const result = await client.get<unknown>('/me/billing');
      if (!this.isCurrent(requestId, revision)) return;
      if (result.data === null || typeof result.data !== 'object' || Array.isArray(result.data)) throw new Error('费用记录无法解析。');
      const body = result.data as Record<string, unknown>;
      this.setData({
        bills: rows(body['bills']).map((r) => ({ ...r, amount: money(r['total_cents']), paid: money(r['paid_cents']), remaining: money(r['remaining_cents']) })),
        payments: rows(body['payments']).map((r) => ({ ...r, amount: money(r['amount_cents']) })),
        refunds: rows(body['refunds']).map((r) => ({ ...r, amount: money(r['amount_cents']) })),
        invoices: rows(body['invoices']).map((r) => ({ ...r, amount: money(r['amount_cents']) })),
        loading: false,
      });
    } catch (e) {
      if (!this.isCurrent(requestId, revision)) return;
      this.setData({ loading: false, error: e instanceof Error ? e.message : '费用记录读取失败。' });
    }
  },
  goLogin() { wx.navigateTo({ url: '/pages/index/index' }); },
  backHome() {
    wx.navigateTo({ url: '/pages/home/home' });
  },
});
