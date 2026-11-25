# 旧档绑定与前台凭证签发/重签（T017-B，2026-10-23）

本批实现两条公开路由：`POST /api/v1/auth/patient-bind`（旧档绑定）与两条员工路由 `POST /api/v1/staff/patients/{id}/binding-token`（首发）+ `.../binding-token/reissue`（显式重签）。范围限定，未实现前端或预约；T017 整项保持未勾选，等待 Codex 复核。按 DEEPSEEK_T017B_IMPLEMENT.md 的 D1–D5 审定结论实现。

## 前置结论（D1–D5）

- **D1 身份依据与线下核验留痕**：不新增证件号字段。姓名+电话只匹配档案、不证明本人；签发须有服务端门店权限 + 结构化线下核验记录 `verification_method`（`visit_evidence`/`id_document`）+ `in_person=true`。服务端只留痕核验人/时间/门店/方法，不在审计放姓名电话/证件号/任意自由文本。
- **D2 重签**：独立 `reissue` 端点，必填 `previous_challenge_ref`；锁内须仍是「当前待用」凭证，否则 409 `STALE_BINDING_REISSUE`。首发遇待用凭证 409 `BINDING_TOKEN_ALREADY_ACTIVE`；已绑定患者拒绝首发/重签。
- **D3 二选一响应**：首发 `binding_token=明文`（仅此一次）、`replayed=false`；同键同内容重放 `binding_token=null`、`replayed=true` 且返回 `challenge_ref`/`expires_at`/`status`。复用 `IdempotencyService`，不另写一套、不存令牌明文。操作数 72→73（新增 `staffReissueBindingToken`）。
- **D4 每患者至多一条待用凭证**：部分唯一索引 `uq_identity_challenge_active_binding(patient_id) WHERE purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL`；该索引含已过期记录，签发/重签在患者锁下先撤销过期/耗尽记录再插入。V004 对历史绑定行缺 `branch_id` 不任填门店，统一撤销并用约束过渡。
- **D5 权限仅前台**：新权限 `patients.bind_issue` 仅默认授予 `frontdesk`；`owner`/`sysadmin` 不隐式绕过。

## 前台签发/重签（PatientBindingTokenService + PatientBindingController）

请求 DTO 严格对应契约（`additionalProperties=false` 由全局 `FAIL_ON_UNKNOWN_PROPERTIES` 兜底）：

- 首发：`branch_id`（`[1-9][0-9]*`）、`patient_name`（≤60）、`patient_phone`（≤30）、`appointment_id`（可选）、`verification_method`（枚举）、`in_person`（必填布尔）。
- 重签：同首发 + `previous_challenge_ref`（必填 UUID）。

`PatientBindingTokenService.issue/reissue` 均为 `@Transactional`，复用 `IdempotencyService.execute`（operation=`patient-bind-issue`/`patient-bind-reissue`，`targetId=patient_id`、`resultRef=challenge UUID`）。`requireAuthorized` 回调 = `AuthorizationService.requireBranch(actor, "patients.bind_issue", branchId)`（进锁前与取得幂等锁后各执行一次，覆盖等待期间被撤权）；`business` 回调持有首发明文令牌（仅 `AtomicReference`，事务提交失败即丢弃）；`requireVisible` 校验患者仍可见。

业务落地（患者 advisory 锁下，顺序固定）：

1. `verifyBasis`：`SELECT name,phone FROM patient WHERE patient_id=? FOR UPDATE`，姓名/电话须与请求精确一致，否则 422 `BUSINESS_BASIS_REQUIRED`（这是档案匹配，不证明本人）。
2. `verifyAppointment`：若传 `appointment_id`，须 `appointment(appointment_id, patient_id, branch_id)` 命中，否则 422 `BUSINESS_BASIS_REQUIRED`。
3. `rejectIfBound`：目标患者已有微信身份 → 409 `PATIENT_ALREADY_BOUND`（不重绑/不合并/不启用）。
4. `revokeExhausted`：撤销该患者已过期/失败次数≥5 的待用绑定记录（解除部分唯一索引占用）。
5. 首发 `rejectIfActive`（仍有待用凭证 → 409 `BINDING_TOKEN_ALREADY_ACTIVE`）；重签校验 `previous_challenge_ref` 为当前待用凭证并 `UPDATE ... SET revoked_at`（否则 409 `STALE_BINDING_REISSUE`）。
6. `insertChallenge`：`purpose='patient_binding'`、`branch_id`、`issued_by=actor.accountId()`、`patient_id`、10 分钟、32 随机字节只存 SHA256 摘要。
7. `audit.user` 最小摘要（`verification_method`、`in_person`、`branch_ids`，白名单字段）。

凭证 32 随机字节 Base64url（43 字符），只存摘要，不入库/审计/toString/异常/响应；响应 `Cache-Control: no-store`。令牌明文仅在首发请求内存出现一次。

## 旧档绑定（PatientBindingService + PatientBindingExecutor + PatientAuthController）

`POST /auth/patient-bind` 只收 `{registration_token, binding_token}`。`PatientBindingService`（`@Transactional(Propagation.NEVER)` 编排层）先入口限流 `PatientBindingThrottle`（查询前），再摘要查找两个凭证、判活，随后跨代理调 `PatientBindingExecutor`（`@Transactional`）单事务原子落地：

1. 微信身份 advisory 锁 `wechat-identity(app_id, open_id)`。
2. 患者绑定 advisory 锁 `patient-bind(patient_id)`。
3. 持锁后注册上下文、绑定上下文行 `FOR UPDATE` 权威复查（用途/摘要对应身份/未撤销/未消费/未过期/失败次数<5）。注册上下文绑定微信身份、绑定凭证绑定患者，客户端不能替换身份。
4. 持锁重查 `wechat_identity(app_id, open_id)`：已绑定（含停用账号）→ 409 `WECHAT_IDENTITY_ALREADY_BOUND`，不重绑/不转移/不启用。
5. 持锁重查目标患者账号：已有另一微信 → 409 `PATIENT_ALREADY_BOUND`；账号停用 → 403 `PATIENT_ACCOUNT_DISABLED`（不启用）；无账号 → 新建；未绑定且启用 → 关联微信。
6. 按固定顺序（先注册后绑定）单次消费两个凭证（`consumed_at` 仅当仍为 NULL 时各写 1 行）。
7. 复用 `SessionService.issue(accountId, PATIENT)` 签发患者会话（leaf：account 行 FOR UPDATE）。
8. 系统审计（最小摘要，不记 openid/token/姓名/电话）。

任何一步失败整体回滚，不留孤儿账号或「已消费却未绑定」。数据库唯一约束（`wechat_identity.account_id UNIQUE`、`UNIQUE(app_id,open_id)`、`account.patient_id UNIQUE`）兜底。关系不变量：**1 患者 ↔ 1 账号 ↔ 1 微信**，不转移、不合并、不启用停用账号。

## 锁序

所有身份操作固定锁序，避免相反顺序死锁：

```
幂等 advisory 锁（签发/重签）→ 患者绑定 advisory 锁 patient-bind(patient_id) → challenge 行 FOR UPDATE → account 行 FOR UPDATE
```

绑定落账（`PatientBindingExecutor`）在写 `wechat_identity` 之前先 `account ... FOR UPDATE` 锁定并权威重查绑定/启用，`SessionService.issue` 仍按叶子取 account 行锁；签发/重签在取得患者锁后再次鉴权（见下）。微信身份锁 `wechat-identity` 为涉及微信操作的第一把锁。`IdentityLocks.lockKey(namespace, parts...)` 统一派生（SHA256 → 前 8 字节 → long），与 T017-A 字节级一致；命名空间 `wechat-identity`（注册+绑定）、`patient-phone`（注册）、`patient-bind`（绑定+签发+重签）。重签与绑定允许两个合法顺序：重签先提交则旧凭证失效（绑定 401）；绑定先提交则绑定成功且重签被拒绝（409），不假定重签永远胜出。

## 失败计数 / 限流 / 脱敏

- `PatientRegistrationChallengeService.recordFailure(challengeId, purpose)` 参数化用途，权威条件 `purpose=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>? AND failed_attempts<5`，REQUIRES_NEW 单条 UPDATE 原子累加，由 Propagation.NEVER 编排层在事务回滚后调用，封顶 5、撤销后不增。
- 归责：可识别有效注册上下文但绑定凭证无效 → 至少计注册上下文；双方有效而业务冲突 → 按规则计绑定上下文；未知凭证无可更新行/系统故障 → 不处罚。
- `PatientBindingThrottle`（30/IP/15min，`reset()`）置于摘要查找之前；超限 429 不触发数据库探测。与注册/微信登录/员工登录预算分离。
- 脱敏：两凭证只存摘要；`Challenge`/`Issued`/DTO `toString` 固定脱敏；响应与审计不回显 openid/凭证/姓名/电话；`Cache-Control: no-store`。

## 迁移 V004（新增，不改 V001–V003）

`branch_id`、`revoked_at` 两列 + `fk_identity_challenge_branch` + 重写 `ck_identity_challenge_purpose_fields` + 部分唯一索引 `uq_identity_challenge_active_binding` + `patients.bind_issue` 权限授予 `frontdesk`。历史绑定行缺 `branch_id` 统一撤销（`revoked_at=now()`）后加 CHECK 过渡约束。详见 `database-migrations.md`。

## 未完成范围

前端、预约、真实微信联调未实现；不把合成核验称真实线下核验。T017 保持未勾选，等待 Codex 复核。

## 复核修复（Codex 复核第二轮，2026-10-23）

源码复核发现四处问题，定向修复（未改契约/错误码/迁移，未进入前端或预约）：

1. **幂等摘要漏患者**：`contentMap` 补 `patient_id`；重放时显式核对 `target_id` 与本次患者一致，`result()` 校验返回凭证引用的 `patient_id`/`branch_id` 与本次请求一致。同名同电话的另一患者同键 → 409 `IDEMPOTENCY_CONFLICT`，不泄露前一位患者凭证引用；跨门店重放同样 409。
2. **签发缺门店营业/核验鉴权**：新增 `requireBranchBusiness`（门店 `state='营业'` 且 `verified_at` 非空、`staff_branch.enabled` 且已核实），仅用于签发/重签/重放，不改变其他业务的 `requireBranch`。鉴权在进幂等锁前、取得幂等锁后、取得患者锁后、重放返回前各执行一次，覆盖等待患者锁期间的撤权窗口；与业务提交真正并发的撤权仍属既有竞争窗口，由绑定落账侧以 account 行锁协同。
3. **返回对象与私有请求记录脱敏**：`IssueResult` 与内部 `Request` 固定 `toString` 脱敏；HTTP 首发仍返回明文令牌，日志/审计/幂等记录不存明文（审计仅白名单 `verification_method`/`in_person`/`branch_ids`）。
4. **账号行锁与并发屏障**：`PatientBindingExecutor` 改为先 `account ... FOR UPDATE` 再写 `wechat_identity`，持账号锁后重查绑定与启用，避免外键 FOR KEY SHARE 隐式锁升级与并发停用竞争；补同微信注册对绑定、同凭证并发消费、停用与绑定竞争（独立事务夹具，不声称已测生产患者停用接口）、撤权后重放，以及重签 vs 绑定两种提交顺序的屏障测试。

## 测试证据

- 编排层单测 `PatientBindingServiceTest`：系统故障不计数、业务冲突计绑定上下文、绑定无效计注册上下文、注册无效计绑定上下文、双未知不计数、超限不查询、Outcome 脱敏。
- 真实隔离 PostgreSQL + HTTP `PatientBindingIntegrationTest`（33 项）：签发+绑定正常路径、同键重放返回 `binding_token=null`、同键不同内容 409、重签撤销旧凭证新凭证可用、过时重签 409、先绑后重签拒绝、活跃凭证二次首发 409、已绑定患者首发 409、停用账号绑定 403 且不启用、姓名/电话不符 422、in_person=false 422、预约引用不符 422、缺 CSRF 403、无角色员工 403、患者会话访问员工路由 403、耗尽/过期待用记录撤销后重签、不同微信同患者并发单次成功、绑定 vs 重签并发恰一方成功、注入会话失败双凭证消费原子回滚；复核新增：同名同电话两患者同键 409、跨门店重放 409、停业门店 403、未核验门店 403、无效任职 403、同角色跨门店不拼接 403、撤权后重放 403、审计/幂等不存明文、同微信注册并发单次绑定、同凭证并发消费、停用与绑定竞争（独立事务夹具）、重签先于绑定屏障、绑定先于重签屏障。
