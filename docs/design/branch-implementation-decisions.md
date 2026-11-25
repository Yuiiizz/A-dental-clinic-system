# T024 门店与营业区间：实施前决策核对

日期：2026-10-24。范围：仅设计核对，**未修改代码、契约、迁移、Word、图片、constitution、tasks 状态，未提交/推送，未运行全量测试**。本文件供 Codex 审定后，再发 T024 实现批。

> **2026-10-24 Codex 审定（以 `DEEPSEEK_T024A.md` 为准）**：批准 `branches.create`（仅 owner）与 `license_no_expiry_confirmed`；实际创建须同时满足组织唯一负责人、owner 权限、在岗/已改密，manager_id 首期等于创建者，启动授权只授新店；账号维护锁 734192014 必须先于幂等锁并重新核验。31 天是**单次编辑范围**，不是排班提前上限。营业区间冲突按“修改后覆盖是否不足”判断，扩大/同值/仍完整覆盖已有安排不应拒绝。本文件下面的旧建议若与 T024A 冲突，以 T024A 为准；T024A 只实现创建/列表/详情，不实现状态动作和营业 PUT。
>
> **2026-10-25 T024-B 实施边界**：已实现 PATCH 与 submit/verify/disable/reopen；PATCH 只更新请求出现字段、拒绝服务端字段；营业中执业地址/许可证/有效期变更须先停用；许可事实变化清除核验/无期限确认；停用检查未结束已发布班次和活动预约并返回类型化 impact；版本、状态、审计、幂等同事务。T024-B 复核后补充“先鉴权、再锁 branch、锁后复查”，审计覆盖地址/代码/许可核验事实及清空值。营业区间 PUT、前端和排班仍未实现；T024 整体保持未完成。
>
> **2026-10-25 T024-C 实施边界**：已实现营业区间 GET/PUT；branch.version 作为集合聚合版本；日期范围两端包含、最多31日；今天已开始区间只读；覆盖按替换后完整并集判断，空列表闭店；同值不递增版本/不写审计。契约原文字“branch_opening_interval.version”与多行语义冲突，按审定改为 branch.version。前端和排班仍未实现，T024 保持未完成。

## 结论摘要

- **建店权限必须独立于既有门店编辑权限**。推荐新增 `branches.create`，仅授予组织负责人（`organization.responsible_staff_id`）的 owner 角色；同时要求安装实例恰有一个当前经营主体（organization），不允许客户端指定 `organization_id`。sysadmin 不因身份管理权限获得建店权。
- 新建门店写入 `草稿`；创建事务只给**该店**的 manager（首期要求等于创建者）建立 `staff_branch` 和其 owner 角色的 `role_branch_scope`，作为有限、可撤销的启动授权，不产生跨店/跨组织无限授权。
- `PATCH` 只改档案字段，不接收 `state/verified_by/verified_at`。状态通过明确的 submit/verify/disable/reopen 动作转换；核验人、时间取会话和 `Clock`。
- 许可有效期为空不能视为永久有效。推荐新增 `branch.license_no_expiry_confirmed` 布尔列；为 false 时必须提供有效 `license_valid_to`，为 true 时仍需明确核验依据并写审计。
- 营业区间采用**日期范围替换**，范围外不动；`expected_version` 复用 `branch.version` 作为聚合版本；每次成功写入递增 branch.version。无需新增版本表，代价是与门店档案编辑共用并发控制，冲突保守返回 409。
- 所有营业/停用变更先锁 `branch` 行，再检查未来班次和活动预约；默认有冲突即 409、零写入，不自动取消/改约。T027 排班发布和 T035 预约写入必须使用同一 `branch` 行锁。
- 现有 `visit_record` 已存在，不再为“接诊记录”新建重复表；本批不新增诊疗/收费模型。

## 一、决策表

| 问题 | 推荐结论 | 依据 | 最小改动 |
| --- | --- | --- | --- |
| 谁可创建门店、organization 如何确定 | 安装实例恰有一个 organization；创建者必须是其 `responsible_staff_id`，且当前在岗、已完成强制改密、owner 角色具有新建权限。`BranchRequest` 仍不接受 organization_id；零/多 organization 明确报错，不猜。 | `organization.responsible_staff_id` 已是负责人 FK；现有 `requireBranch` 需要既有 branch scope，不能用于新店；`spec.md` 要求负责人管理，`permissions.md` 要求不能仅凭 owner 或 sysadmin 扩大业务访问。 | 新增权限 `branches.create`（仅 owner）；`BranchService` 做 singleton organization 校验；不新增 organization HTTP 创建接口，organization 由本地/安装初始化或 T022 合成数据建立。 |
| 创建后谁可核验/配置、如何取得合法授权 | 首期 `manager_id` 必须等于当前创建者。创建事务在插入 `branch(state=草稿,version=1)` 后，只对该店插入/确认 `staff_branch(manager, enabled, verified_at=now)` 和 manager 的 owner 角色 `role_branch_scope(manager,owner_role,branch)`；审计记录启动授权。之后 manager 用 `branch.configure` 核验/配置。 | `role_branch_scope` 需已有 branch；不自动授权则新店无人可配置；只授一店、一个既有 owner 角色，边界有限且可撤销，不等于组织级无限授权。 | 同事务启动授权；若 manager 无有效 owner 角色/账号/任职，422 且整单回滚。后续再设计店长转移，不在本批。 |
| 状态转换 | `草稿 --submit--> 待核验 --verify--> 营业 --disable--> 停用 --reopen--> 待核验`。不允许跳跃；核验/开立/停用均需 `expected_version` 与幂等键。 | `branch.state` 四值已存在；现有 PATCH 无 state/DTO，不能继续用任意字段写状态；`spec.md` 要求未核验不能营业、有未来安排不能直接停用。 | 新增 detail GET 和状态动作端点；PATCH 仅档案字段；每次动作 version+1，写审计。 |
| 状态写入与核验人 | 客户端不得传 `verified_by/verified_at`；服务端取当前员工和 `Clock`。`verify` 请求只带 `expected_version`、许可有效期/无期限确认、`verification_basis`。 | `branch.verified_by/verified_at` 是服务端事实；`permissions.md` 要求记录操作者和时间；`AuthorizationService` 已有 `Clock` 注入。 | 新增状态动作 DTO；不允许通用 PATCH 写核验字段。 |
| 许可有效期空值 | 推荐新增 `license_no_expiry_confirmed boolean NOT NULL DEFAULT false`。为 false 时 `license_valid_to` 必填且不得早于当天；为 true 时必须有明确核验依据。仅 `license_valid_to IS NULL` 不能通过核验。 | `data-model` 写明空有效期“无期限另行核验”，但现有表没有确认标志；仅凭 null 无法区分“未核验”和“确认无期限”。 | V005 增一列；`BranchDetail`/verify 返回该标志；审计保留依据。若 Codex 不批准新列，替代是首期一律要求非空 `license_valid_to` 并拒绝 null。 |
| 营业区间集合语义 | 请求改为 `from_date/to_date + intervals + expected_version`，日期范围最多 31 天（含），替换该范围内全部区间；范围外不动。空 intervals 表示该范围闭店。`from_date` 不得早于今天（Asia/Shanghai），避免改写历史。 | 现有 PUT 只有一个 expected_version，却对应多行 interval.version，语义不成立；全量历史替换会误删范围外历史；日期范围可明确审计和冲突检查。 | 改契约请求/响应；按范围删除并重建，写 before/after 审计；不改表结构。 |
| 聚合版本 | 用 `branch.version` 作为 `expected_version`；成功替换/闭店后 branch.version+1。`opening_version` 在详情中等于 branch.version，不新增独立版本列/表。 | branch 已有 version；新增 opening set version 需要新聚合表，超出最小方案；共用版本的代价是档案编辑与区间编辑互相 409，但语义安全。 | 无迁移；文档明确“共用并发控制”；后续若有明显竞争再评估独立 opening version。 |
| 跨午夜/全天边界 | `starts_at < ends_at`；`valid_on` 必须是开始时刻在 Asia/Shanghai 的日期；`ends_at` 最晚可到次日 00:00:00（表示当日全天/24:00），超过次日零点必须拆成两日两行。 | 现有模型按 `valid_on` 分行；`[开始,结束)`；跨午夜拆两日；结束在次日零点不应被误判为跨日非法。 | 服务端按 Asia/Shanghai 解析 `OffsetDateTime` 后比较本地日期；返回 422 指出需拆日。 |
| 变更影响与事务 | 营业区间缩短/停用：锁 branch 行后查 `schedule`（草稿/已发布/暂停且区间相交）和活动 appointment（待确认/已确认/候诊中/接诊中且未来/区间相交）；有则 409 `BRANCH_HAS_FUTURE_COMMITMENTS`，零写入，不自动改约。 | `spec.md` FR-002/013；任务 T047 停诊协调在后续，T024 不能假装协调完成；不泄漏患者资料。 | 返回影响摘要 `{schedule_count, appointment_count, first_starts_at,last_ends_at,truncated}`，不返回姓名/电话/患者编号；上限 50/100 后标记 truncated。 |
| 共同锁协议 | 所有 T024 营业/停用/区间写入先 `SELECT ... FROM branch WHERE branch_id=? FOR UPDATE`；T027 排班发布、T035 预约写入也必须先取同一 branch 行锁，再检查状态/营业区间/资源/排班。不得新增全局业务锁。 | 仅“先查后写”会有并发空隙；现有身份锁 734192014 与门店业务无关，不能复用为全局锁；branch 行锁天然按门店串行，范围最小。 | 在 T024 服务中固定顺序；将“branch 行锁”写进 T027/T035 实施约束。 |
| 列表/详情权限 | 列表/详情都要求 `branch.configure` + 对应 branch 的 `role_branch_scope` + 有效 `staff_branch`；不同角色门店不能拼接；sysadmin 不自动获得。建店权限 `branches.create` 与编辑权限分开。 | `AuthorizationService.requireBranch` 已按同一 role_id 联合检查；`permissions.md` 明确不能合并角色；账号管理员只需后续受控身份查询，不扩权复用经营接口。 | 列表只返回有权限门店；详情 404 不泄露存在性；后续账号管理选择门店另立受控查询。 |
| 详情/列表字段 | 新增 `GET /staff/branches/{id}` 返回完整可编辑字段、state、version、verified_by/at、license_no_expiry_confirmed、opening_intervals/opening_version；列表项补 version/manager_id/state 等管理最小字段。 | 现有 BranchResult 只有 branch_id/code/name/state/version，列表无 version/manager；PATCH 无详情无法可靠编辑。 | 契约新增 BranchDetail/OpeningInterval；列表项加 version 和 manager_id；不返回患者/财务/员工敏感信息。 |

## 二、请求/响应示例（字符串 ID，与现有协议对齐）

### 1. 建店

```http
POST /api/v1/staff/branches
Cookie: A_CLINIC_SESSION=...
X-CSRF-Token: ...
Idempotency-Key: 7f1c...
Content-Type: application/json

{
  "code": "HX-002",
  "name": "河西分院",
  "address": "合成地址 2 号",
  "phone": "0731-00000002",
  "license_no": "SYNTH-LIC-002",
  "license_valid_to": "2030-12-31",
  "opening_hours": "周一至周六 09:00-18:00（文本说明）",
  "manager_id": "12"
}
```

```http
201 Created
{
  "data": {
    "branch_id": "21",
    "code": "HX-002",
    "name": "河西分院",
    "state": "草稿",
    "version": 1,
    "manager_id": "12",
    "verified_by": null,
    "verified_at": null
  },
  "request_id": "..."
}
```

- manager_id 首期必须等于当前 staff；否则 422。
- 创建事务同时建立该店 staff_branch 和 owner 角色 branch scope；失败整体回滚。
- 同键同内容重放返回原结果；同键不同内容 409 `IDEMPOTENCY_CONFLICT`。

### 2. 详情

```http
GET /api/v1/staff/branches/21
```

```json
{
  "data": {
    "branch_id": "21",
    "code": "HX-002",
    "name": "河西分院",
    "address": "合成地址 2 号",
    "phone": "0731-00000002",
    "license_no": "SYNTH-LIC-002",
    "license_valid_to": "2030-12-31",
    "license_no_expiry_confirmed": false,
    "opening_hours": "周一至周六 09:00-18:00（文本说明）",
    "manager_id": "12",
    "state": "草稿",
    "verified_by": null,
    "verified_at": null,
    "version": 1,
    "opening_version": 1,
    "opening_intervals": []
  }
}
```

### 3. 状态动作

```http
POST /api/v1/staff/branches/21/submit
{ "expected_version": 1 }
```

```http
POST /api/v1/staff/branches/21/verify
{
  "expected_version": 2,
  "license_valid_to": "2030-12-31",
  "license_no_expiry_confirmed": false,
  "verification_basis": "负责人现场核对许可证原件"
}
```

```http
200 OK
{
  "data": {
    "branch_id": "21",
    "state": "营业",
    "version": 3,
    "verified_by": "12",
    "verified_at": "2026-10-24T12:00:00Z"
  }
}
```

- `verified_by/at` 由服务端写入，请求不得包含；出现即 422 `UNKNOWN_FIELD`。
- 停用：`POST /staff/branches/21/disable {expected_version, reason}`；有未来班次/活动预约返回 409 影响摘要，零写入。
- 重新启用：`POST /staff/branches/21/reopen {expected_version}` → `待核验`，必须重新 verify，不能直接营业。
- 非法状态/版本：409 `BRANCH_STATE_CONFLICT` / `VERSION_CONFLICT`。

### 4. 营业区间范围替换

```http
PUT /api/v1/staff/branches/21/opening-intervals
Idempotency-Key: ...
{
  "from_date": "2026-11-01",
  "to_date": "2026-11-07",
  "expected_version": 3,
  "intervals": [
    {"valid_on": "2026-11-01", "starts_at": "2026-11-01T09:00:00+08:00", "ends_at": "2026-11-01T18:00:00+08:00"},
    {"valid_on": "2026-11-02", "starts_at": "2026-11-02T09:00:00+08:00", "ends_at": "2026-11-02T12:00:00+08:00"}
  ]
}
```

```json
{
  "data": {
    "branch_id": "21",
    "opening_version": 4,
    "state": "营业",
    "replaced_from": "2026-11-01",
    "replaced_to": "2026-11-07",
    "interval_count": 2
  }
}
```

- 空 `intervals` = 该范围闭店；不删除范围外记录。
- 全天：`starts_at=2026-11-01T00:00:00+08:00`、`ends_at=2026-11-02T00:00:00+08:00` 合法；`ends_at` 超过次日 00:00 必须拆行并返回 422。
- 有未来班次/活动预约与范围相交：409：

```json
{
  "code": "BRANCH_HAS_FUTURE_COMMITMENTS",
  "message": "该变更影响未来排班或预约，请先协调",
  "request_id": "...",
  "impact": {
    "schedule_count": 2,
    "appointment_count": 3,
    "first_starts_at": "2026-11-01T09:00:00+08:00",
    "last_ends_at": "2026-11-03T18:00:00+08:00",
    "truncated": false
  }
}
```

`impact` 只给计数/时间边界，不含患者姓名、电话、患者编号或病历。

## 三、状态转换表

| 当前 | 动作 | 下一状态 | 权限/前置 | 版本/幂等 | 失败 |
| --- | --- | --- | --- | --- | --- |
| （无） | POST create | 草稿 | singleton organization 负责人 + `branches.create` + owner；manager_id=当前 staff；字段合法 | Idempotency-Key；新 branch.version=1；同时建立 manager 的本店 staff_branch/owner scope | 422 组织/字段/manager；409 同键异内容/重复 code |
| 草稿 | POST submit | 待核验 | `branch.configure` + 本店 scope + 有效 staff_branch | Idempotency-Key + expected_version；version+1 | 409 `BRANCH_STATE_CONFLICT`/`VERSION_CONFLICT` |
| 待核验 | POST verify | 营业 | 同上；许可有效期/无期限依据合法 | 同上；写 verified_by/at + 审计；version+1 | 422 许可依据；409 状态/版本 |
| 营业 | PATCH 档案 | 营业 | 同上；不得改 license/state/verified 字段 | Idempotency-Key + expected_version；version+1 | 422 字段；409 |
| 营业 | POST disable | 停用 | 同上；无未来班次/活动预约 | Idempotency-Key + expected_version + reason；version+1 | 409 `BRANCH_HAS_FUTURE_COMMITMENTS`/状态/版本；零写入 |
| 停用 | POST reopen | 待核验 | 同上 | Idempotency-Key + expected_version；version+1 | 409 状态/版本 |
| 停用 | PATCH/verify/disable | 拒绝 | 需先 reopen | — | 409 `BRANCH_STATE_CONFLICT` |

不可删除门店；停用保留历史、排班、预约和审计。`PATCH` 永不直接写 `state` 或 `verified_at/verified_by`。

## 四、最多 12 条真实 PostgreSQL + HTTP 验收场景

1. 组织负责人 owner 创建门店：201，state=草稿、version=1；manager staff_branch 与 owner branch scope 已建立；审计有创建记录。
2. 非组织负责人 owner 创建：403；无 branch 写入。
3. sysadmin（无 owner/`branches.create`）创建：403；不因账号管理权限绕过。
4. organization 为零/多个：分别 422 `BRANCH_ORGANIZATION_NOT_CONFIGURED` / 409 `BRANCH_ORGANIZATION_AMBIGUOUS`；无写入。
5. manager_id 非当前 staff、离岗、无有效 owner 角色：422；创建整体回滚。
6. 草稿直接 verify/disable：409；submit 后待核验，verify 后营业，核验人/时间来自会话/Clock，客户端传 verified_at 422。
7. `license_valid_to=null` 且 `license_no_expiry_confirmed=false`：422；改为 true 且有 `verification_basis` 后成功并写审计。
8. 停用有未来已发布班次：409 影响摘要，branch 状态/version 不变，零写入；无未来班次时停用成功。
9. 缩短营业范围与未来活动预约相交：409 影响摘要；无患者姓名/电话；零写入。
10. 日期范围替换：范围外历史行不变，范围内旧行被替换，空 intervals 闭店，branch.version/opening_version+1；from_date 早于今天 422。
11. 全天结束于次日 00:00 合法；超过次日 00:00 422 要求拆行；跨午夜拆两行后同店同日不重叠。
12. 两个并发 PUT/停用同 expected_version：仅一个成功，另一个 409；无部分删除/半写入；详情只返回最新完整集合。

补充权限验收（可并入上述或单列）：frontdesk/sysadmin 无 branch.configure 不可列表/详情；owner 只能看到本店，跨店 404；列表/详情都按 role×branch 与 staff_branch 检查。若严格不超过 12 条，可把第 5 条与第 2 条合并。

## 五、待改文件清单（仅为实施批预估，本批未改）

- 契约：`specs/001-scheduling-booking/contracts/openapi.yaml`（BranchDetail、GET detail、submit/verify/disable/reopen、OpeningRangeRequest、BranchResult 字段、错误响应 impact）；`contracts/api.md`；`contracts/error-codes.md`；`contracts/permissions.md`。
- 迁移：新增 V005（仅当 Codex 批准）：`branch.license_no_expiry_confirmed boolean NOT NULL DEFAULT false`；`permission('branches.create')` 并授予 owner。若驳回新列，则改为 verify 必须非空 `license_valid_to`，并拒绝 null。
- 后端：`backend/src/main/java/cn/dental/organization/application/BranchService.java`、`controller/BranchController.java`、必要的 repository/事务辅助；不新建重复 `visit_record`。
- 测试：`backend/src/test/java/cn/dental/organization/BranchIntegrationTest.java`（真实 PG+HTTP，使用 `ConcurrentRunner`）。
- 文档：`docs/development/branch-configuration.md`（实现后）；T027/T035 实施说明需记录 branch 行锁协议。
- 前端：T024 不做；后续 T029 才接详情/工作台。

## 六、已有能力 / 本批建议 / 需 Codex 确认

**已有能力**
- `branch` 四状态、version、verified_by/at、manager_id、license_valid_to；`organization.responsible_staff_id`；`staff_branch`；`role_branch_scope`；`branch_opening_interval`（valid_on/起止/version）；`AuthorizationService.requireBranch` 的 role×branch+任职联合检查；`IdempotencyService`、`AuditService`、`Clock`；`visit_record` 已存在。
- 现有契约有列表、新增、PATCH、opening PUT；但无详情 GET，PATCH 无 state/核验动作，opening PUT 语义不足。

**本批建议（推荐方案）**
- 新增 `branches.create`（owner）+ singleton organization + 创建事务的有限 manager 启动授权。
- 新增详情 GET 与 submit/verify/disable/reopen；PATCH 只改档案。
- 新增 `license_no_expiry_confirmed`；若驳回，首期要求非空有效期。
- opening 改为日期范围替换；branch.version 聚合；空列表闭店；范围外不动。
- 变更前锁 branch 行；有未来班次/活动预约默认 409 零写入；T027/T035 共用该锁。
- impact 摘要不含患者身份资料；列表/详情按现有权限链过滤。

**需 Codex 确认**
1. 是否批准新增 `branches.create` 权限与 `license_no_expiry_confirmed` 列（两项都是最小模型/权限补充；不新增业务表）。
2. 首期是否接受“manager_id 必须等于创建者”和“创建者自动获得本店 owner scope”作为启动授权；替代是另做组织中店长分配/核验流程，工作量更大。
3. 是否接受 branch.version 作为营业区间聚合版本（与档案编辑共用并发控制），还是另立 opening 版本实体。
4. 营业区间是否限定未来 31 天；历史区间是否只读。
5. 影响摘要的 409 错误体是否增加 `impact` 字段；不返回患者/预约明细是否符合前台协调需要。

## 七、未验证与边界

- 本批只读源码与契约，未运行数据库/HTTP；未实现 T024，未改 tasks 勾选，T024 仍未完成。
- 未验证真实门店许可证/组织初始化流程；organization 由本地初始化或 T022 合成数据建立，本批不增加 organization HTTP 创建接口。
- 未验证 T027/T035 的锁实现，因为两者尚未实现；本文件只规定它们必须遵守的 branch 行锁协议。
- T023 排班验收测试仍应先于后续排班实现；本批不提前勾选 T024，也不实现 T025–T029。
- 未修改 Word/图片/constitution；未提交/推送。
