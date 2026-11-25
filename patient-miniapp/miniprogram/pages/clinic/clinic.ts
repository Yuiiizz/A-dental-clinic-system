import { DEMO_NOTICE, clinics } from '../../demo/data';
import { isDemoEnabled, setDemoEnabled } from '../../demo/store';
import { createPublicDirectoryApi } from '../../services/public-directory';
import type { PublicBranch } from '../../services/public-directory';
import { authFlow } from '../../services/auth-flow';

const directory = createPublicDirectoryApi();

Page({
  data: { demoNotice: DEMO_NOTICE, demoEnabled: true, showDemo: isDemoEnabled(), hasPatientSession: false, clinics, selected: clinics[0],
    directoryLoading: false, directoryError: '', directoryEmpty: false, directoryRequestId: 0,
    publicBranches: [] as PublicBranch[], publicSelected: null as PublicBranch | null },

  onLoad(options: { id?: string } = {}) {
    const selected = clinics.find((c) => c.id === options.id) ?? clinics[0];
    this.setData({ selected, demoEnabled: isDemoEnabled() });
  },

  onShow() {
    const demo = isDemoEnabled();
    const hasSession = authFlow.hasSession;
    if (demo) {
      this.setData({
        demoEnabled: true, showDemo: true, hasPatientSession: hasSession,
        directoryRequestId: this.data.directoryRequestId + 1,
        directoryLoading: false, directoryError: '', directoryEmpty: false,
        publicBranches: [], publicSelected: null,
      });
      return;
    }
    this.setData({ demoEnabled: false, showDemo: false, hasPatientSession: hasSession });
    void this.loadDirectory();
  },

  async loadDirectory() {
    if (isDemoEnabled()) return;
    const id = this.data.directoryRequestId + 1;
    this.setData({ directoryRequestId: id, directoryLoading: true, directoryError: '', directoryEmpty: false });
    try {
      const branches = await directory.branches();
      if (id !== this.data.directoryRequestId) return;
      const selected = branches.find((branch) => branch.branch_id === this.data.publicSelected?.branch_id) ?? branches[0] ?? null;
      this.setData({ publicBranches: branches, publicSelected: selected, directoryLoading: false, directoryEmpty: branches.length === 0 });
    } catch (error) {
      if (id !== this.data.directoryRequestId) return;
      this.setData({ directoryLoading: false, directoryEmpty: false, directoryError: error instanceof Error ? error.message : '门店资料读取失败。' });
    }
  },

  choosePublic(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const selected = this.data.publicBranches.find((branch) => branch.branch_id === id);
    if (selected) this.setData({ publicSelected: selected });
  },

  onUnload() {
    this.setData({ directoryRequestId: this.data.directoryRequestId + 1 });
  },

  choose(e: WechatMiniprogram.BaseEvent) {
    const id = String((e.currentTarget.dataset as Record<string, unknown>).id ?? '');
    const selected = clinics.find((c) => c.id === id) ?? clinics[0];
    this.setData({ selected, demoEnabled: isDemoEnabled() });
  },

  switchToRealMode() {
    setDemoEnabled(false);
    this.onShow();
    wx.showToast({ title: '已切换真实模式', icon: 'none' });
  },

  openDoctors() {
    const branchId = !this.data.showDemo ? this.data.publicSelected?.branch_id : '';
    const query = branchId ? `?branch_id=${encodeURIComponent(branchId)}` : '';
    wx.navigateTo({ url: `/pages/doctors/doctors${query}` });
  },
  openBooking() {
    if (!this.data.showDemo) return;
    wx.navigateTo({ url: '/pages/booking/booking' });
  },
  openRealBooking() {
    const id = this.data.publicSelected?.branch_id ? `?branch_id=${this.data.publicSelected.branch_id}` : '';
    wx.navigateTo({ url: `/pages/booking-flow/booking-flow${id}` });
  },
  openRecords() {
    wx.navigateTo({ url: '/pages/records/records' });
  },
  openProfile() {
    wx.redirectTo({ url: '/pages/profile/profile' });
  },
  backHome() {
    wx.redirectTo({ url: '/pages/home/home' });
  },
});
