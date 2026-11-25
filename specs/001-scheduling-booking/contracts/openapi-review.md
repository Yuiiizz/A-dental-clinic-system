# OpenAPI 契约评审（T006 交付说明，含 Codex 复核修正与 spectral 官方规则集校验）

> 历史评审快照：下文的“80 个操作”“仅契约、未写应用代码”和警告数均指 2026-10-21 的 T006 阶段，不描述当前交付范围。后续接口持续增加；2026-11-12 待联系清单接入时，`openapi.yaml` 有 108 条路径、131 个 operationId，Spectral 校验 0 错误、43 警告。当前实现范围以代码、最新契约和各业务开发说明为准。

**范围**：将 `contracts/api.md` 具体化为可校验的 OpenAPI 契约 `contracts/openapi.yaml`。仅契约，未写应用代码、未实现接口。
**日期**：2026-10-21。

## 一、接口覆盖

`openapi.yaml` 共 **80 个操作（operationId）**，覆盖 api.md 全部方法+路径行。按六组（对应 T006 六个子步骤与契约 tags）分布：

| 组 | tag | 操作数 |
| --- | --- | --- |
| 身份 | 身份与会话 | 16 |
| 基础配置与排班 | 基础配置与排班 | 24 |
| 预约 | 公开查询与患者预约 | 15 |
| 协调 | 协调 | 14 |
| 签到 | 签到 | 4 |
| 政策 | 政策 | 7 |

合计 **80**；api.md 的表式行按 method 展开后合计 80 个操作。

## 二、验证结果（PyYAML 6.0.3 结构级 + spectral 6.17.0 官方规则集）

| 检查项 | 方法 | 结果 |
| --- | --- | --- |
| YAML 重复键 | 自定义 loader（`safe_load` 会静默覆盖，故重写映射构造器抛异常） | 无重复键 |
| YAML 语法 + OpenAPI 结构 | 解析并校验 `openapi: 3.0.3`/info/servers/paths/components | 通过 |
| 引用解析 | 遍历全部 `$ref`（schemas/responses/parameters/securitySchemes）比对组件定义 | 无未解析 |
| operationId 唯一 | 集合去重比对 | 无重复 |
| 路径参数一致性 | 逐路径比对 `{占位符}` 与 path 参数（name 完全一致、required=true、in=path、无重复、无多余） | 通过（修正 2 处，见下） |
| 方法+路径覆盖 | 与 api.md 逐行核对 | 80/80 |
| 幂等/版本逐接口 | 54 个变更操作逐一分类断言（见「三」清单） | 全部落实 |
| 认证分支 | WechatLoginResult oneOf + discriminator 断言 | 通过 |
| OpenAPI 官方规则集（spectral 6.17.0 `spectral:oas`） | `npx spectral lint --ruleset .spectral.yaml` | 0 错误、20 警告（见第六节） |

**本轮修正的两处路径参数错误**：
- `PUT /staff/staff-members/{id}/branches/{branch_id}`：原第二个参数名误用 `id`，已改为 `branch_id`（`BranchPathId`）。
- `GET /public/doctors/{id}`：原参数名误用 `doctor_id`，已改为 `id`（`DoctorPathId`）。

**诚实记录（已做/未做）**：
- **spectral 6.17.0 官方规则集机器校验已执行**（项目独立目录锁定版本，未全局安装）：0 错误、20 警告，详见第六节。0 错误表明无 OpenAPI 3.0.3 结构/引用/规范错误，但**不等于接口已实现或业务测试通过**。
- 未生成契约测试；未在 PostgreSQL 执行任何 SQL（排他约束属迁移任务，不在本契约范围）。

## 三、幂等与并发控制（逐接口清单）

54 个变更操作（80 − 26 个 GET）分为三类，已在 OpenAPI 中实际声明（`Idempotency-Key` 头参数 / 请求体 `expected_version`）：

**A. 幂等键必填（21，新建/签发/生成，目标无 version 列或无需乐观锁）**
staffIssueBindingToken、staffReissueBindingToken、staffCreatePatient、staffCreateBranch、staffCreateStaffMember、staffCreateServiceItem、staffCreateResource、staffCreateAccount、staffCreateSchedule、staffCreateBookingPolicy、staffCreateContact、staffCreateDentistUnavailability、staffCreateResourceUnavailability、staffCreateCheckinCode、meCreateAppointment、staffCreateAppointment、staffCreateChangeProposal、meAppealRestriction、staffResolveResourceUnavailability（无 version 列，幂等键+状态前置）、staffReviewBookingEvent（无 version 列，幂等键+状态前置）、staffSetBranchService（无 version 列，调价追加历史行，幂等键防重复）。

**B. 幂等键 + expected_version（22，修改既有记录，目标表有 version 列；staffSetRoleScopes 复用 account.auth_version）**
meUpdateProfile、staffUpdateBranch、staffSubmitBranch、staffVerifyBranch、staffDisableBranch、staffReopenBranch、meCancelAppointment、meRescheduleAppointment、meRespondToChange、meCheckin、staffPublishSchedule、staffSuspendSchedule、staffConfirmAppointment、staffCancelAppointment、staffRespondToChange、staffApplyChange、staffCheckin、staffNoShowReview、staffPublishBookingPolicy、staffUpdateBookingConfig、staffUpdateOpeningIntervals、staffSetRoleScopes。

**C. 合理例外（11，无幂等键、无版本）**
authWechatLogin、authStaffLogin（登录）；authPasswordChange、authLogout（会话撤销，天然幂等）；authPatientRegister、authPatientBind（registration_token/binding_token 单次消费，防重）；meSetDoctorPreference、meDeleteDoctorPreference、staffSetBranchAssignment、staffSetServiceScope、staffDisableAccount（set 型 PUT/DELETE 或状态置位，同值重复无副作用；staff_branch/dentist_service 无 version 列，disable 由服务端递增 auth_version）。

**版本字段统一**：请求体统一 `expected_version`（整数，≥1），不再混用 `version`。目标表无 version 列的状态迁移已显式改用「幂等键+状态前置条件」并注明理由。

## 四、设计补充与理由

1. **服务端计算字段不可写**：`confirmed_dentist_id`、`ends_at`、`state`、`coordination_state`、resource 分配、`reviewed_by`、`resolved_by`、`resolved_at`、`approved_by`、`approved_at` 只出现在响应；创建/修改请求不接收。
2. **未知字段拒绝**：所有请求体 `additionalProperties: false`，服务端对未声明字段返回 422 `UNKNOWN_FIELD`（api.md 已同步该规则）。
3. **认证分支明确**：`WechatLoginResult` 用 `oneOf + discriminator(kind)` 分 `WechatSession`（必填 session_token/expires_at）与 `WechatRegistration`（必填 registration_token/registration_expires_at）两分支，消除空对象与双凭证。两分支均 `additionalProperties: false`（第六轮补）：`WechatSession` 拒绝 `registration_token`/`registration_expires_at`，`WechatRegistration` 拒绝 `session_token`/`expires_at`，仅 `oneOf+discriminator` 不足以禁止混入另一分支凭证字段。
4. **自助注册与前台建档分离**：`PatientSelfRegisterRequest`（`registration_token` 必填，消费后失效）与 `StaffCreatePatientRequest`（无注册凭证，员工权限即授权）两个 DTO，前台不接受注册凭证替代授权。
5. **DTO 完整性**：补齐员工资格（credential 嵌套：credential_type/certificate_no/valid_from/valid_to/no_expiry_confirmed）、门店服务（enabled/auto_bookable/current_price/reason）、资源（category_id/resource_type/asset_id，operating_state 服务端默认「可用」）、排班（schedule + schedule_service 的 services[{item_id,duration_minutes}]）、政策（version_no 服务端按 UNIQUE(branch_id,version_no) 分配）。各字段带长度、可空、必填、枚举。不镜像 48 表、不虚构未开放功能（叫号/诊疗/收费/开票不出现）。
6. **鉴权三分**：患者不透明 Bearer（仅存摘要）、员工 `A_CLINIC_SESSION` Cookie（HttpOnly+Secure+SameSite=Lax）+ `X-CSRF-Token` 头（名称已定为契约约定，不再作未决）、公开接口 `security: []`；`/auth/logout` 支持患者或员工二选一。
7. **servers 相对地址**：`/api/v1`，前端与网关按部署环境拼接域名。
8. **ID 字符串、时间带时区**、分页 `{items,next_cursor}`（limit 1–100 默认 20）、错误体 `{code,message,request_id,field_errors?}`。
9. **资源禁用/回填规则落地**：resolve 的 `resolved_by` 取会话、解除后仍查其他有效禁用；拒绝回填过去时段（starts_at 早于当前时间 422）。

## 五、真正未决事项（非本次可解）

1. **无官方 OpenAPI schema 机器校验**：环境无 spectral/redocly 等，未安装；建议进入实现阶段前在 CI 加官方校验器再视为绑定契约。
2. **错误码登记表未独立成文**：`Error.code` 现列 POLICY_CHANGED、SLOT_UNAVAILABLE、IDEMPOTENCY_CONFLICT、VERSION_CONFLICT、UNKNOWN_FIELD 等示例，完整错误码清单待与契约测试一并定稿。
3. **未开放接口仍不虚构**：叫号、临床诊疗、收费结算与开票留后续阶段，本契约不占位。

## 六、spectral 官方规则集机器校验（第七轮，2026-10-22）

工具：`D:\DevTools\openapi-validator`（项目独立目录，锁定 `@stoplight/spectral-cli@6.17.0`，未全局安装），规则集 `.spectral.yaml`（`extends: spectral:oas`）。

可重复命令：

```bash
cd D:/DevTools/openapi-validator
npx spectral lint --ruleset .spectral.yaml -f json \
  D:/GitHub/A-dental-clinic-system/specs/001-scheduling-booking/contracts/openapi.yaml
```

结果：**0 错误、20 条警告（severity 1）**，分类如下：

| 规则 | 条数 | 性质 | 处置 |
| --- | --- | --- | --- |
| `info-contact` | 1 | 风格：info 缺 `contact` | 记录，不改（非阻断） |
| `operation-description` | 15 | 文档：15 个 GET 操作缺 `description` | 记录，不改 |
| `oas3-unused-component` | 4 | 质量：组件定义后未被引用（dead components） | 记录，不改（业务语义） |

4 个未引用组件（经 grep 确认全文件无引用）：

- `schemas/BookingEventKind`（枚举 临近取消/核实未到）
- `schemas/BookingEventState`（枚举 待核实/计入/豁免）
- `schemas/ResourceUnavailableState`（枚举 有效/已撤销）
- `schemas/ResourceBookingState`（枚举 有效/已释放）

上述组件是否应接线到对应 DTO（履约事件、资源禁用/资源占用）或删除，属业务语义决策，本轮只记录、不自行重设计。**0 错误 = 无规范错误；20 警告均为非阻断的风格/质量建议；不因校验通过声称接口已实现。**


T025-B2：新增 staffRevokeCredential，操作总数85。独立Spectral检查0错误/20既有警告；新增资格撤销请求与历史动作revoke，未声称下游资格准入或协调已实现。
