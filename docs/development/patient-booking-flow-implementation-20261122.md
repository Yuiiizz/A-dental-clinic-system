# 患者真实预约主路径实施记录（2026-11-22）

本批按审查稿和六项决定实施患者小程序真实预约主路径。未改后端、数据库、Word、云端配置；未增加教学数据、未重跑种子、未修改现有预约；未上传体验版或发布。

## 已实现流程

```text
首页/门店页
→ 选门店（公开）
→ 登录/建档（选项目前）
→ 选项目（仅当前门店实际 availability 中出现的项目）
→ 日期卡片 → 上午/下午、可约/已无可约/无排班
→ 选时段
→ 确认（选择普通预约登记类型；历史记录仅提示，不自动判定）
→ 提交真实预约
```

### 页面和入口

- 新增：`patient-miniapp/miniprogram/pages/booking-flow/`
- 首页“预约挂号”进入新流程；首页“我的预约”仍进入现有 `real-booking` 管理页。
- 门店页“真实预约”带 `branch_id` 进入新流程。
- 首页下次就诊和真实预约管理仍进入 `real-booking`；改约、取消、签到、协调答复继续由原页面负责。
- `real-booking` 不复制提交逻辑；新页面复用 `createRealBookingApi`、`authFlow`、`patientSession` 和幂等键逻辑。

### 步骤状态

- 返回上一步：保留门店；项目步骤返回门店清空项目/日期/时段；时间步骤返回项目清空日期/时段。
- 改门店：清空项目、日期、时段、医生和就诊类型。
- 改项目：清空日期、时段、医生和就诊类型。
- 切换账号/退出：按 `patientSession.revision` 清空旧账号的项目、日期、时段和就诊类型，保留可公开浏览的门店入口。
- 登录/建档返回：登录页 `returnTo=booking-flow` 使用 `navigateBack`；注册/绑定页完成后返回两层，回到原预约流程，重新读取授权数据。
- 409：清空已选时段、回到时间步骤、重新读取 availability；不自动重复提交。

## 排班与数据语义

- 日期只来自 `/me/booking` 的 `availability` 实际返回日期，不补齐未返回日期，遵守 7 天政策窗口。
- 项目只来自当前门店排班关联的 `availability.item_id`，与 `services` join；不从全量服务目录或医生名录臆造。
- 日期卡片按 `slot` 的上海时间区分上午/下午。
- 有 slots：可约；有排班区间但 slots 为空：**已无可约时段**；该半天没有班次：**无排班**。
- 不显示“已满”，不显示余量/容量；文案明确“候选不预占、提交时服务端重新校验”。
- 无门店照片/坐标：只显示真实文本地址，不画地图/导航入口。

## 就诊类型

- 普通预约在确认页由用户明确选择“初诊／复诊”登记类型；没有默认值，未选择不能提交。
- `/me/records` 只用于提示是否存在历史就诊记录，**不再自动把有记录的人判为复诊**。
- 普通预约的后端只校验 `visit_type` 是“初诊／复诊”，**不校验普通复诊资格**；页面明确写“登记类型不代表系统已核验复诊资格”。
- 复诊计划关联预约是另一条受控规则：需从“复诊计划”入口发起、关联计划并由后端校验；普通预约页不声称具备该资格。
- 病历读取失败不影响选择登记类型，只显示提示；提交仍使用后端现有 `visit_type` 允许值。

## 截图

- 首页真实入口：`docs/screenshots/patient-ui/home-booking-entry-after.png`
- 新流程门店步骤：`docs/screenshots/patient-ui/booking-flow-branch.png`
- 新流程选门店后登录提示：`docs/screenshots/patient-ui/booking-flow-branch-login.png`

说明：本轮没有可用真实患者登录态，因此项目/日期/确认页的实际截图未生成；这些步骤由纯函数和页面单测覆盖，避免用假的排班截图冒充真实接口页面。

## 修改文件

- `patient-miniapp/miniprogram/pages/booking-flow/booking-flow.ts`、`.wxml`、`.wxss`、`.json`（新增）
- `patient-miniapp/miniprogram/services/booking-flow.ts`（新增派生纯函数）
- `patient-miniapp/miniprogram/app.json`
- `patient-miniapp/miniprogram/pages/home/home.ts`、`home.wxml`
- `patient-miniapp/miniprogram/pages/clinic/clinic.ts`
- `patient-miniapp/miniprogram/pages/index/index.ts`
- `patient-miniapp/miniprogram/pages/register/register.ts`
- `patient-miniapp/miniprogram/pages/bind/bind.ts`
- `patient-miniapp/__tests__/booking-flow.test.ts`（新增）
- `patient-miniapp/__tests__/booking-flow-page.test.ts`（新增）
- `patient-miniapp/__tests__/pages.test.ts`
- 本文件、审查稿与 `handoff.md`

未改真实 `real-booking` 的改约/取消/签到/协调逻辑；只把新的主预约入口接到新页面。

## 验证

```powershell
cd patient-miniapp
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
```

- `typecheck`：通过。
- `test:unit`：**20 个测试文件、149 项通过**。
- 微信开发者工具普通编译：通过。
- `cli preview`：通过，包约 2.0 MB（2,095,508 Byte）；只生成预览二维码，未扫码、未上传体验版/未发布。
- 页面单测覆盖：
  - 未登录只浏览门店、不请求 `/me/booking`；
  - 项目只来自当前门店 availability；
  - 上午/下午分组；
  - 无 slots 显示“已无可约时段”、无下午班显示“无排班”；
  - 历史记录只做提示、登记类型由用户选择且无默认值；
  - 返回、重选时段、隐藏页面后病历迟到响应不能写回；
  - 409 刷新并清空时段、只调用一次 create；
  - 换账号清空下游选择并保留门店；
  - 登录/注册返回原流程。

## 未验证项

- 真实患者登录、真实 `/me/booking` 和真实 `/me/records` 数据；项目/日期/确认页截图因此未生成。
- 真机触屏、窄屏和字体放大下的四步流程、长医生名和长项目名。
- 真实服务端 409、网络超时和跨账号实际切换。
- 真实 7 天窗口与当前环境排班数据的实际日期覆盖；本轮没有查询或修改云端/数据库。
- 没有上传或发布版本；Codex 复核前不要上传新的体验版。
