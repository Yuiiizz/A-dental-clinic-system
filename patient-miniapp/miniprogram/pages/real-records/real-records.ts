import { authFlow } from '../../services/auth-flow';
import { createRecordsApi } from '../../services/records-api';
import type { PatientPlan, PatientVisit, TreatmentPlan } from '../../services/records-api';

const api = createRecordsApi();
function dateTime(value: string): string {
  const d = new Date(new Date(value).getTime() + 8 * 3600000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}
Page({
  data: {
    hasSession: false,
    loadRequestId: 0,
    identityRevision: -1,
    operationId: 0,
    submitting: false,
    loading: false,
    error: '',
    tab: 'visits',
    visits: [] as Array<PatientVisit & { display: string }>,
    plans: [] as PatientPlan[],
    treatmentPlans: [] as Array<TreatmentPlan & { displayTotal: string }>,
    treatmentError: '',
  },
  onLoad() {},
  onShow() { void this.load(); },
  onHide() { this.invalidateRequests(); },
  onUnload() { this.invalidateRequests(false); },

  invalidateRequests(clearLoading = true) {
    this.setData({
      loadRequestId: this.data.loadRequestId + 1,
      operationId: this.data.operationId + 1,
      submitting: false,
      ...(clearLoading ? { loading: false } : {}),
    });
  },

  isCurrent(requestId: number, revision: number): boolean {
    return this.data.loadRequestId === requestId &&
      this.data.identityRevision === revision &&
      authFlow.sessionRevision === revision &&
      authFlow.hasSession;
  },

  isPageCurrent(revision: number, operationId: number): boolean {
    return authFlow.hasSession &&
      authFlow.sessionRevision === revision &&
      this.data.operationId === operationId;
  },

  beginOperation(): { id: number; revision: number } | null {
    if (!authFlow.hasSession) return null;
    const revision = authFlow.sessionRevision;
    const id = this.data.operationId + 1;
    this.setData({ operationId: id, submitting: true, error: '', treatmentError: '' });
    return { id, revision };
  },

  isOperationCurrent(op: { id: number; revision: number }): boolean {
    return this.data.operationId === op.id &&
      authFlow.sessionRevision === op.revision &&
      authFlow.hasSession;
  },

  finishOperation(op: { id: number; revision: number }, patch: Record<string, unknown> = {}): boolean {
    if (!this.isOperationCurrent(op)) return false;
    this.setData({ ...patch, submitting: false });
    return true;
  },
  switchTab(e: WechatMiniprogram.BaseEvent) {
    this.setData({ tab: String((e.currentTarget.dataset as Record<string, unknown>).tab ?? 'visits') });
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
      Object.assign(patch, {
        visits: [],
        plans: [],
        treatmentPlans: [],
        treatmentError: '',
        submitting: false,
        operationId: this.data.operationId + 1,
      });
    }
    if (!authFlow.hasSession) {
      Object.assign(patch, {
        loading: false,
        error: '请先完成微信登录或本人建档，才能读取真实就诊记录。',
      });
      this.setData(patch);
      return;
    }
    this.setData(patch);
    try {
      const result = await api.load();
      if (!this.isCurrent(requestId, revision)) return;
      let treatmentPlans: Array<TreatmentPlan & { displayTotal: string }> = [];
      let treatmentError = '';
      try {
        const plans = await api.loadTreatmentPlans();
        if (!this.isCurrent(requestId, revision)) return;
        treatmentPlans = plans.map((p) => ({ ...p, displayTotal: (p.estimated_total_cents / 100).toFixed(2) }));
      } catch {
        if (!this.isCurrent(requestId, revision)) return;
        treatmentError = '治疗计划读取失败，请重试。';
      }
      if (!this.isCurrent(requestId, revision)) return;
      this.setData({ visits: result.visits.map((v) => ({ ...v, display: dateTime(v.completed_at) })), plans: result.plans, treatmentPlans, treatmentError, loading: false });
    } catch (e) {
      if (!this.isCurrent(requestId, revision)) return;
      this.setData({ loading: false, error: e instanceof Error ? e.message : '真实就诊记录读取失败。' });
    }
  },
  acceptPlan(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!id || this.data.submitting) return;
    const revision = authFlow.sessionRevision;
    const operationId = this.data.operationId;
    wx.showModal({ title: '确认治疗计划', content: '确认接受该治疗计划吗？接受后会记录确认依据。', success: async (res) => {
      if (!res.confirm || this.data.submitting) return;
      if (!this.isPageCurrent(revision, operationId)) return;
      const op = this.beginOperation();
      if (!op) return;
      try {
        await api.acceptPlan(id);
        if (!this.isOperationCurrent(op)) return;
        await this.load();
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op);
      } catch (err) {
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op, { error: err instanceof Error ? err.message : '确认失败。' });
      }
    } });
  },
  rejectPlan(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!id || this.data.submitting) return;
    const revision = authFlow.sessionRevision;
    const operationId = this.data.operationId;
    wx.showModal({ title: '暂不接受计划', content: '拒绝后计划会保留在记录中，可联系诊所调整。', success: async (res) => {
      if (!res.confirm || this.data.submitting) return;
      if (!this.isPageCurrent(revision, operationId)) return;
      const op = this.beginOperation();
      if (!op) return;
      try {
        await api.rejectPlan(id);
        if (!this.isOperationCurrent(op)) return;
        await this.load();
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op);
      } catch (err) {
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op, { error: err instanceof Error ? err.message : '操作失败。' });
      }
    } });
  },
  bookFollowup(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!authFlow.hasSession || !this.data.plans.some((plan) => plan.plan_id === id && plan.state === '待协调')) return;
    wx.navigateTo({ url: `/pages/real-booking/real-booking?followup_plan_id=${encodeURIComponent(id)}` });
  },
  goLogin() { wx.navigateTo({ url: '/pages/index/index' }); },
  backHome() { wx.navigateTo({ url: '/pages/home/home' }); },
});
