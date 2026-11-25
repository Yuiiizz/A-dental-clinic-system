import { authFlow } from '../../services/auth-flow';
import { patientSession } from '../../services/session';
import { isDemoEnabled } from '../../demo/store';
import { createPublicDirectoryApi } from '../../services/public-directory';
import type { PublicBranch } from '../../services/public-directory';
import { createRealBookingApi, isApiError } from '../../services/booking-api';
import type { RealBookingOverview } from '../../services/booking-api';
import { createRecordsApi } from '../../services/records-api';
import { deriveDates, deriveServices } from '../../services/booking-flow';
import type { FlowDate, FlowService, FlowSlot } from '../../services/booking-flow';

type Step = 'branch' | 'service' | 'schedule' | 'confirm' | 'success';

const directory = createPublicDirectoryApi();
const bookingApi = createRealBookingApi();
const recordsApi = createRecordsApi();

function errorMessage(value: unknown): string {
  return value instanceof Error ? value.message : '预约信息读取失败，请重试。';
}

Page({
  data: {
    step: 'branch' as Step,
    demoBlocked: false,
    branches: [] as PublicBranch[],
    branchesLoading: false,
    branchesError: '',
    branchesRequestId: 0,
    branch: null as PublicBranch | null,
    branchId: '',
    requestedServiceId: '',
    hasSession: false,
    loginRequired: false,
    overview: null as RealBookingOverview | null,
    overviewLoading: false,
    overviewError: '',
    overviewRequestId: 0,
    identityRevision: -1,
    services: [] as FlowService[],
    service: null as FlowService | null,
    dates: [] as FlowDate[],
    selectedDate: '',
    selectedSlot: null as FlowSlot | null,
    visitType: '' as '' | '初诊' | '复诊',
    visitTypeNotice: '',
    visitTypeLoading: false,
    visitTypeError: '',
    visitTypeRequestId: 0,
    submitting: false,
    operationId: 0,
    message: '',
    appointmentId: '',
  },

  onLoad(options: Record<string, string | undefined> = {}) {
    const branchId = options.branch_id ?? '';
    const serviceId = options.item_id ?? '';
    this.setData({ branchId: /^\d{1,18}$/.test(branchId) ? branchId : '',
      requestedServiceId: /^\d{1,18}$/.test(serviceId) ? serviceId : '' });
  },

  onShow() {
    if (isDemoEnabled()) {
      this.setData({ demoBlocked: true, step: 'branch', branchesLoading: false, overviewLoading: false });
      return;
    }
    const revision = patientSession.revision;
    const identityChanged = this.data.identityRevision !== revision || this.data.hasSession !== authFlow.hasSession;
    const patch: Record<string, unknown> = {
      demoBlocked: false,
      hasSession: authFlow.hasSession,
      identityRevision: revision,
      loginRequired: Boolean(this.data.branch && !authFlow.hasSession),
    };
    if (identityChanged) {
      Object.assign(patch, {
        overview: null,
        overviewError: '',
        services: [],
        service: null,
        dates: [],
        selectedDate: '',
        selectedSlot: null,
        visitType: '',
        visitTypeNotice: '',
        visitTypeError: '',
        visitTypeLoading: false,
        visitTypeRequestId: this.data.visitTypeRequestId + 1,
        appointmentId: '',
        message: '',
        step: 'branch',
      });
    }
    this.setData(patch);
    void this.loadBranches();
    if (this.data.branchId && !authFlow.hasSession) this.setData({ loginRequired: true });
  },

  onHide() {
    this.setData({
      branchesRequestId: this.data.branchesRequestId + 1,
      overviewRequestId: this.data.overviewRequestId + 1,
      operationId: this.data.operationId + 1,
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
      visitTypeLoading: false,
      submitting: false,
    });
  },
  onUnload() {
    this.setData({
      branchesRequestId: this.data.branchesRequestId + 1,
      overviewRequestId: this.data.overviewRequestId + 1,
      operationId: this.data.operationId + 1,
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
      visitTypeLoading: false,
      submitting: false,
    });
  },

  async loadBranches() {
    if (isDemoEnabled()) return;
    const requestId = this.data.branchesRequestId + 1;
    this.setData({ branchesRequestId: requestId, branchesLoading: true, branchesError: '' });
    try {
      const branches = await directory.branches();
      if (requestId !== this.data.branchesRequestId) return;
      const branch = branches.find((row) => row.branch_id === this.data.branchId) ?? null;
      this.setData({
        branches,
        branch,
        branchesLoading: false,
        loginRequired: Boolean(branch && !authFlow.hasSession),
      });
      if (branch && authFlow.hasSession && !this.data.overviewLoading) {
        void this.loadOverview(this.data.step === 'branch');
      }
    } catch (error) {
      if (requestId !== this.data.branchesRequestId) return;
      this.setData({ branchesLoading: false, branchesError: errorMessage(error), branches: [] });
    }
  },

  chooseBranch(event: WechatMiniprogram.BaseEvent) {
    const id = String((event.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const branch = this.data.branches.find((row) => row.branch_id === id);
    if (!branch) return;
    this.setData({
      branch,
      branchId: branch.branch_id,
      requestedServiceId: '',
      loginRequired: !authFlow.hasSession,
      step: 'branch',
      service: null,
      services: [],
      dates: [],
      selectedDate: '',
      selectedSlot: null,
      visitType: '',
      visitTypeNotice: '',
      visitTypeError: '',
      visitTypeLoading: false,
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
      overview: null,
      overviewError: '',
    });
    if (authFlow.hasSession) void this.loadOverview(true);
  },

  async loadOverview(moveToService: boolean) {
    if (isDemoEnabled() || !authFlow.hasSession || !this.data.branchId) return;
    const revision = patientSession.revision;
    const requestId = this.data.overviewRequestId + 1;
    this.setData({ overviewRequestId: requestId, overviewLoading: true, overviewError: '' });
    try {
      const overview = await bookingApi.overview();
      if (requestId !== this.data.overviewRequestId || revision !== patientSession.revision || !authFlow.hasSession) return;
      const services = deriveServices(overview, this.data.branchId);
      const selectedService = services.find((service) => service.id ===
        (this.data.service?.id ?? this.data.requestedServiceId)) ?? null;
      const dates = selectedService ? deriveDates(overview, this.data.branchId, selectedService.id) : [];
      const selectedDate = dates.some((date) => date.date === this.data.selectedDate) ? this.data.selectedDate : '';
      const selectedSlot = selectedDate && this.data.selectedSlot
        ? dates.find((date) => date.date === selectedDate)?.morning.concat(
          dates.find((date) => date.date === selectedDate)?.afternoon ?? [],
        ).find((slot) => slot.scheduleId === this.data.selectedSlot?.scheduleId &&
          slot.slot === this.data.selectedSlot?.slot) ?? null
        : null;
      const patch: Record<string, unknown> = {
        overview,
        services,
        service: selectedService,
        dates,
        selectedDate,
        selectedSlot,
        overviewLoading: false,
        overviewError: '',
      };
      if (moveToService && this.data.step === 'branch') patch.step = selectedService ? 'schedule' : 'service';
      if (this.data.step === 'confirm' && !selectedSlot) patch.step = 'schedule';
      this.setData(patch);
    } catch (error) {
      if (requestId !== this.data.overviewRequestId || revision !== patientSession.revision) return;
      this.setData({ overviewLoading: false, overviewError: errorMessage(error) });
    }
  },

  retryOverview() {
    void this.loadOverview(this.data.step === 'branch');
  },

  chooseService(event: WechatMiniprogram.BaseEvent) {
    const id = String((event.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const service = this.data.services.find((row) => row.id === id);
    if (!service || !this.data.overview) return;
    const dates = deriveDates(this.data.overview, this.data.branchId, service.id);
    this.setData({
      service,
      dates,
      selectedDate: '',
      selectedSlot: null,
      visitType: '',
      visitTypeNotice: '',
      visitTypeError: '',
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
      step: 'schedule',
    });
  },

  chooseSlot(event: WechatMiniprogram.BaseEvent) {
    const data = event.currentTarget.dataset as Record<string, unknown>;
    const date = this.data.dates.find((row) => row.date === String(data.date ?? ''));
    const slot = date?.morning.concat(date.afternoon).find((row) =>
      row.scheduleId === String(data.schedule ?? '') && row.slot === String(data.slot ?? ''));
    if (!slot) return;
    this.setData({
      selectedDate: String(data.date ?? ''),
      selectedSlot: slot,
      step: 'confirm',
      visitType: '',
      visitTypeNotice: '',
      visitTypeError: '',
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
    });
    void this.loadVisitType();
  },

  async loadVisitType() {
    const selected = this.data.selectedSlot;
    if (!selected) return;
    const requestId = this.data.visitTypeRequestId + 1;
    const slotSnapshot = `${selected.scheduleId}:${selected.slot}`;
    const revision = authFlow.sessionRevision;
    this.setData({ visitTypeRequestId: requestId, visitTypeLoading: true, visitTypeNotice: '', visitTypeError: '' });
    try {
      const records = await recordsApi.load();
      if (requestId !== this.data.visitTypeRequestId || revision !== authFlow.sessionRevision || !authFlow.hasSession) return;
      if (!this.data.selectedSlot || `${this.data.selectedSlot.scheduleId}:${this.data.selectedSlot.slot}` !== slotSnapshot) return;
      this.setData({
        visitTypeLoading: false,
        visitTypeNotice: records.visits.length > 0
          ? '检测到本人已有历史就诊记录。普通预约只记录登记类型，不能据此确认复诊资格。'
          : '未检测到本人历史就诊记录；请按实际情况选择普通预约登记类型。',
      });
    } catch (error) {
      if (requestId !== this.data.visitTypeRequestId || revision !== authFlow.sessionRevision) return;
      if (!this.data.selectedSlot || `${this.data.selectedSlot.scheduleId}:${this.data.selectedSlot.slot}` !== slotSnapshot) return;
      this.setData({
        visitTypeLoading: false,
        visitTypeError: '历史就诊记录暂时无法读取；这不影响你选择普通预约登记类型，但普通预约不会校验复诊资格。',
      });
    }
  },

  chooseVisitType(event: WechatMiniprogram.BaseEvent) {
    const value = String((event.currentTarget.dataset as Record<string, unknown>).value ?? '');
    if (value === '初诊' || value === '复诊') this.setData({ visitType: value });
  },

  retryVisitType() {
    this.setData({ visitType: '', visitTypeNotice: '', visitTypeError: '', visitTypeLoading: false, visitTypeRequestId: this.data.visitTypeRequestId + 1 });
    void this.loadVisitType();
  },

  back() {
    if (this.data.submitting) return;
    if (this.data.step === 'service') {
      this.setData({ step: 'branch', service: null, services: [], dates: [], selectedDate: '', selectedSlot: null, visitType: '', visitTypeNotice: '', visitTypeError: '', visitTypeLoading: false, visitTypeRequestId: this.data.visitTypeRequestId + 1 });
      return;
    }
    if (this.data.step === 'schedule') {
      this.setData({ step: 'service', selectedDate: '', selectedSlot: null, visitType: '', visitTypeNotice: '', visitTypeError: '', visitTypeLoading: false, visitTypeRequestId: this.data.visitTypeRequestId + 1 });
      return;
    }
    if (this.data.step === 'confirm') {
      this.setData({ step: 'schedule', visitType: '', visitTypeNotice: '', visitTypeError: '', visitTypeLoading: false, visitTypeRequestId: this.data.visitTypeRequestId + 1 });
      return;
    }
    if (this.data.step === 'success') {
      wx.navigateTo({ url: '/pages/real-booking/real-booking' });
      return;
    }
    wx.navigateBack({ delta: 1 });
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/index/index?returnTo=booking-flow' });
  },

  goMine() {
    wx.navigateTo({ url: '/pages/real-booking/real-booking' });
  },

  restart() {
    this.setData({
      step: 'branch',
      service: null,
      services: [],
      dates: [],
      selectedDate: '',
      selectedSlot: null,
      visitType: '',
      visitTypeNotice: '',
      visitTypeError: '',
      visitTypeLoading: false,
      visitTypeRequestId: this.data.visitTypeRequestId + 1,
      message: '',
      appointmentId: '',
    });
    if (authFlow.hasSession) void this.loadOverview(false);
  },

  async submit() {
    if (this.data.submitting || !this.data.service || !this.data.selectedSlot || !this.data.visitType) return;
    const revision = authFlow.sessionRevision;
    const token = patientSession.token;
    const operationId = this.data.operationId + 1;
    this.setData({ operationId, submitting: true, message: '', overviewError: '' });
    try {
      const created = await bookingApi.create(
        this.data.selectedSlot.scheduleId,
        this.data.service.id,
        this.data.selectedSlot.slot,
        this.data.visitType,
        '患者端自助预约',
      );
      if (this.data.operationId !== operationId || revision !== authFlow.sessionRevision || token !== patientSession.token) return;
      this.setData({
        submitting: false,
        step: 'success',
        appointmentId: created.appointment_id,
        message: `预约 ${created.appointment_id} 已确认。`,
      });
    } catch (error) {
      if (this.data.operationId !== operationId || revision !== authFlow.sessionRevision) return;
      if (isApiError(error) && error.status === 409) {
        this.setData({
          submitting: false,
          selectedSlot: null,
          step: 'schedule',
          overviewError: '该时段已不可预约，已刷新可约时段，请重新选择；最终以服务端校验为准。',
        });
        await this.loadOverview(false);
        if (this.data.operationId === operationId) {
          this.setData({ overviewError: '该时段已不可预约，已刷新可约时段，请重新选择；最终以服务端校验为准。' });
        }
        return;
      }
      this.setData({ submitting: false, overviewError: errorMessage(error) });
    }
  },
});
