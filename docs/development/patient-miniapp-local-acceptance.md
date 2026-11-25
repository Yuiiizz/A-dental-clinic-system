# 患者端小程序本地验收记录（2026-11-14）

## 环境

- 仓库：`D:/GitHub/A-dental-clinic-system`
- 页面：`patient-miniapp/`（原生微信小程序 + TypeScript）
- WeChat DevTools：`2.02.2609240 Stable`，基础库 `3.17.6`，本机路径 `D:\Tencent\微信web开发者工具`
- Node.js：`v24.22.1`，npm `11.19.1`
- 本地 API：`API_BASE_URL` 为空，真实接口未连接；本地演示数据仍保留并明确标识。
- 2026-11-21 真实入口盘点与账号隔离修正见 `docs/development/patient-miniapp-real-entry-audit-20261121.md`；界面、模式切换、图标和建档页改进见 `docs/development/patient-miniapp-ui-polish-20261121.md`；本文件保留 2026-11-14 的旧轮次验收记录。

## 实际编译

1. 直接执行 `cli.bat open` 被拒绝，原因是开发者工具“服务端口”关闭。随后启动 GUI，在项目窗口中执行“普通编译”，未上传、未预览到真机。
2. 第一次打开时模拟器空白，控制台出现工具侧
   `SystemError (appServiceSDKScriptError)`：`screenHeight`/`getPageTypeById` 读取失败；重新执行普通编译后首页正常渲染，页面注册成功。该错误出现在工具启动/首次加载阶段，不是业务 WXML/TS 编译错误。
3. 重新编译后控制台没有业务异常；可见警告主要是资源 preload，另有页面重载时的
   `ERR_ABORTED .../instanceframe.html` 和工具 timing 日志 `markDevLaunchEnv timingData not found`。
   工具顶部仍显示 Warning 计数，本轮未定位到 WXML 编译错误。
4. 实际渲染的页面包括：
   `home`、`clinic`、`doctors`、`booking`、`appointments`、`records`、`payments`、
   `profile`、`index`、`real-booking`、`real-records`、`register`、`bind`。

## 页面走查结果

| 页面 | 实际结果 |
| --- | --- |
| 首页 | 正常渲染；未登录显示“登录/建档”；真实服务入口与本地演示入口分开标识；演示预约/门店/医生区块仅作为样例。模拟器实际点按“登录/建档”后跳转到 `pages/index/index`。 |
| 门店信息（旧轮次，未接 API） | 真实门店接口报“API 地址未配置”，下方本地演示门店仍明确标识；空/失败/重试状态可见。 |
| 医生名录（旧轮次，未接 API） | 同上；真实接口错误与本地演示医生并列显示，未把演示医生冒充真实排班。 |
| 预约挂号 | 演示选项目、医生、日期/时段正常；明确“不写入后台、不占真实号源、不发起支付”。 |
| 我的预约 | 演示预约正常；用模拟器输入点选预约卡片后出现“改约/取消预约”；通过页面方法执行取消后 AP-1001 变为“已取消（演示）”并从待就诊列表移除，提示“未写入后台”。取消按钮的注入鼠标事件未触发，真实触屏点击待复核。 |
| 就诊与复诊 | 演示记录正常，明确不含完整病历、不构成医疗建议。 |
| 费用与发票 | 未登录时只显示登录提示和“微信登录 / 本人建档”，不显示任何演示费用；未实现支付/开票的边界仍明确。 |
| 个人信息 | 未登录显示“返回登录”；登录态真实资料区在本次未覆盖（没有真实微信会话）。 |
| 患者登录 | 未登录、微信登录、浏览演示首页、说明均正常；没有执行真实 `wx.login`，因此没有登录后的真实数据。 |
| 真实预约 | 未登录标题改为“未登录 · 真实接口”，显示登录入口；未渲染任何真实排班或虚构数据。 |
| 真实记录 | 未登录显示登录入口和空状态，不展示演示记录；真实记录仍需登录后才能读取。 |
| 本人建档 / 绑定旧档 | 无登录上下文时显示“注册上下文不存在或已失效”，属于预期的受保护入口。 |

## 本轮修改

- `pages/real-booking/real-booking.ts`、`.wxml`
  - 增加 `hasSession` 状态与登录入口；未登录标题不再写“已登录用户”；
  - 未登录与“接口错误”不再重复显示同一提示。
- `pages/real-records/real-records.ts`、`.wxml`
  - 同上，未登录提供“微信登录 / 本人建档”入口。
- `pages/payments/payments.ts`、`.wxml`
  - 未登录时提供登录入口，不再只给一个无效的“重试”。
- `__tests__/real-booking.test.ts`
  - 新增“未登录显示登录入口且不读取真实接口”“已撤回候选不提供答复入口”的回归测试；并在 `beforeEach` 重置会话状态，避免测试间污染。
- `pages/appointments/appointments.wxml`
  - 已取消（演示）预约的“改约/取消预约”按钮同步禁用，避免历史页出现可操作假象。
- `pages/real-records/real-records.ts`、`.wxml`
  - 治疗计划单独读取失败时显示“治疗计划读取失败，请重试”，不再静默退化成空计划。
- `app.wxss`
  - 补充统一 `.error` 样式，使真实预约/真实记录的错误提示有可见的警示边界。
- `__tests__/demo-pages.test.ts`、`__tests__/real-records.test.ts`
  - 补充取消后不可改约、治疗计划失败提示、未登录不请求真实接口的回归测试。
- 真实预约/真实记录写操作防护测试：
  - `__tests__/real-booking.test.ts` 覆盖创建、改约、取消、扫码签到、协调答复中切换身份，隐藏返回，以及旧操作结束不清理新操作 busy 状态。
  - `__tests__/real-records.test.ts` 覆盖治疗计划接受/拒绝中切换身份及隐藏返回。

### 跨会话旧响应防护

- 真实预约、真实记录、费用页每次读取都捕获 `authFlow.sessionRevision` 和页内请求代次；成功与失败都先复核代次、身份和页面状态。
- 身份变化后开始新读取时立即清空上一身份的预约/病历/费用数据。
- 真实记录在 `api.load()` 与治疗计划读取之间再次复核身份；页面隐藏/卸载会使旧请求失效。
- `onLoad` 只做页面初始化，真实读取统一由 `onShow` 发起，避免首次进入重复请求。
- 回归测试使用受控延迟 Promise，覆盖迟到成功、迟到失败、同页乱序返回、页面隐藏和 `onLoad`/`onShow` 不重复读取。
- 真实预约的创建、改约、取消、扫码签到、协调答复，以及真实记录的治疗计划接受/拒绝，均使用独立 `operationId`：弹窗确认后、发请求前和每次 `await` 后复核身份与操作代次。
- 身份变化或页面隐藏时清除旧 `message`、复位 `submitting/loading`；旧操作不能写预约编号、候诊号、错误提示，也不能发起旧流程刷新。
- 页面隐藏再返回同一账号时重新开始读取，不会永久卡在 `submitting/loading`；旧操作晚到不会清除新操作的 busy 状态。
- 真实记录和真实预约页的写操作按钮在 `submitting` 期间禁用；费用页只有本人记录查询。
- Codex 复核时补齐成功提交后的等待边界：创建、改约、取消和扫码签到在重新读取权威预约期间保持 `submitting`，防止旧页面数据上的重复点击；新增受控延迟回归测试。

未修改后端、OpenAPI、数据库、权限或旧迁移；没有真实数据写入，没有 Git 提交/推送。

## 验证命令与结果

```powershell
cd D:\GitHub\A-dental-clinic-system\patient-miniapp
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
```

- `typecheck`：通过。
- `test:unit`：**16 个测试文件、124 个测试全部通过**（追加 wx-login、本地真实预约回归后重跑）。
- DevTools：普通编译后小程序实际渲染；没有使用单元测试替代开发者工具结论。

## 截图

- `docs/screenshots/wechat-home.png`
- `docs/screenshots/wechat-clinic.png`
- `docs/screenshots/wechat-doctors.png`
- `docs/screenshots/wechat-booking.png`
- `docs/screenshots/wechat-appointments-shell.png`
- `docs/screenshots/wechat-payments.png`
- `docs/screenshots/wechat-real-booking-shell.png`
- `docs/screenshots/wechat-recompiled.png`

部分页面（records/profile/login/real-records）在开发者工具截图 API 调用中超时，已通过同一模拟器页面的实际 DOM 文本走查；这不是页面编译失败的替代结论。

## 未覆盖 / 待复核

- 没有真实微信登录、真实患者会话、真实 API 数据；登录后的预约协调撤回/答复、真实预约提交、真实记录、账单/发票未验证。
- 首页“登录/建档”已在模拟器实际点按，成功跳转到 `pages/index/index`；其他入口仍以页面路由打开验证，未用真实触屏/真机逐一点击。
- `API 地址未配置` 时真实数据不可用；本地演示与真实数据保持分离。**2026-11-15 的 local mock + 隔离库批次已另行接通真实预约闭环**，见 `docs/development/local-miniapp-e2e.md`；旧轮次记录仍按原日期保留。
- 开发者工具顶部仍有 Warning 计数；本轮只确认无业务 WXML 编译异常。
- 管理端预约页源码已接入 `patient_selection_token`（`admin-web/src/views/AppointmentWorkbenchView.vue`、`admin-web/src/services/appointments.ts`）；本轮未重新验收管理端页面交互。

## 2026-11-15 追加：local mock 真实预约闭环

- 使用一次性隔离 PostgreSQL、教学虚拟种子和 `local` profile mock 微信身份，非真实微信/真机/云端。
- 患者候选过滤后，已有 09:00 预约的默认候选变为 09:30；页面实际提交预约 950 并在页面和 `/me/booking` 读回。
- 页面 stale 候选被同一患者占用后提交返回 HTTP 409 `SLOT_UNAVAILABLE`，页面显示“该时段已不可预约”并刷新候选。
- 详见 [local-miniapp-e2e.md](local-miniapp-e2e.md)。
