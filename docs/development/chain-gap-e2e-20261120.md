# 已实现主链路隔离浏览器验收（2026-11-20）

> 本页只记录**本轮可复现的隔离环境实机操作**。不是云端发布验收，不是真实微信/真机验收，也不是对历史 `tasks.md` 未勾选项的重新判定。接口存在、页面接线、隔离浏览器验收、云端人工验收、真机验收分层列明。

## 环境与边界

- 全新隔离 PostgreSQL 18.6：`127.0.0.1:55432`，数据库 `chain_gap_e2e`；未导入 `deploy/teacher-demo-seed.sql`。
- 当前源码构建的 Spring Boot jar；`local` profile；`APP_SEED_ENABLED=false`、`MOCK_WECHAT_ENABLED=false`。
- 独立管理端 Vite 开发服务器：`127.0.0.1:5173`，通过同源 `/api` 代理到 `127.0.0.1:58080`。
- 仅使用虚构组织、门店、员工、患者、服务和排班；预约编号从 1 开始，**没有读取或操作云端预约 941**。
- 为建立空库登录入口，仅在隔离库中手工插入最少虚构账号/角色/门店/服务/排班数据；没有运行教学种子或既有一次性启动脚本，没有写回任何现有数据库。未新增默认密码或公网初始化接口。

## 步骤与结果

### 1. 前台检索/选择用户、代预约、签到

使用 `frontdesk_chain` 在真实浏览器 `/appointments`：

1. 输入虚构患者甲姓名和手机号，勾选“已在现场核验”，执行“精确检索档案”。
   - 结果：找到 1 个候选，显示脱敏姓名/尾号，不展示完整手机号。
2. 点击“核验并选择”，填写虚构确认依据和目的，创建预约。
   - 结果：页面显示“预约 1 已创建并占用真实排班时段”；数据库 `appointment` 为患者甲 10:30，状态 `已确认`。
3. 用患者乙重复检索/选择/创建 11:00。
   - 结果：页面显示“预约 2 已创建”；数据库为患者乙 11:00，状态 `已确认`。
4. 分别对两条预约执行前台签到。
   - 首次没有配置窗口且未填例外理由，服务端返回 422 `BUSINESS_BASIS_REQUIRED`；页面原先只显示“请提供可核验的线下办理依据”，没有指出应填写“超窗/未配置窗口理由”。
   - 本轮已做限定修复：`AppointmentWorkbenchView.vue` 将例外理由标明为“无窗口时必填”，并把该业务错误映射为明确提示；新增单测通过。
   - 填入虚构例外理由后签到成功：页面显示候诊号 1、2，数据库 `queue_ticket` 为两条 `候诊`，`checkin_channel=前台`。

### 2. 医生读取候诊、开始接诊、完成虚构案例、创建复诊计划

使用 `dentist_chain` 在真实浏览器 `/clinical`：

- 页面初始显示 2 条候诊/接诊中预约（患者甲 10:30、患者乙 11:00）。
- 对患者甲：开始接诊 → 写入虚构临床记录 → 完成就诊。
- 对患者乙：开始接诊 → 写入另一份虚构记录 → 完成就诊。
- 切到“复诊计划”：来源只能选本人已完成就诊；填写虚构目的和日期，创建计划。
- 实际数据库：
  - `appointment` 1、2 均为 `已完成`，version 4；
  - `visit_record` 2 条；
  - `followup_plan` 1 条，状态 `待协调`，来源为患者甲的 visit；
  - 页面“已记录就诊 2”“复诊计划 1”与数据库一致。
- 期间重新构建了当前源码 jar；此前运行的是旧 jar，导致临床 overview 缺字段、页面显示“临床数据无法解析”。换用当前源码 jar 后页面正常，未改临床状态机或接口契约。

### 3. 店内消息互发/读回

- `frontdesk_chain` 在 `/internal-messages` 向本店医生发送虚构工作消息。
- `dentist_chain` 登录后读取同一消息，并回复虚构确认。
- 前台重新登录读取，页面同时显示两条消息；数据库 `internal_staff_message` 有 2 条，发送账号分别为前台和医生。
- 消息页明确说明消息不改业务状态，没有伪造“已送达/已读”状态。

### 4. 负责人服务范围 409 与刷新

- `owner_chain` 在 `/scheduling?tab=scope` 选择医生，取消已配置的唯一服务项目并填写虚构核实依据。
- 服务端返回冲突：`医生已有相关未结束排班，暂不能移除该服务范围`。
- 页面显示错误；重新加载后复选框仍为选中，数据库 `dentist_service` 仍为 `dentist_id=3,item_id=1,enabled=true`。
- 这是隔离库内验证，不改变服务端范围校验。

### 5. 签到时窗配置与短效签到码

- 在负责人门店页“到店签到设置”中，页面读取到版本 1，开放扫码签到关闭、窗口分钟为空。
- 实际保存时发现组件对 `type=number` 输入调用 `.trim()`，抛 `early.value.trim is not a function`，页面没有保存请求；这是真实页面缺陷。
- 修复 `CheckinConfigPanel.vue` 为 `String(value ?? '').trim()` 后，实际保存成功：版本 2，`self_checkin_enabled=true`、提前/迟到 30 分钟、步长 30。
- 前台预约工作台“生成／刷新签到码”成功显示短效二维码，页面显示剩余 60 秒；图片为 data URL，不包含患者资料。
- 本轮**没有**用真实微信/真机扫码，也不把二维码页面验收当成真机扫描。

## 关键请求（浏览器实际触发）

| 角色 | 请求 |
| --- | --- |
| 前台 | `GET /api/v1/staff/booking`，`GET /staff/patients?branch_id=1&name=...&phone=...`，`POST /staff/patients/{id}/selection`，`POST /staff/appointments`，`POST /staff/appointments/{id}/checkin` |
| 医生 | `GET /staff/clinical`，`POST /staff/appointments/{id}/start-visit`，`POST /staff/visits`，`POST /staff/followup-plans` |
| 前台/医生 | `GET /staff/internal-messages/contacts`，`GET /staff/internal-messages?branch_id=1&peer_staff_id=...`，`POST /staff/internal-messages` |
| 负责人 | `PUT /staff/scheduling/staff/3/services`（409 冲突），`PUT /staff/branches/1/booking-config`（保存窗口），`POST /staff/branches/1/checkin-code`（前台短效码） |

## 分层证据

| 层次 | 本批结论 |
| --- | --- |
| 接口存在 | 预约/签到、临床、复诊计划、店内消息、门店签到设置、医生服务范围接口均实际调用成功 |
| 页面接线 | 上述页面在真实 Edge 浏览器中实际点按；不是仅看源码 |
| 隔离浏览器验收 | 已完成前台→医生→消息→负责人范围/签到设置的本地闭环 |
| 云端人工验收 | **未做**；未访问或修改云端预约 941 |
| 真机/真实微信 | **未做**；本批无真实微信会话、真机扫码或体验版 |
| 自动测试 | 见下；不能替代云端或真机验收 |

## 修改文件

- `admin-web/src/views/AppointmentWorkbenchView.vue`：签到例外理由标签/`BUSINESS_BASIS_REQUIRED` 提示。
- `admin-web/src/components/CheckinConfigPanel.vue`：数字输入 `.trim()` 崩溃修复。
- `admin-web/src/__tests__/AppointmentWorkbenchView.test.ts`：签到错误映射回归。
- `admin-web/src/__tests__/CheckinConfigPanel.test.ts`：数字输入保存回归。
- `docs/development/chain-gap-e2e-20261120.md`：本页。

未修改后端、OpenAPI、数据库迁移、constitution/tasks/Word；未提交或推送 Git。

## 截图

- `docs/screenshots/chain-login.png`
- `docs/screenshots/chain-owner-home.png`
- `docs/screenshots/chain-frontdesk.png`
- `docs/screenshots/chain-frontdesk-search.png`
- `docs/screenshots/chain-frontdesk-selected.png`
- `docs/screenshots/chain-frontdesk-created.png`
- `docs/screenshots/chain-frontdesk-two.png`
- `docs/screenshots/chain-checkin-dialog.png`
- `docs/screenshots/chain-frontdesk-checked-in.png`
- `docs/screenshots/chain-doctor-plans.png`
- `docs/screenshots/chain-messages-front-sent.png`
- `docs/screenshots/chain-messages-doctor.png`
- `docs/screenshots/chain-messages-front-readback.png`
- `docs/screenshots/chain-scope-conflict.png`
- `docs/screenshots/chain-checkin-config.png`
- `docs/screenshots/chain-checkin-config-saved.png`
- `docs/screenshots/chain-checkin-code.png`

## 测试命令与结果

管理端：

```powershell
cd admin-web
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
```

- `typecheck`：通过。
- `test:unit`：**27 个测试文件、264 项通过**。
- `npm run build`：通过（Vite 构建完成；仅提示主 chunk 体积较大，未改代码分包策略）。

患者小程序（同一源码基线，无本批小程序改动）：

- `typecheck`：通过。
- `test:unit`：**16 个测试文件、124 项通过**。

后端隔离 PostgreSQL 全量：

- **31 个测试类、422 项通过**；`NewBusinessIntegrationTest` 109 项、`SchemaMigrationTest` 14 项。该后端基线未在本轮改源码；本轮只重新构建并启动当前 jar。

## 数据库/清理

已停止隔离后端、Vite 和独立 PostgreSQL；未删除任何现有数据库或教学数据，未访问云端。隔离簇目录保留在 `backend/target/chain-gap-e2e-20261120/` 供审计，不进入版本控制。浏览器临时 Storage 未用于本批。

## 仍未打通/建议

- 云端公网角色验收、真实微信/真机扫码、体验版请求域名未做。
- 真实财务角色、库管角色、支付/税控/通知成功状态未做；没有新增这些功能。
- 复诊计划更正版本、暂不复诊答复、临时替诊等仍未实现，不能把当前基础计划当完整复诊流程。
- Word 只建议在后续人工整理时补充本页证据和“接口/页面/浏览器/云端/真机”分层表述，本轮未改图表。

## Word 建议修改点（未改）

- 在预约/签到流程图中标注：前台代预约使用患者选择凭证；签到窗口未配置时必须填写人工例外理由；短效签到码只证明当次门店签到，不等于真机验收。
- 在临床/复诊流程图中区分“预约已签到 → 医生开始接诊 → 完成 visit_record → followup_plan”，不要把复诊计划画成已自动预约。
- 在店内沟通图中标注“仅同店授权，消息不改变预约/临床状态”，不要给消息增加“已送达/患者已读”符号。
- 在部署/验收图中保留“云端人工验收”“真实微信/真机验收”未完成的明确标记；不要用隔离浏览器证据替代。

## Codex 复核补充（2026-11-20）

- 核对了两处页面修复与服务端规则：无签到窗口且未填例外理由时，服务端返回 `BUSINESS_BASIS_REQUIRED`；数字输入在 Vue 中可能成为数值，保存前统一转为字符串可避免 `.trim()` 抛错。保留原有服务端状态、权限与版本校验。
- 复核隔离走查记录、截图和测试报告；这批证据支持“隔离浏览器中的虚构业务链路已走通”，不等同于云端多角色或微信真机验收。当前测试报告中 `SchemaMigrationTest` 为 **14 项**；本页上方的 13 项是早先执行记录。
- 管理端当前源码类型检查、**27 个文件 264 项单测**及构建通过；258/259 是较早源码基线的运行数。
- 两处页面修复已单独构建并更新云端网页容器，保留原网页镜像标签 `deploy-web:before-ds-ui-fixes-20261120`；API 与数据库容器未重建。更新后公网首页和公开门店接口均返回 200，云端预约 941 只读核对仍为“接诊中”、版本 3。云端已登录后的签到窗口设置和弹窗提示尚未由 Codex 实际点按验收。
