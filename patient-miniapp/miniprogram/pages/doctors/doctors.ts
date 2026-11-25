import { DEMO_NOTICE, doctors } from '../../demo/data';
import { isDemoEnabled, setDemoEnabled } from '../../demo/store';
import { authFlow } from '../../services/auth-flow';
import { createRealBookingApi } from '../../services/booking-api';
import { patientSession } from '../../services/session';
import { createPublicDirectoryApi } from '../../services/public-directory';
import type { PublicDoctor } from '../../services/public-directory';
import { createDoctorPreferencesApi } from '../../services/doctor-preferences';
import type { PreferenceSource } from '../../services/doctor-preferences';
import { buildScheduleBoard } from '../../services/schedule-board';
import type { BoardDay, BoardService } from '../../services/schedule-board';

const bookingApi = createRealBookingApi();
const publicDirectory = createPublicDirectoryApi();
const preferencesApi = createDoctorPreferencesApi();

Page({
  data: { demoNotice: DEMO_NOTICE, demoEnabled: true, showDemo: isDemoEnabled(), doctors, hasPatientSession: false, identityRevision: -1,
    sharedDoctorId: '', selectedBranchId: '', selectedBranchName: '',
    directoryLoading: false, directoryError: '', directoryEmpty: false, directoryRequestId: 0,
    publicDoctors: [] as Array<PublicDoctor & { favoriteSource: PreferenceSource | '' }>,
    preferenceRequestId: 0, preferenceEditRevision: 0, preferenceError: '', favoriteSources: {} as Record<string, PreferenceSource>, busyDoctorId: '',
    sourceOptions: ['本人选择', '熟人推荐', '既往就诊'] as PreferenceSource[],
    realLoading: false, realError: '', realRequestId: 0,
    scheduleServices: [] as BoardService[], selectedServiceId: '', scheduleDays: [] as BoardDay[] },

  onLoad(options: Record<string, string | undefined>) {
    const raw = options?.doctorId ?? '';
    const branchId = options?.branch_id ?? '';
    this.setData({ sharedDoctorId: /^\d{1,18}$/.test(raw) ? raw : '',
      selectedBranchId: /^\d{1,18}$/.test(branchId) ? branchId : '' });
  },

  onShareAppMessage(e: unknown) {
    const id = String((e as { target?: { dataset?: { id?: unknown } } })?.target?.dataset?.id ?? '');
    const doctor = this.data.publicDoctors.find((item) => item.doctor_id === id);
    return doctor ? {
      title: `查看${doctor.name}医生的公开信息`,
      path: `/pages/doctors/doctors?doctorId=${encodeURIComponent(doctor.doctor_id)}${this.data.selectedBranchId ? `&branch_id=${encodeURIComponent(this.data.selectedBranchId)}` : ''}`,
    } : { title: '查看诊所公开医生名录', path: '/pages/doctors/doctors' };
  },

  onShow() {
    const demo = isDemoEnabled();
    const hasSession = authFlow.hasSession;
    const revision = patientSession.revision;
    const identityChanged = this.data.identityRevision !== revision || this.data.hasPatientSession !== hasSession;
    const patch: Record<string, unknown> = {
      demoEnabled: demo, showDemo: demo,
      hasPatientSession: hasSession, identityRevision: revision,
    };
    if (demo) {
      Object.assign(patch, {
        directoryRequestId: this.data.directoryRequestId + 1,
        directoryLoading: false, directoryError: '', directoryEmpty: false,
        publicDoctors: [],
        favoriteSources: {}, preferenceRequestId: this.data.preferenceRequestId + 1, preferenceError: '',
        realRequestId: this.data.realRequestId + 1, scheduleServices: [], selectedServiceId: '', scheduleDays: [], realError: '', realLoading: false,
      });
      this.setData(patch);
      return;
    }
    if (identityChanged) {
      Object.assign(patch, {
        favoriteSources: {},
        publicDoctors: this.data.publicDoctors.map((person) => ({ ...person, favoriteSource: '' })),
        scheduleServices: [], selectedServiceId: '', scheduleDays: [], realError: '', realLoading: false,
        preferenceError: '',
      });
    }
    this.setData(patch);
    void this.loadDirectory();
    if (hasSession) {
      void this.loadPreferences();
      void this.loadReal();
    } else {
      this.setData({
        preferenceRequestId: this.data.preferenceRequestId + 1,
        favoriteSources: {}, preferenceError: '',
        publicDoctors: this.data.publicDoctors.map((person) => ({ ...person, favoriteSource: '' })),
        realRequestId: this.data.realRequestId + 1,
        scheduleServices: [], selectedServiceId: '', scheduleDays: [], realError: '', realLoading: false,
      });
    }
  },

  async loadDirectory() {
    if (isDemoEnabled()) return;
    const id = this.data.directoryRequestId + 1;
    this.setData({ directoryRequestId: id, directoryLoading: true, directoryError: '', directoryEmpty: false });
    try {
      const [people, branches] = await Promise.all([
        publicDirectory.doctors(), this.data.selectedBranchId ? publicDirectory.branches() : Promise.resolve([]),
      ]);
      if (id !== this.data.directoryRequestId) return;
      const branchId = this.data.selectedBranchId;
      const visible = branchId ? people.filter((person) => person.branches.some((branch) => branch.branch_id === branchId)) : people;
      this.setData({ publicDoctors: visible.map((person) => ({ ...person, favoriteSource: this.data.favoriteSources[person.doctor_id] ?? '' })),
        selectedBranchName: branches.find((branch) => branch.branch_id === branchId)?.name ?? '',
        directoryLoading: false, directoryEmpty: visible.length === 0 });
    } catch (error) {
      if (id !== this.data.directoryRequestId) return;
      this.setData({ directoryLoading: false, directoryEmpty: false, directoryError: error instanceof Error ? error.message : '医生资料读取失败。' });
    }
  },
  onUnload() {
    this.setData({ directoryRequestId: this.data.directoryRequestId + 1, realRequestId: this.data.realRequestId + 1,
      preferenceRequestId: this.data.preferenceRequestId + 1 });
  },

  async loadPreferences() {
    if (isDemoEnabled()) return;
    const id = this.data.preferenceRequestId + 1;
    const editRevision = this.data.preferenceEditRevision;
    const sessionRevision = patientSession.revision;
    this.setData({ preferenceRequestId: id, preferenceError: '' });
    try {
      const rows = await preferencesApi.list();
      if (id !== this.data.preferenceRequestId || editRevision !== this.data.preferenceEditRevision
          || sessionRevision !== patientSession.revision || !authFlow.hasSession) return;
      const favoriteSources: Record<string, PreferenceSource> = {};
      rows.forEach((row) => { favoriteSources[row.dentist_id] = row.source; });
      this.setData({ favoriteSources, publicDoctors: this.data.publicDoctors.map((person) => ({ ...person, favoriteSource: favoriteSources[person.doctor_id] ?? '' })) });
    } catch {
      if (id === this.data.preferenceRequestId && sessionRevision === patientSession.revision)
        this.setData({ preferenceError: '收藏状态暂时无法读取，可稍后重试。' });
    }
  },

  async chooseFavoriteSource(e: WechatMiniprogram.BaseEvent) {
    const doctorId = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const source = this.data.sourceOptions[Number((e as unknown as { detail: { value?: string } }).detail.value)];
    if (!doctorId || !source || this.data.busyDoctorId) return;
    const sessionRevision = patientSession.revision;
    const editRevision = this.data.preferenceEditRevision + 1;
    this.setData({ busyDoctorId: doctorId, preferenceEditRevision: editRevision, preferenceError: '' });
    try {
      await preferencesApi.set(doctorId, source);
      if (sessionRevision !== patientSession.revision || editRevision !== this.data.preferenceEditRevision) return;
      this.setData({ favoriteSources: { ...this.data.favoriteSources, [doctorId]: source },
        publicDoctors: this.data.publicDoctors.map((person) => person.doctor_id === doctorId ? { ...person, favoriteSource: source } : person) });
    } catch {
      if (sessionRevision === patientSession.revision && editRevision === this.data.preferenceEditRevision)
        this.setData({ preferenceError: '收藏结果尚未确认，请刷新收藏状态后再操作。' });
    } finally {
      if (editRevision === this.data.preferenceEditRevision) this.setData({ busyDoctorId: '' });
    }
  },

  async removeFavorite(e: WechatMiniprogram.BaseEvent) {
    const doctorId = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    if (!doctorId || this.data.busyDoctorId) return;
    const sessionRevision = patientSession.revision;
    const editRevision = this.data.preferenceEditRevision + 1;
    this.setData({ busyDoctorId: doctorId, preferenceEditRevision: editRevision, preferenceError: '' });
    try {
      await preferencesApi.delete(doctorId);
      if (sessionRevision !== patientSession.revision || editRevision !== this.data.preferenceEditRevision) return;
      const favoriteSources = { ...this.data.favoriteSources };
      delete favoriteSources[doctorId];
      this.setData({ favoriteSources, publicDoctors: this.data.publicDoctors.map((person) => person.doctor_id === doctorId ? { ...person, favoriteSource: '' } : person) });
    } catch {
      if (sessionRevision === patientSession.revision && editRevision === this.data.preferenceEditRevision)
        this.setData({ preferenceError: '取消收藏结果尚未确认，请刷新收藏状态后再操作。' });
    } finally {
      if (editRevision === this.data.preferenceEditRevision) this.setData({ busyDoctorId: '' });
    }
  },

  async loadReal() {
    if (isDemoEnabled()) return;
    const requestId = this.data.realRequestId + 1;
    const sessionRevision = patientSession.revision;
    this.setData({ realRequestId: requestId, realLoading: true, realError: '' });
    try {
      const overview = await bookingApi.overview();
      if (requestId !== this.data.realRequestId || sessionRevision !== patientSession.revision || !authFlow.hasSession) return;
      const scheduleServices = buildScheduleBoard(overview, this.data.selectedBranchId);
      const selectedServiceId = scheduleServices.some((service) => service.id === this.data.selectedServiceId)
        ? this.data.selectedServiceId : scheduleServices[0]?.id ?? '';
      this.setData({ scheduleServices, selectedServiceId,
        scheduleDays: scheduleServices.find((service) => service.id === selectedServiceId)?.days ?? [], realLoading: false });
    } catch (error) {
      if (requestId !== this.data.realRequestId || sessionRevision !== patientSession.revision) return;
      this.setData({ realLoading: false, scheduleServices: [], selectedServiceId: '', scheduleDays: [], realError: error instanceof Error ? error.message : '医生排班读取失败。' });
    }
  },
  chooseScheduleService(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const service = this.data.scheduleServices.find((row) => row.id === id);
    if (service) this.setData({ selectedServiceId: id, scheduleDays: service.days });
  },
  openReal(e: WechatMiniprogram.BaseEvent) {
    const branchId = String((e.currentTarget.dataset as Record<string, unknown>).branch ?? '');
    const serviceId = this.data.selectedServiceId;
    const query = branchId && serviceId ? `?branch_id=${encodeURIComponent(branchId)}&item_id=${encodeURIComponent(serviceId)}` : '';
    wx.navigateTo({ url: `/pages/booking-flow/booking-flow${query}` });
  },

  open(e: WechatMiniprogram.BaseEvent) {
    if (!this.data.showDemo) return;
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    wx.navigateTo({ url: `/pages/booking/booking?doctorId=${id}` });
  },
  switchToRealMode() {
    setDemoEnabled(false);
    this.onShow();
    wx.showToast({ title: '已切换真实模式', icon: 'none' });
  },
  backHome() {
    wx.navigateTo({ url: '/pages/home/home' });
  },
});
