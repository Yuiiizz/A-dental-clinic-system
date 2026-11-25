# 患者小程序界面改进与验收（2026-11-21）

本批只改患者小程序界面、交互和本地图标资源；未改网页端、后端、数据库、OpenAPI、Word 或云端配置；未删除数据，未上传体验版/未发布。

## 改了什么

### 1. 首页入口和下一步

- 首页新增明显的 **真实模式 / 演示模式** 分段切换；默认真实模式，测试者再主动切换演示。
- 真实模式顶部先显示“第一次使用？登录/建档”或“下一步：选择门店或医生”，再显示服务入口。
- 门店、医生排班保持可浏览；预约挂号、我的预约、就诊与复诊、费用与发票、个人信息在未登录时明确标“需登录”。
- 删除了重复的服务说明，只保留一条简短边界说明。

### 2. 真实/演示隔离

- 演示模式不请求真实公开目录、`/me/booking` 或 `/me/changes`，并清空上一模式留下的真实数据与迟到响应。
- 首页演示区持续显示 **“演示模式 · 虚构样例／不写入后台”**，写明不会提交预约、占用号源或发起支付。
- 演示预约按钮改为“生成演示预约（不提交）”，成功后显示“演示预约已生成（未提交）”+ 演示编号；`demo/store` 仍返回“未写入后台”。
- 演示模式底部导航只保留演示首页和真实模式，不能误入个人中心或门诊真实入口。
- 门店、医生、个人中心也提供切回真实模式的入口。
- 真实模式未登录时，个人中心不再回退到演示预约/记录，而是去登录。
- 切换账号/退出登录/返回页面时，真实预约摘要、医生收藏、排班、费用和记录均按会话代次清空；演示模式只使用本地虚构状态。

### 3. 本人建档

- 原生 `input`/`textarea` 的边框移到外层 `.control`，避免真机上原生组件裁切边框。
- 字段按“基本信息 / 健康信息”分组；姓名、手机号必填，过敏说明在“有/不确定”时必填。
- 增加字段级错误提示和本地校验，提交前不请求接口；原有服务端校验和 `authFlow` 逻辑保留。
- 输入框/文本框增加 `adjust-position`、`cursor-spacing`、确认栏；页面底部使用安全区 padding，提交按钮保持在正常文档流中可见。
- 长过敏说明固定高度内滚动，显示 `0 / 300` 字数；窄屏无固定宽度。

### 4. 图标

- 新增本地 128×128 PNG 图标：`assets/icons/home.png`、`clinic.png`、`doctor.png`、`booking.png`、`appointments.png`、`records.png`、`payments.png`、`profile.png`、`real-mode.png`。
- 首页服务入口、演示入口和底部导航统一使用这些图标；不再使用 `✚ ◉ ▦ ◷ ▤ ¥` 等字符图标。
- 首页图标显示 52rpx，底部导航 44rpx；底部导航点击区域最小高度 92rpx，保持清晰和可点。

## 截图

改前首页（上一轮）：

![改前首页](../screenshots/patient-ui/home-before.png)

改后真实模式：

![改后真实模式](../screenshots/patient-ui/home-real-after.png)

改后演示模式：

![改后演示模式](../screenshots/patient-ui/home-demo-after.png)

改后建档页顶部：

![建档页顶部](../screenshots/patient-ui/register-after-top.png)

改后建档页必填错误：

![建档页错误](../screenshots/patient-ui/register-errors-after.png)

改后建档页底部（提交按钮可见）：

![建档页底部](../screenshots/patient-ui/register-after-bottom.png)

说明：首页有改前截图；建档页改前没有单独留存截图，改后是在微信开发者工具同一设备尺寸下截取。真机键盘弹起状态、窄屏和触屏点击仍需真机补验。

## 修改文件

- `patient-miniapp/miniprogram/pages/home/home.ts`、`home.wxml`、`home.wxss`
- `patient-miniapp/miniprogram/pages/doctors/doctors.ts`、`doctors.wxml`
- `patient-miniapp/miniprogram/pages/clinic/clinic.ts`、`clinic.wxml`
- `patient-miniapp/miniprogram/pages/booking/booking.ts`、`booking.wxml`
- `patient-miniapp/miniprogram/pages/appointments/appointments.ts`、`appointments.wxml`
- `patient-miniapp/miniprogram/pages/records/records.ts`、`records.wxml`
- `patient-miniapp/miniprogram/pages/profile/profile.ts`、`profile.wxml`
- `patient-miniapp/miniprogram/pages/index/index.ts`、`index.wxml`
- `patient-miniapp/miniprogram/pages/register/register.ts`、`register.wxml`、`register.wxss`
- `patient-miniapp/miniprogram/demo/store.ts`
- `patient-miniapp/miniprogram/app.wxss`
- `patient-miniapp/miniprogram/assets/icons/*.png`
- `patient-miniapp/__tests__/home-real.test.ts`、`doctors-session.test.ts`、`demo-pages.test.ts`、`pages.test.ts`

## 逐页实际打开

用开发者工具模拟器实际打开并读取渲染层文本：

| 页面 | 实际结果 | 截图 |
| --- | --- | --- |
| 首页·真实模式 | 真实模式高亮；真实门店/医生公开目录；未登录时“需登录”标识和登录引导可见 | `home-real-after.png` |
| 首页·演示模式 | 演示模式高亮；虚构样例横幅、演示入口和“不写入后台”说明可见；无真实服务入口 | `home-demo-after.png` |
| 登录页 | 微信登录、浏览演示（虚构）和旧档说明正常 | `login.png` |
| 门店页（真实） | 读取云栖门店、滨江门店；地址/电话/营业说明正常；未登录显示“登录后真实预约” | `clinic-real.png` |
| 医生页（真实） | 读取 8 位公开医生；未登录时提示登录后看真实排班 | `doctors-real.png` |
| 真实预约（未登录） | 显示“未登录 · 真实接口”和登录入口，不展示演示预约 | `real-booking-guest.png` |
| 真实记录（未登录） | 显示“未登录 · 真实接口”和登录入口，不展示演示记录 | `real-records-guest.png` |
| 个人信息（真实、未登录） | 只显示登录提示，不回退到演示预约/记录 | — |
| 费用（真实、未登录） | 只显示登录入口，不显示演示账单；本页不发起支付/开票 | — |
| 演示预约 | 明确“演示数据 · 本地模拟，未写入后台”；提交按钮为“生成演示预约（不提交）”，并有“进入真实预约” | `demo-booking.png` |
| 演示预约列表 | 明确演示状态，按钮为“新演示预约”，有“进入真实预约” | `demo-appointments.png` |
| 演示记录 | 明确演示摘要，有“进入真实就诊记录” | `demo-records.png` |

## 验证

```powershell
cd patient-miniapp
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
```

- `typecheck`：通过。
- `test:unit`：**18 个测试文件、134 项通过**。
- 微信开发者工具：`2.02.2609250 Stable`，基础库 `3.17.6`，普通编译通过；首页实际渲染真实模式和演示模式，建档页在模拟器实际渲染到顶部和底部（含提交按钮）。
- 已生成真机预览二维码（`cli preview`，AppID `wx84c3e69b16c8cc72`，包约 2.0 MB）；**未扫码、未上传体验版、未发布**。

## 仍未验证

- 真机微信登录、真机表单键盘弹起时输入框/文本框边框和提交按钮的完整可见性；模拟器不能替代真机。
- 窄屏（iPhone SE 等）和系统字体放大后的排版。
- 真实预览二维码没有在手机上扫描；没有真实患者账号操作真实预约/记录/费用。
- 真实演示模式切换在真机触屏上的手感、返回栈和长列表滚动。
- 没有执行云端写入、真实预约修改或版本上传/发布。

## Codex 定向复核建议

1. 用 DevTools 在 iPhone SE/iPhone 12 两档宽度实际打开首页、登录、建档、门店、医生、真实预约、真实记录、演示预约和演示记录，逐页核对真实/演示文案和导航。
2. 真机重点看建档页键盘弹起后姓名/手机号/过敏说明边框、长文本滚动和提交按钮。
3. 核对切换真实/演示、切换账号、退出登录后是否还有上一模式/上一账号数据。
4. 确认本地图标资源和 `assets/icons/` 路径在真机、不同 DPR 下清晰且点击区域足够。
5. 复核前不要上传新的体验版；预览二维码仅用于本轮真机检查。
