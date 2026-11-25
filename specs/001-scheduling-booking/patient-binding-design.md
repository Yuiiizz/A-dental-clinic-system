# 旧档绑定与前台凭证签发设计核对（T017-B）

日期 2026-10-23。本文为 T017-B 设计核对；Codex 已于同日审定（见第 7 节 D1–D5 结论），第 0/2.1/2.2/3.3/5 节的原始建议以第 7 节审定结论为准并已据其实现。

## 0. 结论摘要

- **前台签发**：`identity_challenge` 补 `branch_id`；签发请求由单一自由文本 `verification_basis` 改为「`branch_id` + 精确姓名 + 精确电话（+ 可选预约引用）」。授权基于服务端推导（角色门店范围 + 有效任职 + 门店营业），自由文本降级为可选审计备注，不再作为授权依据。
- **凭证**：只存 SHA-256 摘要；首次响应给明文、仅一次；同键重放只给已签发元信息并明确「无法重显」；结果丢失须显式重签并原子撤销旧有效凭证。
- **原子绑定**：有效注册上下文 + 有效绑定凭证（均 10 分钟、最多 5 次失败）核验后，单事务内原子消费两凭证、建立或关联唯一患者账号与微信身份、签发患者会话。禁止自动转移/合并、禁止启用停用账号。
- **锁序**（与 T017-A 相容）：微信身份锁 → 患者账号锁 → challenge 行锁。
- **新增权限码** `patients.bind_issue`（前台）。

## 1. 现状与缺口

### 1.1 现状表结构（V001–V003）

- `identity_challenge(purpose='patient_binding')`：已存 `patient_id`、`issued_by`（员工账号）、`token_hash`、`expires_at`、`consumed_at`、`failed_attempts`。**缺 `branch_id`、缺「撤销/被重签取代」标记**。
- `wechat_identity`：`account_id UNIQUE` + `UNIQUE(app_id, open_id)` → **1 账号 ↔ 1 微信**。
- `account`：`patient_id UNIQUE`、`staff_id UNIQUE`、`ck_account_identity (patient_id 与 staff_id 互斥)` → **1 患者 ↔ 1 账号**。
- 因此现有唯一约束实际允许的关系是：**1 患者 ↔ 1 账号 ↔ 1 微信身份**。一名患者最多绑一个微信；一个微信最多绑一个账号。
- `staff_branch(staff_id, branch_id)` PK + `enabled` + `verified_at NOT NULL`（有效任职）。
- `role_branch_scope(account_id, role_id, branch_id)` PK（角色门店范围）。
- `branch.state`（营业/停业）、`verified_by`/`verified_at`。
- 权限目录现有：`accounts.manage`、`branch.configure`、`appointments.assist`、`appointments.own.read`；角色 `sysadmin/owner/frontdesk/dentist`。**无「绑定凭证签发」权限码**。
- `appointment(patient_id, branch_id, state, ...)`；`queue_ticket(branch_id)`；`visit_record` 无 branch_id/patient_id。
- `patient` 无身份证/证件号字段（这是「更强身份证明」的数据缺口，见 D1）。

### 1.2 契约缺口

- `POST /staff/patients/{id}/binding-token` 只收 `verification_basis`（≤300 自由文本），无 branch_id、无办理记录引用，响应仅 200/403（缺 404/409/422）。
- `POST /auth/patient-bind` 只收 `{registration_token, binding_token}`，响应仅 200/422（缺 401/403/409/429）。
- 自由文本 `verification_basis` 不能等同授权；凭证明文仅显示一次与「同键重放返回凭证」的恢复语义冲突。

## 2. 推荐方案

### 2.1 前台签发：branch_id + 可核验业务依据

签发请求（`POST /staff/patients/{id}/binding-token`）改为：

```json
{
  "branch_id": 3,
  "patient_name": "张某某",
  "patient_phone": "13800000000",
  "appointment_id": null,
  "verification_notes": "线下核验后办理绑定凭证"
}
```

- `branch_id` 必填，办理门店。
- `patient_name`、`patient_phone` 必填，与患者档案精确一致（电话按 T017-A 同一规范化规则比较）。
- `appointment_id` 可选，患者在该门店的预约引用（加强依据 + 审计）。
- `verification_notes` 可选 ≤300，仅审计，不作为授权依据。

服务端授权/核验（顺序固定，全部通过才签发）：

1. **员工授权（服务端推导，不信任请求）**：当前员工账号在 `branch_id` 同时满足——
   - 存在某角色 `role_id` 具有 `patients.bind_issue` 权限，且 `role_branch_scope(account_id, role_id, branch_id)` 存在（门店范围）；
   - `staff_branch(staff_id, branch_id)` 存在且 `enabled=true`、`verified_at IS NOT NULL`（有效任职）；
   - `branch.state='营业'` 且 `branch.verified_at IS NOT NULL`（门店已核验营业）。
   任一不满足 → 403 `FORBIDDEN`。
2. **患者身份核验（可核验依据，防「知 ID 即发」）**：`patient_name`、规范化 `patient_phone` 与目标 `patient` 记录 `name`、`phone` **精确相等**，且目标患者与本员工同组织（跨组织全库检索禁止）。不符 → 422 `BUSINESS_BASIS_REQUIRED`。
   - 精确姓名+电话对**新来患者与老患者通用**，是 permissions.md「精确姓名+联系电话」的落地。
3. **业务引用核验（可选加强）**：若传 `appointment_id`，须存在 `appointment.patient_id=目标 AND appointment.branch_id=branch_id` 且 `state ∈ {待确认, 已确认, 候诊中, 接诊中, 已完成}`。不符 → 422 `BUSINESS_BASIS_REQUIRED`。老患者建议传（本店办理记录），新来患者无预约可省略。
4. 生成 32 随机字节 Base64url（43 字符）绑定凭证，只存 SHA-256 摘要于 `identity_challenge` 新行（`purpose='patient_binding'`、`branch_id`、`patient_id`、`issued_by`=当前员工账号、10 分钟）。
5. 响应：首次 200 `{binding_token(明文, 仅此次), expires_at}`，`Cache-Control: no-store`。

> 关键取舍：姓名+电话精确匹配是当前数据下的最小身份依据（患者表无证件号）。它防的是「仅凭 patient_id 误发/滥发」，不是防「能读档案的恶意前台」——后者只能靠更强身份证明（证件号）与审计。是否补证件号字段见 D1。

### 2.2 凭证单次显示 + 幂等 + 重签

凭证只存摘要，无法从摘要还原明文，因此「同键重放」不可能返回明文。方案：

1. **首发（新 Idempotency-Key）**：签发并返回明文（仅此一次）。同时 `operation_request(actor_account_id, operation='patient_bind_issue', request_key, request_hash)` 记录幂等键与「已签发」状态（**不存明文**）。
2. **同键同哈希重放（响应丢失后的重试）**：经 `IdempotencyService` 命中后**再次鉴权**（重放也要重新校验员工授权，防权限变更后重放），返回 200 但**不含令牌**：
   ```json
   { "issued": true, "binding_token": null, "reason": "token_shown_once",
     "challenge_ref": "<uuid>", "expires_at": "..." }
   ```
   并明确「无法重显」。**不复用通用幂等「重放原响应」路径**——原响应含只显示一次的明文，通用重放会违背单次显示；此处幂等只保存元信息。
3. **同键不同哈希**：409 `IDEMPOTENCY_CONFLICT`（复用既有语义）。
4. **结果丢失/凭证过期/看错重签**：须**显式动作**，新增 `POST /staff/patients/{id}/binding-token/reissue`（请求体与首发一致，新的 Idempotency-Key）。重签**重新执行全部授权与身份核验**（不跳过），并在**同一事务**内：
   - `UPDATE identity_challenge SET revoked_at=now() WHERE patient_id=? AND purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL`（原子撤销该患者全部旧有效绑定凭证）；
   - 插入新凭证行并返回新明文。
   旧凭证被撤销后不可再用于绑定（核验时 `revoked_at IS NOT NULL` → 401 `BINDING_TOKEN_INVALID`）。

> 最小字段调整（需 Codex 审定）：`identity_challenge` 增 `revoked_at timestamptz NULL`（区分「被重签取代」与「已用于绑定」的 `consumed_at`）。是否用部分唯一索引保证「每患者至多一张有效绑定凭证」见 D4。

### 2.3 原子绑定

`POST /auth/patient-bind`（`{registration_token, binding_token}`）：

1. 入口 IP 限流（新 `PatientBindingThrottle`，独立预算，与注册/登录分离）→ 超限 429。
2. 两个凭证摘要查找 + 判活（未消费、未撤销、未过期、`failed_attempts<5`、用途正确）。任一无效 → 401 `BINDING_TOKEN_INVALID` / `REGISTRATION_TOKEN_INVALID`（不计数）。
3. 单事务内（`PatientBindingExecutor`，跨代理 `@Transactional`；编排层 `PatientBindingService` 为 `Propagation.NEVER`）：
   1. 微信身份 advisory 锁 `(app_id, open_id)`（与 T017-A 同一命名空间 `wechat-identity`）。
   2. 患者账号 advisory 锁（新命名空间 `patient-account`，键由 `patient_id` 派生）。
   3. 持锁后对注册上下文、绑定上下文行 `FOR UPDATE` 权威复查。
   4. 持锁重查 `wechat_identity(app_id, open_id)`：
      - 已存在（含停用账号）→ 409 `WECHAT_IDENTITY_ALREADY_BOUND`，**不重绑、不转移、不启用**。
   5. 持锁重查目标患者账号：
      - 患者已有账号：
        - 该账号已有微信身份（另一微信）→ 409 `PATIENT_ALREADY_BOUND`（一名患者只一个微信，**禁止自动转移/合并**）；
        - 该账号无微信身份：若 `enabled=false` → 403 `PATIENT_ACCOUNT_DISABLED`（**不启用**）；若 `enabled=true` → 关联：`INSERT wechat_identity(account_id, app_id, open_id)`。
      - 患者无账号：`INSERT account(patient_id=目标, enabled=true, must_change_password=false)` + `INSERT wechat_identity`。
   6. 单次消费两凭证（`consumed_at` 仅当仍为 NULL 时各写 1 行，否则拒绝）。
   7. `SessionService.issue(accountId, PATIENT)` 签发患者会话（2 小时），**不授员工角色**。
   8. `audit.system` 最小摘要（不记 openid/凭证/姓名/电话）。
4. 任一步失败整体回滚；失败计数在事务回滚后由编排层 catch 提交（见 2.4/2.5）。

**多微信/账号规则（与现有唯一约束一致）**：1 患者 ↔ 1 账号 ↔ 1 微信。绑定已绑定微信 → 409；绑定已绑定患者 → 409；停用账号不启用；不转移、不合并。

**家庭共享手机号**：手机号不是身份。绑定由 `binding_token`（前台按具体 `patient_id` 签发）驱动，全程不用手机号解析身份。同手机号的多名患者可各有自己的账号+微信，互不影响。

### 2.4 锁序与并发

**全局固定锁序**（所有身份操作同一顺序，杜绝交叉死锁）：

```
第 1 层：微信身份 advisory 锁  wechat-identity(app_id, open_id)   ← 涉及微信的操作都先取
第 2 层：患者/账号 advisory 锁
          注册→patient-phone(phone)；绑定/重签/停用→patient-account(patient_id)
第 3 层：challenge 行 FOR UPDATE（注册上下文 → 绑定上下文）
第 4 层：patient/account/wechat_identity 行（叶子，不再取锁）
```

不变式：**任何操作不得在取得更高层锁之后再取更低层锁**；所有涉及微信的操作第一把必取 `wechat-identity` 锁，从而与 T017-A 注册的「微信身份锁先行」一致。员工账号停用路径（T013/T014，`734192014` 账号维护锁 → account 行）不取微信锁，与绑定的 `patient-account` advisory 锁是不同串行化域，只在 account 行相交且该行为叶子，不成环。

并发行为：

- **同微信并发注册 vs 绑定**：被 `wechat-identity` 锁串行化；注册先完成则微信已绑 → 绑定 409；绑定先完成则注册侧持锁重查发现已绑 → 409。最终只一份 patient/account/wechat_identity。
- **同微信、两个有效绑定凭证并发绑定**：微信锁串行化；后者 `FOR UPDATE` 复查发现注册上下文已消费（或微信已绑）→ 401/409，只成功一次。
- **不同微信、同一患者并发绑定**：`patient-account` 锁串行化；后者持锁重查发现患者已有微信 → 409 `PATIENT_ALREADY_BOUND`。
- **并发重签 vs 绑定**：重签在 `patient-account` 锁下原子撤销旧凭证；被撤销凭证用于绑定 → 401（`revoked_at` 非空）。
- **停用患者账号 vs 绑定**：见不变式，不成环；绑定要么在停用前完成，要么持锁重查发现 `enabled=false` → 403 且不启用。

### 2.5 失败计数 / 限流 / 脱敏

- `recordFailure` 复用于 `patient_binding` 上下文：现实现把 `purpose` 硬编码为 `'wechat_registration'`，需参数化（传入预期 `purpose`）或拆出第二个方法，保留权威条件（`purpose=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>? AND failed_attempts<5`，注意补 `revoked_at`）。仍由 `Propagation.NEVER` 编排层在事务回滚后调用，REQUIRES_NEW 单条 UPDATE，不持锁自锁。计数归类沿用 T017-A：可识别有效上下文的身份/业务校验失败计数；未知凭证/格式错/已消费/已过期/已达上限/系统故障不计数。
- 入口限流：`PatientBindingThrottle`（30/IP/15min，同款预算与 reset()），置于摘要查找**之前**；超限 429 不触发数据库探测。
- 同键重放再次鉴权（见 2.2）。
- 脱敏：绑定凭证/注册凭证只存摘要；Challenge/DTO `toString` 脱敏；响应与审计不回显 openid/凭证/姓名/电话；`Cache-Control: no-store`。

## 3. 具体修改清单

### 3.1 迁移 V004（新增）

- `ALTER TABLE identity_challenge ADD COLUMN branch_id bigint NULL, ADD COLUMN revoked_at timestamptz NULL`。
- `ADD CONSTRAINT fk_identity_challenge_branch FOREIGN KEY (branch_id) REFERENCES branch(branch_id)`。
- 扩展 `ck_identity_challenge_purpose_fields`（或新增约束）：`patient_binding` 行要求 `patient_id IS NOT NULL AND issued_by IS NOT NULL AND branch_id IS NOT NULL`；`wechat_registration` 行要求 `app_id/open_id NOT NULL AND patient_id IS NULL AND branch_id IS NULL`。
- （D4 若采纳）部分唯一索引 `uq_identity_challenge_active_binding ON identity_challenge(patient_id) WHERE purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL`。
- 权限目录增 `patients.bind_issue`（患者绑定凭证签发）；`role_permission` 增 `frontdesk→patients.bind_issue`（owner 是否授予见 D5）。

### 3.2 契约（OpenAPI，待 Codex 审定后改）

- `POST /staff/patients/{id}/binding-token`：请求体替换为 2.1 结构；响应补 404/409/422；200 响应增加 replay 形态说明。
- `POST /staff/patients/{id}/binding-token/reissue`（新增）：显式重签。
- `POST /auth/patient-bind`：响应补 401/403/409/429。

### 3.3 新增错误码

| 码 | HTTP | 含义 |
| --- | --- | --- |
| `BINDING_TOKEN_INVALID` | 401 | 绑定凭证无效/过期/已消费/已撤销 |
| `BINDING_TOKEN_NOT_REISSUABLE` | 200(meta) 或 409 | 同键重放，明文仅显示一次无法重显 |
| `PATIENT_ALREADY_BOUND` | 409 | 目标患者已有微信身份（不转移/不合并/不加第二微信） |
| `PATIENT_ACCOUNT_DISABLED` | 403 | 目标患者账号停用，拒绝绑定且不启用 |
| `BUSINESS_BASIS_REQUIRED` | 422 | 无可核验业务依据（姓名/电话不符或预约引用不符） |

（`WECHAT_IDENTITY_ALREADY_BOUND`、`REGISTRATION_TOKEN_INVALID`、`IDEMPOTENCY_CONFLICT`、`FORBIDDEN` 复用既有。）

### 3.4 可复用 vs 需新增（主源码）

- **可复用**：`SessionService`、`AuditService`（MANDATORY）、`ApiException/ApiErrorCode`、`IdempotencyService`（仅用于签发去重元信息，见 2.2）、`PatientRegisterThrottle` 模式（新 `PatientBindingThrottle` 复制同款）、advisory 锁 `lockKey` 派生（提取为共享工具或复制，新增命名空间 `patient-account`）、`PatientRegistrationChallengeService`（`recordFailure` 参数化 purpose 后复用）。
- **需新增**：`PatientBindingTokenService`（签发+重签）、`PatientBindingService`（编排，NEVER）+ `PatientBindingExecutor`（@Transactional 原子绑定）、`PatientBindingThrottle`。

## 4. 验收矩阵

### 4.1 真实 PostgreSQL 并发（隔离合成数据）

| 场景 | 预期 |
| --- | --- |
| 有效注册上下文+有效绑定凭证并发（同微信） | 仅一次成功，另一个 401/409；只一份 account/wechat_identity |
| 不同微信、同一患者并发绑定 | 仅一次成功，另一个 409 `PATIENT_ALREADY_BOUND` |
| 并发注册 vs 绑定（同微信） | 串行化，最终一份绑定，另一侧 409 |
| 并发重签 vs 绑定（旧凭证） | 旧凭证被撤销，绑定 401；新凭证有效；每患者至多一张有效凭证 |
| 停用账号 vs 绑定 | 不成环，绑定要么先完成要么 403 且不启用 |
| 失败计数并发 | 权威条件 UPDATE 封顶 5，不越界，回滚后可靠保存 |

### 4.2 HTTP 权限（真实隔离 PostgreSQL）

| 场景 | 预期 |
| --- | --- |
| 前台（有 scope + 有效任职 + 门店营业）签发 | 200，返回明文 |
| 无 scope / 非本店 / 无效任职 / 门店停业 | 403 |
| 仅知 patient_id、姓名或电话不符 | 422 `BUSINESS_BASIS_REQUIRED` |
| 预约引用非本店或非本人 | 422 `BUSINESS_BASIS_REQUIRED` |
| 新来患者（无预约）姓名+电话精确匹配 | 200 |
| 同键同哈希重放 | 200 元信息、无令牌、明确无法重显 |
| 同键不同哈希 | 409 |
| 重签后旧凭证绑定 | 401，新凭证绑定成功 |
| 微信已绑 / 患者已绑 / 患者账号停用 | 409 / 409 / 403 |
| 未知凭证、超 5 次 | 401 + 限流 429，不计数 |
| 脱敏 | 响应/审计/日志无 openid/凭证/姓名/电话 |

## 5. 待决策点（Codex 审定，≤5）

- **D1 身份依据强度**：现方案「精确姓名+电话」是最小可用依据；是否补 `patient` 证件号字段（更强防滥发）。建议：本批用姓名+电话，证件号列为后续。
- **D2 重签接口形态**：独立 `.../reissue` 端点（推荐）vs 同端点 `reissue:true`。
- **D3 同键重放响应**：200+元信息+`token=null`（推荐，保持幂等语义）vs 独立 409 `BINDING_TOKEN_NOT_REISSUABLE`。
- **D4 每患者至多一张有效绑定凭证**：部分唯一索引强制（推荐，重签撤销旧凭证）vs 允许多张并存。
- **D5 新权限码授予范围**：仅 `frontdesk`（推荐，前台核验职责）vs `frontdesk`+`owner`。

## 6. 边界

本文件只做设计核对：不改契约/迁移/应用代码/任务状态；不安装依赖；不提交推送；不访问真实患者库；不虚构字段/权限。自由文本 `verification_basis` 降级为审计备注，不再作为授权依据。

## 7. Codex 审定结论（2026-10-23，覆盖上文冲突处）

审定后按下列 D1–D5 实现；本节为最终设计，第 0/2.1/2.2/3.3/5 节若有冲突以本节为准。

**D1 — 身份依据与线下核验留痕（不新增证件号）**
- 不新增身份证号字段。姓名+电话只是档案匹配条件，不能证明本人。
- 签发请求改为结构化 `verification_method`（枚举 `visit_evidence`=现场核对既有就诊凭据 / `id_document`=现场核对证件但不采集号码）+ `in_person`（本人到场确认，必填且必须为 true）。
- 服务端只记录核验人/时间/门店/方法（`AuditService` 类型化白名单字段 `verification_method`、`in_person`、`branch_ids`），不得在审计里放姓名电话/证件号或任意自由文本；不采集证件号。
- 预约引用 `appointment_id` 若提供须核对患者/门店；单有预约或姓名电话不足以证明本人。无本店记录者允许经本组织门店人工核验后办理（受审计的人工授权，不假装存在数据库业务记录）。当前单组织安装，不虚构 organization_id 或跨组织校验。

**D2 — 独立重签端点 + 显式引用旧凭证**
- 采纳独立 `POST /staff/patients/{id}/binding-token/reissue`，新增必填 `previous_challenge_ref`。
- 锁内检查该引用仍为「当前待用」凭证（未消费、未撤销、未过期、失败次数<5、patient_id 一致），否则 409 `STALE_BINDING_REISSUE`（避免两名前台相互撤销刚签发的新凭证）。
- 首次签发遇仍有效待用凭证 → 409 `BINDING_TOKEN_ALREADY_ACTIVE`（不偷偷重签）。已绑定患者拒绝首发/重签（409 `PATIENT_ALREADY_BOUND`）。

**D3 — 200 二选一响应 + 复用幂等服务**
- 200 明确二选一：首发 `binding_token=明文`（仅此一次）、`replayed=false`；同键同内容重放 `binding_token=null`、`replayed=true`，并返回 `challenge_ref`、`expires_at`、当前 `status`，不伪称旧凭证仍有效。
- 复用现有 `IdempotencyService`（只存 targetId/resultCode/resultRef，不存令牌明文）；`targetId=patient_id`、`resultRef=challenge UUID`。不另写一套幂等。
- 首发令牌仅限本请求内存（事务提交失败即不返回）；幂等记录与业务/审计同事务提交。
- 新增 operationId `staffReissueBindingToken`，操作数 72→73，不强行维持 72。

**D4 — 每患者至多一条待用凭证（部分唯一索引含过期记录）**
- 采纳部分唯一索引 `uq_identity_challenge_active_binding ON identity_challenge(patient_id) WHERE purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL`。
- 该索引也包含已过期记录（不能在索引条件里写 `now()`）；新签发/重签在患者锁下先 `revokeExhausted`（撤销已过期/失败次数≥5 的待用记录）再插入；活跃记录只允许显式重签。
- V004 迁移对已有绑定行缺 `branch_id` 不任填门店：保留历史并统一撤销（`revoked_at=now()`），再用 CHECK 约束过渡（`patient_binding` 要求 `patient_id/issued_by` 非空且 `branch_id IS NOT NULL OR revoked_at IS NOT NULL`），附迁移测试。

**D5 — 新权限仅默认授予 frontdesk**
- 新权限码 `patients.bind_issue` 仅默认授予 `frontdesk`；`owner`/`sysadmin` 不隐式绕过。
- 复用同 `role_id` 门店权限链（账号角色 × 门店范围 × 有效 `staff_branch` 任职 × 门店营业已核验）；签发/重签与重放均复核当前会话、强制改密、任职、门店及目标可见性。Cookie+CSRF 与强制改密规则保留。

**并发顺序澄清（覆盖第 2.4 节「叶子不再取锁」的不准确表述）**：`SessionService.issue` 会取 account 行 FOR UPDATE，故完整顺序为——幂等 advisory 锁（签发/重签）→ 患者 advisory 锁 `patient-bind(patient_id)` → challenge 行 FOR UPDATE → account 行 FOR UPDATE（叶子）。重签与绑定允许两个合法顺序：重签先提交则旧凭证失效（绑定 401）；绑定先提交则绑定成功且重签被拒绝（409），不假定重签永远胜出，测试用屏障分别覆盖。
