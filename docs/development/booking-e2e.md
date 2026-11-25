# 真实预约双端联调记录

日期：2026-10-29。目的：把已有真实后端能力接到可操作页面，验证“管理端排班 → 患者可约时段/预约 → 管理端同一预约 → 患者改约/取消 → 双端/数据库一致”。

## 审计结论

- 管理端 `/scheduling`、`/appointments` 已调用真实接口，不使用演示状态。
- 患者端 `pages/real-booking` 已调用 `/me/booking`、`/me/appointments`、改约和取消真实接口；`pages/real-records` 同样为真实接口。
- 缺口：两端原本各自在前端按排班起止生成时段，没有使用后端营业区间/资格/服务范围。本批已改为后端 `/me/booking` 与 `/staff/booking` 返回 `availability`。
- 管理端营业区间配置已有 `/staff/branches` 页面（BranchesView）；医生资格由 StaffMembersView 录入/审核；本批 E2E 使用隔离合成数据预置这些配置，没有把配置页当作已完成的全流程验收。

## 隔离联调数据

使用独立数据库 `a_dental_e2e_64d7168f`（完成后删除），不连接原开发库。合成数据包括：

- 门店 `E2E 云栖店`，营业、已核验；
- 营业区间 2026-11-02 09:00–12:00、13:00–18:00；
- 牙医 `E2E 牙医`，在岗、本店任职已核实、`dentist_practice` 有效；
- 服务 `初诊检查` 30 分钟、`牙周维护` 60 分钟，门店/医生范围开放；
- 两个已发布排班 09:00–12:00、14:00–18:00；
- 牙椅 `E2E 牙椅 1`；
- 前台、负责人、患者真实账号/会话；患者绑定 mock 微信 openid。

这些是测试数据，不是生产种子，也不写入迁移。可复现 SQL 见 `docs/development/e2e-booking-seed.sql`（仅可对刚迁移的空库执行）。

## 实际操作

1. 负责人 `e2e_owner` 打开 `/scheduling`，读取到真实门店、医生、服务和已发布排班。
2. 前台 `e2e_frontdesk` 打开 `/appointments`：
   - 时段下拉只显示后端 availability：09:00–11:30，不含 12:00/12:30 午休缺口；
   - 为 `E2E 患者乙` 创建 2026-11-02 09:00 预约，得到 appointment 1；
   - 页面重新读取并显示该预约。
3. 患者 mock 登录（code `mock-code-bound`）：
   - `/me/booking` 返回 availability；
   - 患者创建 2026-11-02 14:00 预约，得到 appointment 2；
   - 改约到 14:30，返回 original appointment 2（已改约）和 replacement appointment 3（previous_id=2）；
   - 患者取消 appointment 3，状态已取消、version 2。
4. 前台 `/appointments` 再次读取：
   - 看到 appointment 1、2、3；
   - appointment 3 显示“由预约 2 改约”；
   - appointment 3 状态已取消；
   - 管理端与数据库状态一致。
5. 患者端真实页面未在微信开发者工具/真机运行；本批仅完成 API 级联调和可执行的前端类型/单测，不能替代小程序验收。

## 测试与截图

- 管理端真实浏览器：`docs/screenshots/e2e-admin-flow-final.png`
- 管理端排班页：`docs/screenshots/e2e-admin-scheduling.png`
- 管理端首次前台预约：`docs/screenshots/e2e-admin-booking.png`
- 后端全量：**352 项通过**
- 患者端：typecheck 通过，**75 项测试通过**
- 管理端：typecheck、**216 项测试**、build 通过
- OpenAPI：新增 `/me/booking` 说明，`openapi-spec-validator` 通过；未改新增端点/权限/迁移

## 剩余缺口

- 患者小程序未在开发者工具/真机验收；未提供 AppID。
- availability 是只读候选快照，已排除医生既有预约和全部可用牙椅的维护/占用，但仍不预占；提交冲突后重新读取。改约候选不忽略源预约，属保守边界。
- 管理端配置页已有营业区间、资格录入，但本批未在浏览器中逐步操作这些配置页；合成数据由隔离 SQL 预置。
- 未验证微信真机网络中断/重试；幂等键保留由既有单测覆盖。
- 未改 Word、任务勾选，未提交/推送 Git。


## 定向修复后的补充（2026-10-29）

- 管理端 availability 按授权门店过滤；患者列表仅返回授权门店已有预约业务关联的患者。隔离测试中的跨店患者不出现在响应。
- 候选已排除医生活动预约、全部可用牙椅的维护/占用；预约后 09:00 从管理端时段消失，取消后恢复。浏览器脚本实测：创建前含 09:00，创建后不含，取消后重新含 09:00。
- 截图：`docs/screenshots/e2e-refresh-restored.png`。
- 患者冲突刷新：上一版交接曾写“已刷新”但源码实际未接；本批已接上并用真实 409 页面测试验证，保留修正记录。
- 双端候选值保留后端原始 ISO；创建和改约测试确认原值透传。
