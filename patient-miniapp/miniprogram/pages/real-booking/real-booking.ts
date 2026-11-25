import { authFlow } from '../../services/auth-flow';
import { patientSession } from '../../services/session';
import { createRealBookingApi, isApiError } from '../../services/booking-api';
import type { ChangeProposal, RealAppointment, RealBookingOverview } from '../../services/booking-api';
import { createRecordsApi } from '../../services/records-api';
import type { PatientPlan } from '../../services/records-api';

const api = createRealBookingApi();
const recordsApi = createRecordsApi();

function shanghai(value: string): { date: string; time: string } {
  const d = new Date(new Date(value).getTime() + 8 * 3600000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return { date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`, time: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}` };
}

type AppointmentView = RealAppointment & { display: string; canCancel: boolean; statusTone: string };

function groupAppointments(rows: RealAppointment[], now = Date.now()) {
  const today = shanghai(new Date(now).toISOString()).date;
  const tomorrow = shanghai(new Date(now + 86400000).toISOString()).date;
  const appointments: AppointmentView[] = rows.map((row) => {
    const start = shanghai(row.starts_at);
    const day = start.date === today ? '今天' : start.date === tomorrow ? '明天' : start.date;
    return { ...row, display: `${day} ${start.time}`, canCancel: row.state === '已确认',
      statusTone: row.state === '候诊中' ? 'waiting' : row.state === '已确认' ? 'confirmed' : 'muted' };
  });
  const active = appointments.filter((row) => row.state === '已确认' || row.state === '候诊中');
  active.sort((a, b) => {
    const rank = (row: AppointmentView) => row.state === '候诊中' ? 0 : Date.parse(row.starts_at) >= now ? 1 : 2;
    const difference = rank(a) - rank(b);
    if (difference) return difference;
    if (rank(a) === 1) return Date.parse(a.starts_at) - Date.parse(b.starts_at);
    return Math.abs(Date.parse(a.starts_at) - now) - Math.abs(Date.parse(b.starts_at) - now);
  });
  const history = appointments.filter((row) => row.state !== '已确认' && row.state !== '候诊中')
    .sort((a, b) => Date.parse(b.starts_at) - Date.parse(a.starts_at));
  return { appointments, featuredAppointment: active[0] ?? null, otherActiveAppointments: active.slice(1), historyAppointments: history };
}

Page({
  data: {
    real: true,
    hasSession: false,
    loadRequestId: 0,
    identityRevision: -1,
    changeRequestId: 0,
    operationId: 0,
    loading: false,
    error: '',
    message: '',
    overview: null as RealBookingOverview | null,
    followupPlanId: '',
    followupPlan: null as PatientPlan | null,
    schedules: [] as Array<{ id: string; label: string; date: string; start: string; end: string; minutes: number }>,
    services: [] as Array<{ id: string; label: string; minutes: number }>,
    appointments: [] as AppointmentView[],
    featuredAppointment: null as AppointmentView | null,
    otherActiveAppointments: [] as AppointmentView[],
    historyAppointments: [] as AppointmentView[],
    historyExpanded: false,
    showBookingForm: false,
    changes: [] as Array<ChangeProposal & { display: string; canAnswer: boolean }>,
    changesError: '',
    scheduleIndex: 0,
    serviceIndex: 0,
    slots: [] as Array<{ value: string; label: string }>,
    slotIndex: 0,
    submitting: false,
    scanPending: false,
    reschedulingId: '',
    rescheduleVersion: 0,
    rescheduleReason: '',
    rescheduleScheduleIndex: 0,
    rescheduleServiceIndex: 0,
    rescheduleSlotIndex: 0,
    rescheduleSlots: [] as Array<{ value: string; label: string }>,
  },

  onLoad(options: Record<string, string | undefined> = {}) {
    this.setData({ followupPlanId: options.followup_plan_id ?? '' });
  },
  onShow() { if (!this.data.scanPending) void this.load(); },
  onHide() { if (!this.data.scanPending) this.invalidateRequests(); },
  onUnload() { this.invalidateRequests(false); },

  invalidateRequests(clearLoading = true) {
    this.setData({
      loadRequestId: this.data.loadRequestId + 1,
      changeRequestId: this.data.changeRequestId + 1,
      operationId: this.data.operationId + 1,
      submitting: false,
      scanPending: false,
      message: '',
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
    this.setData({ operationId: id, submitting: true, error: '', message: '' });
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
      changesError: '',
    };
    if (this.data.followupPlanId) Object.assign(patch, {
      followupPlan: null, overview: null, schedules: [], services: [], slots: [],
    });
    if (identityChanged) {
      Object.assign(patch, {
        overview: null,
        followupPlan: null,
        schedules: [],
        services: [],
        appointments: [],
        featuredAppointment: null,
        otherActiveAppointments: [],
        historyAppointments: [],
        historyExpanded: false,
        showBookingForm: false,
        changes: [],
        scheduleIndex: 0,
        serviceIndex: 0,
        slots: [],
        slotIndex: 0,
        reschedulingId: '',
        submitting: false,
        message: '',
        operationId: this.data.operationId + 1,
      });
    }
    if (!authFlow.hasSession) {
      Object.assign(patch, {
        loading: false,
        error: '请先完成微信登录或本人建档，才能使用预约。',
      });
      this.setData(patch);
      return;
    }
    this.setData(patch);
    try {
      const overview = await api.overview();
      if (!this.isCurrent(requestId, revision)) return;
      let followupPlan: PatientPlan | null = null;
      if (this.data.followupPlanId) {
        const records = await recordsApi.load();
        if (!this.isCurrent(requestId, revision)) return;
        followupPlan = records.plans.find((plan) => plan.plan_id === this.data.followupPlanId) ?? null;
        if (!followupPlan || followupPlan.state !== '待协调') {
          this.setData({ overview: null, followupPlan: null, loading: false,
            error: '这份复诊计划已不可自助预约，请刷新复诊计划或联系门店。' });
          return;
        }
      }
      const schedules = overview.schedules.filter((s) => !followupPlan || s.dentist_id === followupPlan.dentist_id).map((s) => {
        const start = shanghai(s.starts_at);
        const end = shanghai(s.ends_at);
        return { id: s.schedule_id, label: `${s.dentist_name} · ${s.branch_name} · ${start.date} ${start.time}–${end.time}`, date: start.date, start: start.time, end: end.time, minutes: 0 };
      });
      const services = overview.services.filter((s) => !followupPlan || (followupPlan.service_item_id == null || followupPlan.service_item_id === s.item_id) && overview.availability.some((a) =>
        a.item_id === s.item_id && a.duration_minutes >= followupPlan.duration_minutes &&
        overview.schedules.some((schedule) => schedule.schedule_id === a.schedule_id && schedule.dentist_id === followupPlan.dentist_id)))
        .map((s) => ({ id: s.item_id, label: `${s.name} · ${s.minutes}分钟 · ¥${s.price}`, minutes: s.minutes }));
      const grouped = groupAppointments(overview.appointments);
      this.setData({ overview, followupPlan, schedules, services, ...grouped, loading: false },
        () => this.refreshSlots(this.data.scheduleIndex, this.data.serviceIndex));
      if (this.isCurrent(requestId, revision)) void this.loadChanges();
    } catch (e) {
      if (!this.isCurrent(requestId, revision)) return;
      this.setData({ loading: false, error: e instanceof Error ? e.message : '预约信息读取失败。' });
    }
  },

  toggleHistory() { this.setData({ historyExpanded: !this.data.historyExpanded }); },
  toggleBookingForm() { this.setData({ showBookingForm: !this.data.showBookingForm }); },

  async loadChanges() {
    const revision = authFlow.sessionRevision;
    const loadRequestId = this.data.loadRequestId;
    const requestId = this.data.changeRequestId + 1;
    const identityChanged = this.data.identityRevision !== revision;
    this.setData({ changeRequestId: requestId, identityRevision: revision, ...(identityChanged ? { changes: [] } : {}) });
    if(!authFlow.hasSession) { this.setData({changes:[],changesError:''}); return; }
    const token=patientSession.token;
    try {
      const rows=await api.changes();
      if(!this.isCurrent(loadRequestId, revision) || this.data.changeRequestId !== requestId || token!==patientSession.token) return;
      this.setData({changes:rows.map(row=>({...row,
        display:`${shanghai(row.starts_at).date} ${shanghai(row.starts_at).time}`,
        canAnswer:row.state==='待答复'})),changesError:''});
    } catch(e) {
      if(!this.isCurrent(loadRequestId, revision) || this.data.changeRequestId !== requestId || token!==patientSession.token) return;
      this.setData({changes:[],changesError:e instanceof Error?e.message:'协调方案读取失败。'});
    }
  },

  answerChange(e: WechatMiniprogram.BaseEvent) {
    const id=String((e.currentTarget.dataset as Record<string,unknown>).id??'');
    const accept=String((e.currentTarget.dataset as Record<string,unknown>).accept)==='true';
    const row=this.data.changes.find(c=>c.change_id===id);
    if(!row || !row.canAnswer || this.data.submitting) return;
    const revision=authFlow.sessionRevision;
    const operationId=this.data.operationId;
    wx.showModal({title:accept?'确认同意候选方案':'确认拒绝候选方案',
      content:accept?'同意只记录您的答复；新时段还须前台落实，当前预约暂不释放。':'拒绝后原预约仍保留，请联系前台继续协调。',
      success:async (result)=>{
        if(!result.confirm || this.data.submitting) return;
        if(!this.isPageCurrent(revision,operationId)) return;
        const op=this.beginOperation();
        if(!op) return;
        const token=patientSession.token;
        try {
          const changed=await api.answerChange(id,accept,row.version);
          if(token!==patientSession.token || !this.isOperationCurrent(op)) return;
          await this.loadChanges();
          if(token!==patientSession.token || !this.isOperationCurrent(op)) return;
          this.finishOperation(op,{message:changed.applied?'方案已落实。':accept?'已记录同意；请等待前台落实新时段。':'已记录拒绝；原预约仍保留。'});
        } catch(err) {
          if(token!==patientSession.token || !this.isOperationCurrent(op)) return;
          await this.loadChanges();
          if(token!==patientSession.token || !this.isOperationCurrent(op)) return;
          this.finishOperation(op,{error:isApiError(err)?err.message:'答复结果不明，请查看方案状态再操作。'});
        } finally {
          if(this.isOperationCurrent(op)) this.setData({submitting:false});
        }
      }});
  },

  pickSchedule(e: WechatMiniprogram.BaseEvent) {
    const index = Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0);
    this.setData({ scheduleIndex: index });
    this.refreshSlots(index, this.data.serviceIndex);
  },
  pickService(e: WechatMiniprogram.BaseEvent) {
    const index = Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0);
    this.setData({ serviceIndex: index });
    this.refreshSlots(this.data.scheduleIndex, index);
  },
  pickSlot(e: WechatMiniprogram.BaseEvent) {
    this.setData({ slotIndex: Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0) });
  },
  slotsFor(scheduleIndex: number, serviceIndex: number): Array<{ value: string; label: string }> {
    const schedule = this.data.schedules[scheduleIndex];
    const service = this.data.services[serviceIndex];
    if (!schedule || !service || !this.data.overview) return [];
    const entry = this.data.overview.availability.find(
      (a) => a.schedule_id === schedule.id && a.item_id === service.id);
    if (this.data.followupPlan && (!entry || entry.duration_minutes < this.data.followupPlan.duration_minutes)) return [];
    return (entry?.slots ?? []).map((slot) => ({ value: slot, label: shanghai(slot).time }));
  },
  refreshSlots(scheduleIndex: number, serviceIndex: number) {
    this.setData({ slots: this.slotsFor(scheduleIndex, serviceIndex), slotIndex: 0 });
  },
  startReschedule(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!id || this.data.submitting) return;
    const appointment = this.data.appointments.find(a => a.appointment_id === id);
    if (!appointment || appointment.state !== '已确认') return;
    let scheduleIndex = 0;
    let serviceIndex = 0;
    let slots = this.slotsFor(0, 0);
    for (let si = 0; si < this.data.schedules.length && slots.length === 0; si++) {
      for (let ii = 0; ii < this.data.services.length && slots.length === 0; ii++) {
        const candidate = this.slotsFor(si, ii);
        if (candidate.length) { scheduleIndex = si; serviceIndex = ii; slots = candidate; }
      }
    }
    this.setData({ rescheduleVersion: appointment.version, rescheduleReason: '', reschedulingId: id,
      rescheduleScheduleIndex: scheduleIndex, rescheduleServiceIndex: serviceIndex,
      rescheduleSlotIndex: 0, rescheduleSlots: slots, message: '', error: '' });
  },
  pickRescheduleSchedule(e: WechatMiniprogram.BaseEvent) {
    const index = Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0);
    this.setData({ rescheduleScheduleIndex: index, rescheduleSlots: this.slotsFor(index, this.data.rescheduleServiceIndex), rescheduleSlotIndex: 0, error: '' });
  },
  pickRescheduleService(e: WechatMiniprogram.BaseEvent) {
    const index = Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0);
    this.setData({ rescheduleServiceIndex: index, rescheduleSlots: this.slotsFor(this.data.rescheduleScheduleIndex, index), rescheduleSlotIndex: 0, error: '' });
  },
  pickRescheduleSlot(e: WechatMiniprogram.BaseEvent) {
    this.setData({ rescheduleSlotIndex: Number((e as unknown as { detail?: { value?: string } }).detail?.value ?? 0), error: '' });
  },
  setRescheduleReason(e: WechatMiniprogram.Input) { this.setData({ rescheduleReason: e.detail.value, error: '' }); },
  async confirmReschedule() {
    if (!this.data.reschedulingId || this.data.submitting) return;
    if (!this.data.rescheduleReason.trim()) { this.setData({ error: '请填写改约原因。' }); return; }
    const schedule = this.data.schedules[this.data.rescheduleScheduleIndex];
    const service = this.data.services[this.data.rescheduleServiceIndex];
    const slot = this.data.rescheduleSlots[this.data.rescheduleSlotIndex];
    if (!schedule || !service || !slot) { this.setData({ error: '请选择新的排班、项目和时段。' }); return; }
    const op = this.beginOperation();
    if (!op) return;
    try {
      const result = await api.reschedule(this.data.reschedulingId, schedule.id, service.id, slot.value, this.data.rescheduleVersion, this.data.rescheduleReason.trim());
      if (!this.isOperationCurrent(op)) return;
      await this.load();
      this.finishOperation(op, { reschedulingId: '', message: `改约成功，新预约编号 ${result.replacement.appointment_id}，原预约已保留。` });
    } catch (e) {
      if (!this.isOperationCurrent(op)) return;
      const message = isApiError(e) ? e.message : e instanceof Error ? e.message : '改约失败。';
      if (isApiError(e) && e.status === 409) {
        await this.load();
        if (!this.isOperationCurrent(op)) return;
        this.setData({ rescheduleSlotIndex: 0 });
      }
      this.finishOperation(op, { error: message });
    }
  },
  closeReschedule() { if (this.data.submitting) return; this.setData({ reschedulingId: '', error: '' }); },

  async scanCheckin(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const appointment = this.data.appointments.find(a => a.appointment_id === id);
    if (!appointment || appointment.state !== '已确认' || this.data.submitting) return;
    const op = this.beginOperation();
    if (!op) return;
    this.setData({ scanPending: true });
    let code: string;
    try {
      code = await new Promise<string>((resolve, reject) => wx.scanCode({ scanType: ['qrCode'],
        success: result => resolve(result.result), fail: reject }));
    } catch (scanError) {
      this.setData({ scanPending: false });
      if (!this.finishOperation(op)) {
        if (authFlow.sessionRevision !== op.revision && this.data.operationId === op.id) void this.load();
        return;
      }
      const scanMessage = scanError && typeof scanError === 'object' && 'errMsg' in scanError
        ? String(scanError.errMsg) : '';
      if (!/cancel/i.test(scanMessage)) {
        wx.showModal({ title: '未能打开扫码', content: '请检查相机权限后重试；也可以请前台人工签到。', showCancel: false });
      }
      return;
    }
    this.setData({ scanPending: false });
    if (!this.isOperationCurrent(op)) {
      if (authFlow.sessionRevision !== op.revision && this.data.operationId === op.id) void this.load();
      return;
    }
    try {
      const ticket = await api.checkin(id, code, appointment.version);
      if (!this.isOperationCurrent(op)) return;
      const message = `签到成功，候诊号 ${ticket.queue_no}。请留意前台叫号。`;
      wx.showModal({ title: '签到成功', content: message, showCancel: false });
      await this.load();
      this.finishOperation(op, { message });
    } catch (err) {
      if (!this.isOperationCurrent(op)) return;
      if (isApiError(err) && err.status > 0 && err.status < 500) {
        const message = err.message;
        const content = err.code === 'BRANCH_STATE_CONFLICT'
          ? `${message}。请核对预约是否为今天、是否在门店的签到时间内。` : message;
        wx.showModal({ title: '暂未签到', content, showCancel: false });
        if (err.status !== 409) {
          this.finishOperation(op, { error: message });
          return;
        }
        await this.load();
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op, { error: message });
      } else {
        const message = '签到结果暂未确认，请查看本页预约状态或联系前台；不要连续扫码提交。';
        wx.showModal({ title: '请核对签到结果', content: message, showCancel: false });
        this.finishOperation(op, { error: message });
      }
    } finally {
      if (this.isOperationCurrent(op)) this.setData({ submitting: false });
    }
  },

  async submit() {
    if (this.data.submitting || this.data.loading || !this.data.overview ||
      (this.data.followupPlanId && !this.data.followupPlan)) return;
    const schedule = this.data.schedules[this.data.scheduleIndex];
    const service = this.data.services[this.data.serviceIndex];
    const slot = this.data.slots[this.data.slotIndex];
    if (!schedule || !service || !slot) { this.setData({ error: '请完整选择排班、项目和时段。' }); return; }
    const op = this.beginOperation();
    if (!op) return;
    try {
      const plan = this.data.followupPlan;
      const created = plan
        ? await api.create(schedule.id, service.id, slot.value, '复诊', plan.purpose, plan.plan_id)
        : await api.create(schedule.id, service.id, slot.value, '初诊', '患者端自助预约');
      if (!this.isOperationCurrent(op)) return;
      if (plan) this.setData({ followupPlanId: '', followupPlan: null });
      await this.load();
      this.finishOperation(op, { message: `预约 ${created.appointment_id} 已确认。` });
    } catch (e) {
      if (!this.isOperationCurrent(op)) return;
      const message = isApiError(e) ? e.message : e instanceof Error ? e.message : '预约提交失败。';
      if (isApiError(e) && e.status === 409) {
        await this.load();
        if (!this.isOperationCurrent(op)) return;
        this.setData({ slotIndex: 0 });
      }
      this.finishOperation(op, { error: message });
    }
  },

  cancel(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!id || this.data.submitting) return;
    const revision=authFlow.sessionRevision;
    const operationId=this.data.operationId;
    wx.showModal({ title: '取消预约', content: '确认取消该预约吗？此操作会写入后台。', success: async (res) => {
      if (!res.confirm) return;
      if (!this.isPageCurrent(revision, operationId)) return;
      const op=this.beginOperation();
      if (!op) return;
      try {
        await api.cancel(id, '患者自助取消');
        if (!this.isOperationCurrent(op)) return;
        await this.load();
        this.finishOperation(op, { message: '预约已取消。' });
      } catch (err) {
        if (!this.isOperationCurrent(op)) return;
        this.finishOperation(op, { error: err instanceof Error ? err.message : '取消失败。' });
      }
    } });
  },
  goLogin() { wx.navigateTo({ url: '/pages/index/index' }); },
  backHome() { wx.navigateTo({ url: '/pages/home/home' }); },
});
