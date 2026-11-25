# 前台新患者选择后端

日期：2026-10-30。实现：`V015__patient_selection.sql`、`PatientReceptionService`、`PatientReceptionController`、`BookingService`。管理端预约页现已接入检索、建档、选择凭证流程；真实浏览器联调仍待复核。

## 接口

- `GET /api/v1/staff/patients?branch_id=&name=&phone=`
  - 精确姓名 + 规范化电话；单一机构内最多 10 个最小候选；
  - 只返回 `patient_id`、脱敏姓名、电话后四位、出生年份、匹配类型；
  - 不返回过敏、完整电话、病历、账号或历史；
  - 需要 `patients.lookup`；门店营业/核验、员工本店有效任职；
  - 每账号+门店 30 次/分钟，超限 429；
  - 审计只写结果数量/匹配类型，不写姓名电话。
- `POST /api/v1/staff/patients`
  - patient-only 建档，不创建 account/wechat；
  - 需要 `patients.create` 和 `Idempotency-Key`；
  - 必须提交 `verification_method`（`visit_evidence|id_document`）、`verification_result=confirmed`、`in_person=true`；
  - 同名/同电话冲突转人工；共用电话仅允许 `duplicate_resolution=shared_phone` + 证件核验；同名异电话仅允许 `same_name` + 证件核验；
  - 服务端重算后，`duplicate_resolution` 与实际冲突不符即拒绝，不能自报一个不存在的冲突类型；
  - 首次返回明文 `selection_token`；同键重放只返回 `token_shown_once=true`，不重显 token，不重复建档。
- `POST /api/v1/staff/patients/{id}/selection`
  - 对既有患者重新核验姓名+规范化电话，记录实际线下核验方式/结果并签发 token；
  - **由服务端重新计算精确匹配数、同电话、同名候选，决定允许的 resolution**；不能接受客户端自报 `single_exact`；
  - 姓名电话只用于定位候选，不能作为“已核实本人”；
  - 需要 `patients.lookup`；token 只显示一次。
- `POST /api/v1/staff/appointments`
  - 新增必填 `patient_selection_token`；
  - 服务端校验 token 摘要、操作账号、员工、目标门店、患者、有效期；
  - 目标门店必须等于排班门店；
  - 成功预约同一事务条件更新 `consumed_at/appointment_id`，失败回滚不消费；
  - 同一 Idempotency-Key 成功重放先走既有幂等结果，复核当前权限和预约可见性，返回原预约，不要求旧 token 有效；
  - 同一 token 不同幂等键并发最多一笔成功。

## 数据与锁

`patient_selection` 只存 SHA-256 token 摘要；不存姓名、电话、证件号。字段包括操作账号、员工、门店、患者、来源、核验方式/结果、resolution、有效期、消费时间、预约引用。

创建患者时对规范化电话取事务级 advisory 锁，再检查同名/同电话；预约时对选择凭证行 `FOR UPDATE`，成功写入预约后再条件核销。失败事务整体回滚，凭证保持可重试。

## 测试

`NewBusinessIntegrationTest` 新增：

- 精确检索最小字段；新患者建档后预约；无账号创建；
- 跨账号 / 跨目标门店 / 跨患者 token 拒绝；
- 过期 token、同 token 不同幂等键并发、失败回滚后可复用；
- 撤权后同键重放返回 403；
- 建档同键重放不重显 token、重复建档 409、并发建档一胜一败；
- 缺少 token 422；
- 先前已有的待确认不占号测试继续通过。

结果：`NewBusinessIntegrationTest` **55 项通过**；后端全量 **364 项通过**。

## 未做

- 管理端预约页已携带 `patient_selection_token` 提交。后端仍以凭证和权限校验为准；浏览器与真实后端联调尚未完成。
- 小程序患者自助预约不需要 staff selection token，不受影响。
- 微信开发者工具/真机未运行。
- Word 未修改。


## 2026-10-30 resolution 自报绕过修复

`PatientReceptionService.select` 在签发凭证前重新查询候选并计算：

- 唯一精确匹配且无同电话/同名冲突：只允许 `single_exact`；
- 多个精确匹配：只允许 `manual_candidate`；
- 存在同电话冲突：额外允许 `shared_phone`；
- 存在同名冲突：额外允许 `same_name`；
- 请求的 resolution 不在允许集合：409 `PATIENT_MATCH_REQUIRES_VERIFICATION`，不插入 selection、不写成功审计。

建档侧 `duplicate_resolution` 也按实际 `phoneMatch/nameMatch` 重算；没有对应冲突却携带 `shared_phone`/`same_name` 会被拒绝。新增真实 PostgreSQL 测试：伪造 `single_exact`、共用电话冒充唯一匹配、建档伪造 resolution 均无患者/凭证残留；合法 `manual_candidate`/`shared_phone` 可签发。
# 管理端接线进度（2026-11-02）

管理端预约工作台已接入精确检索、线下核验、patient-only 建档、既有档案选择和短时选择凭证；创建预约时提交 `patient_selection_token`。建档同键重放不重显凭证，页面提示重新检索核验。此处仅完成前端接口接线和自动化测试，尚未以真实员工会话在浏览器逐步验证完整前台流程，不能把它记为端到端验收。

2026-11-16 补充：前台预约页的工作台读取、患者检索、选择和建档按会话代次核对迟到响应；切换账号会清除旧预约、候诊和患者资料，页面卸载后不写回。发出预约请求后的旧响应不能在新会话显示成功。前台真实浏览器端到端流程仍待隔离教学库验收。

