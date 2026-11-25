import { DEMO_NOTICE, recalls, visits } from '../../demo/data';
import { isDemoEnabled, setDemoEnabled } from '../../demo/store';

Page({
  data: { demoNotice: DEMO_NOTICE, demoEnabled: true, visits, recalls, tab: 'visits' },

  onShow() {
    this.setData({ demoEnabled: isDemoEnabled() });
  },

  switchTab(e: WechatMiniprogram.BaseEvent) {
    this.setData({ tab: String((e.currentTarget.dataset as Record<string, unknown>).tab ?? 'visits') });
  },
  backHome() {
    wx.navigateTo({ url: '/pages/home/home' });
  },
  goRealRecords() {
    setDemoEnabled(false);
    wx.navigateTo({ url: '/pages/real-records/real-records' });
  },
});
