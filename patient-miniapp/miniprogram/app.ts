App<IAppOption>({
  globalData: {},
  onLaunch() {
    // 会话只保存在内存；小程序重启后由首页重新走 wx.login，不读取持久令牌。
  },
});
