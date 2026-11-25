# T025 员工资格、跨店任职与医生服务：实施前定向决策

日期：2026-10-26。本文件为实施前决策记录；T025-A 列表/建档/详情与 T025-B1 资格录入/首次审核/历史已按审定段实现，实施与测试记录见 `handoff.md`，其余端点仍未实现。设计阶段未修改代码、契约、迁移、Word、图片、constitution、tasks 状态；实现批次的范围以对应指令为准。

> 当前实际状态：`BranchService`/`OpeningIntervalService` 已实现门店与营业区间；**不存在 `StaffService`、`StaffController`，也不存在处理 `/staff/staff-members` 的控制器**。OpenAPI 只有员工接口契约，没有实现。T025 不能按“已有接口只需补逻辑”假设实施。
>
> **2026-10-26 T025-A 审定**：本批只实现员工列表、创建、详情三个端点；不实现 PATCH、任职写入、资格审核、医生服务写入、排班或前端。批准 `staff.manage_global` 仅授 owner，实际还要求唯一有效 organization 的 `responsible_staff_id` 与当前在岗员工一致、有效 staff_web 会话及完成强制改密；sysadmin 无业务绕过权。`dentist_service` 继续为全局服务能力，与 `branch_service` 交集使用，不加 branch_id。批准 `staff.version` 正整数默认 1；`credential.verified_by/verified_at` 允许成组为空以表达真正 pending；本批不建审核历史表、不添 credential.version。**不批准“资格撤销因有预约而 409”**：已知失效资格必须可立即标为不可用并阻止新发布/确认，已有安排保留原事实并进入后续协调；具体协议留 T025-B。**不批准门店负责人仅凭 staff_id 为无本店关系员工建立首次任职的 write-only 例外**；后续首任由有目标门店权限的组织负责人办理，或使用明确批准的调入依据，门店负责人只维护已授权关系。主动关闭服务/主动结束任职可在未处理安排存在时 409；资格自然到期与撤销不等同于主动配置停用。离岗/账号并发、负责人替换、任职/服务依据持久化和版本保护留后续。排班不能机械要求医生先有登录角色；执业资格、任职、服务能力与操作员权限分离。
>
> **2026-10-26 T025-B1 审定**：本批只实现资格新增、pending→verified/rejected 首次审核和只读审核历史；不实现撤销、重核、证件事实修改、已有预约协调或身份核心并发。新增 `credential.version` 与追加式 `credential_review`；不补造迁移前历史。审核历史仅全局组织负责人可读，完整 basis 不进通用审计/日志；同键幂等、锁序为鉴权→幂等锁→organization→staff→credential。并发相反审核同版本最多一个成功。资格自然到期/撤销对已有安排的影响协调、跨店任职和角色并发仍留给 Codex。

## 结论摘要

- **员工主档、资格、任职、医生服务、账号、角色必须继续分离**。建档不建账号、不授角色；任职不授角色；资格核验不等于授予 `dentist` 角色。
- **全局员工主档只由组织负责人维护**：安装实例唯一 `organization.responsible_staff_id`，且其 owner 角色具有新增的 `staff.manage_global`；sysadmin 不因账号管理权限获得员工业务权，门店负责人不能改全局姓名/在岗状态/资格/医生服务。
- **门店负责人只管理本店任职**：`branch.configure` + 该 role 的门店 scope + 该员工在本店的有效 `staff_branch`；不能通过本店权限修改全局字段。
- **医生可开展项目是全局临床范围**：现有 `dentist_service` 没有 `branch_id`，T025 不应把它偷换成门店范围。门店是否开放项目由 `branch_service` 管，排班同时检查两者。若 Codex 要求“本店可做项目”按店配置，必须另立 `branch_id` 模型，不能借用全局表。
- **资格状态**只使用 `pending/verified/rejected`；空 `valid_to` 不等于有效；`no_expiry_confirmed` 默认 false；核验人/时间取会话与 `Clock`。日期有效按 `[valid_from, valid_to]` 的上海自然日解释。
- **停用任职、驳回/撤销资格、关闭医生服务、员工离岗均不自动取消或释放已确认预约**。有已发布/暂停班次或未终结预约时返回 409 类型化影响摘要，零写入；`待确认` 不占号但单独计数，不伪装成已占用。
- **现有字段不足以安全实现全局修改和历史**：`staff`、`credential` 无 version；`credential.verified_by/verified_at` 为 NOT NULL；无资格审核历史表。推荐补 `staff.version`、`credential.version`、允许核验人/时间为空，并新增追加式 `credential_review`；这些是**需 Codex 审定的迁移项**，不能偷偷借 `branch.version`/`account.auth_version`。
- **T025 当前契约缺 4 个必要端点**：员工详情、员工全局修改、资格新增、资格审核/撤销。没有这些端点就无法完成“资格核验”和“全局姓名/在岗状态维护”；不得以页面方便为由直接开放任意表 CRUD。

## 一、已有事实与对应源码

### 1.1 代码与契约

| 事实 | 证据/符号 |
| --- | --- |
| 无 `StaffService`/`StaffController`，无 `/staff/staff-members` 实现 | `backend/src/main/java/cn/dental/organization/` 仅有 `BranchService`、`OpeningIntervalService` 及两个 Controller |
| 门店接口按“同一 role 的 permission + branch scope + 有效已核实 staff_branch”鉴权 | `AuthorizationService.requireBranch`；`BranchService.canConfigure` |
| 账号管理员只有 sysadmin 的 `accounts.manage`，不自动获得经营/员工权限 | `AuthorizationService.requireAccountAdmin` |
| 账号创建要求目标员工在岗且有有效已核实任职；不自动授角色 | `AccountAdminService.create`、`setRoles` |
| 授予 `dentist` 角色时检查 `staff_type=牙医` 和有效的 `dentist_practice` 资格 | `AccountAdminService.setRoles` 的 credential 检查 |
| 幂等作用域为账号+操作+键；先鉴权再取幂等 advisory 锁，锁后二次鉴权 | `IdempotencyService.execute` |
| 审计字段有白名单，当前不含 `staff_type`、`job_title`、`employment_state`、`practice_no`、`credential_type`、`verification_state`、`valid_from/to`、`item_id` | `AuditService.ALLOWED_SUMMARY_FIELDS` |
| 无员工相关错误码；现有 409 影响异常只有 `schedule_count/appointment_count/first_starts_at/last_ends_at` | `ApiErrorCode`、`ApiImpactException.ApiImpact` |
| 无 `lockStaff`；只有按 staff_id 升序的 `lockDentists` 以及 branch/schedule/resource 锁 | `BusinessLockRepository` |
| 员工登录和会话都检查 `employment_state='在岗'` | `StaffAuthService`、`AuthorizationService.requireCurrent` |
| 管理端只有门店页 `BranchesView`；没有 `StaffSettingsPage` | `admin-web/src/views` |

### 1.2 数据库事实（V001/V002）

- `staff`：`staff_id PK`、`name`、`staff_type`（牙医/其他人员）、`job_title`、`practice_no UNIQUE`、`employment_state`（在岗/离岗）；**无 version、无 created_at/updated_at**。
- `credential`：`credential_id PK`、`staff_id FK`、`credential_type`、`certificate_no`、`valid_from`、`valid_to`、`verified_by NOT NULL FK`、`verified_at NOT NULL`、`verification_state`、`no_expiry_confirmed NOT NULL DEFAULT false`；**无 version、无审核历史表**。
- `credential` 约束：`verification_state IN ('pending','verified','rejected')`；`valid_to>=valid_from`；`no_expiry_confirmed=true` 时 `valid_to` 必须为空；`UNIQUE(staff_id,credential_type,certificate_no)`。
- `staff_branch`：`(staff_id,branch_id) PK`、`enabled`、`verified_at NOT NULL`；**无 version**。
- `dentist_service`：`(dentist_id,item_id) PK`、`enabled`、`verified_at NOT NULL`；**无 version、无 branch_id**。
- `account`：`staff_id UNIQUE`、`auth_version`；账号与员工主档分离。
- `role/permission`：固定角色；当前无 `staff.manage_global` 等员工权限。

### 1.3 契约事实

- 现有 OpenAPI 只有：
  - `GET/POST /staff/staff-members`（列表、创建）
  - `PUT /staff/staff-members/{id}/branches/{branch_id}`（任职 set PUT）
  - `PUT /staff/staff-members/{id}/services/{item_id}`（医生服务 set PUT）
- **没有**：员工详情 GET、员工全局 PATCH、资格新增、资格审核/撤销/重核、任职/服务读取端点。
- `StaffMemberRequest` 允许内嵌一个 `credential`，但 `CredentialRequest` 不允许 `verification_state`；创建只能产生待核验或需额外审核动作。
- `PUT` 任职/服务是 set 型操作，契约明确无 version；`verified_at` 由服务端记录，同值重复无副作用。
- 账号创建、角色门店授权仍是 sysadmin 路径；不能借员工接口自动建账号/授角色。

### 1.4 Word 设计边界

只提取以下相关段落，不改 Word：`3.2 角色与数据权限`、`4.2 组织与任职`、`6.3.3 staff`、`6.3.4 credential`、`6.3.5 staff_branch`、`6.3.10 account_role`、`6.3.33 dentist_service`、`6.3.35 schedule`。Word 全量目标不等于这些接口已交付；T025 仍以实际源码和契约为准。

## 二、端点 × 权限 × 数据对象矩阵

| 端点 | 当前状态 | 推荐权限 | 数据范围/规则 |
| --- | --- | --- | --- |
| `GET /staff/staff-members` | 契约有、代码无 | 全局：`staff.manage_global`；门店：`branch.configure` | 全局负责人可列全组织最小档案；门店负责人只列**本店有效任职**员工；不返回证书编号/证件全文/账号/角色/患者数据 |
| `POST /staff/staff-members` | 契约有、代码无 | `staff.manage_global` + 唯一 `organization.responsible_staff_id` | 创建全局主档；不要求 staff_branch；不建账号/角色；牙医必须有 practice_no；可带一个 pending 资格 |
| `GET /staff/staff-members/{id}` | **缺** | 全局或本店已任职 | 无权限统一 404；返回最小档案、version、本店任职状态、资格**摘要**（不返回证书全文） |
| `PATCH /staff/staff-members/{id}` | **缺** | `staff.manage_global` + 组织负责人 | 只改 name/job_title/practice_no/staff_type/employment_state；expected_version；不接收账号、角色、任职、资格状态、verified_by/at |
| `PUT /staff/staff-members/{id}/branches/{branch_id}` | 契约有、代码无 | `branch.configure` + 本店 scope + 有效本店任职 | 只改本店任职 enabled/verification_basis；不得改全局字段；停用前检查未来安排 |
| `POST /staff/staff-members/{id}/credentials` | **缺** | `staff.manage_global` + 组织负责人 | 新建 pending 资格；不接受客户端 verification_state/verified_by/at |
| `POST /staff/staff-members/{id}/credentials/{credential_id}/review` | **缺** | `staff.manage_global` + 组织负责人 | 审核 verified/rejected、撤销/更正；expected_version/状态前置；写历史与审计 |
| `PUT /staff/staff-members/{id}/services/{item_id}` | 契约有、代码无 | `staff.manage_global` + 组织负责人 | 全局医生服务范围；启用须牙医+在岗+有效 verified 资格；停用前检查影响 |
| `GET /staff/accounts`、角色授权 | 已有 | sysadmin `accounts.manage` | 不属于 T025；不能因员工管理自动获得；账号仍要求有效任职/资格 |

**明确不做**：员工创建不自动建 `account`/`account_role`/`role_branch_scope`；任职不自动授角色；资格核验不自动授 `dentist`；门店负责人不能修改全局员工字段。

## 三、状态、字段与日期规则

### 3.1 staff

- 主档字段：姓名、`staff_type`、`job_title`、`practice_no`、`employment_state`；`staff_type` 只有牙医/其他人员。
- 牙医 `practice_no` 必须有值且唯一；其他人员可为空。岗位不决定权限。
- `employment_state`：在岗/离岗；**不物理删除**，历史接诊/审计保留。
- 建议新增 `staff.version integer NOT NULL DEFAULT 1 CHECK (version>0)`；全局 PATCH/在岗状态修改必须带 `expected_version`，成功后 +1。
- 离岗前检查：未来已发布/暂停班次、未终结预约、是否组织负责人/门店负责人、是否存在启用账号。需要 Codex 决定“先停账号再离岗”还是“离岗只阻断会话、不自动停账号”；不在本设计偷偷跨身份域停账号。

### 3.2 credential

- 状态机：
  - `pending -> verified`（核验通过）
  - `pending -> rejected`（驳回）
  - `verified -> rejected`（撤销/更正；不删除）
  - `rejected -> pending`（更正后重新送审；需新的审核记录）
- 只有 `verified` 且日期有效才可用于安排接诊；`pending/rejected` 永不可用。
- 有效日期：`valid_from` 为空表示无起始限制；`valid_to` 为空且 `no_expiry_confirmed=true` 才表示已确认无期限；空日期且 flag=false **不有效**。
- 日期按 Asia/Shanghai 自然日解释，`valid_from` 和 `valid_to` 均包含当日；`valid_to` 次日 00:00 起失效。`valid_to < valid_from` 拒绝。
- `verified_by/verified_at` 只由服务端从会话和 `Clock` 写入；客户端不得传入。新驳回/撤销不能覆盖原核验人；审核人写在审核历史中。
- 推荐新增 `credential.version integer NOT NULL DEFAULT 1`，审核动作带 `expected_version`；`rejected -> pending` 也递增版本。
- **schema 缺口**：现有 `verified_by/verified_at` NOT NULL 使 pending 无法诚实表达“尚未核验”。推荐改为 nullable；若 Codex 不批准，T025 只能把创建人/时间当“录入人”使用，语义会误导，不建议。
- **历史缺口**：现有表没有审核历史。推荐追加式 `credential_review`：
  - `review_id PK`、`credential_id FK`、`action`（submit/verify/reject/revoke/correct）、`from_state`、`to_state`、`actor_staff_id FK`、`basis`、`occurred_at`
  - 只追加、不更新/删除；当前 `credential.verification_state` 是当前状态，`credential_review` 保留历次执行人/依据；禁止覆盖历史执行人。
  - 若 Codex 拒绝新表，则退化为 `audit_log` 作为唯一历史，并且不得支持同一证书的多次撤销/重核；这会降低可审计性，需明确接受。

### 3.3 staff_branch

- 只表示“该员工可在该门店工作”，不是角色、不是账号、不是资格。
- `enabled=true` 不要求先有账号/角色；但排班仍检查资格和角色。
- 同一员工可有多行；PK 防重复；跨店任职不是重复建档。
- 无 version，set PUT：同 `enabled` 且同一 `verification_basis` 为同值无副作用；`enabled` 或依据变化才更新 `verified_at` 并审计。`verified_at` 取服务端 `Clock`。
- 停用前检查本店未来已发布/暂停班次和未终结预约；有影响返回 409，不自动取消、不释放占用。

### 3.4 dentist_service

- 只表示该牙医的**全局临床服务范围**，不表示门店开放；门店开放由 `branch_service` 管（T026）。
- `enabled=true`：目标必须是 `staff_type=牙医`、在岗、具有有效 `dentist_practice` verified 资格；`item_id` 必须存在；不自动授角色/账号。
- `enabled=false`：不要求当前资格有效，但须检查引用该医生+项目的未来已发布/暂停班次和未终结预约；有影响 409。
- 无 version，set PUT：同值无副作用；变化更新 `verified_at` 并审计。启用 `verified_at` 由服务端记录。

## 四、全局与门店权限边界

### 4.1 谁能做什么

| 操作 | 全局员工主档 | 本店任职 | 资格 | 医生服务 | 账号/角色 |
| --- | --- | --- | --- | --- | --- |
| 组织负责人（唯一 responsible_staff_id + owner + `staff.manage_global`） | 新建/改名/岗位/执业标识/在岗状态 | 在拥有本店 branch.configure+scope+任职时可设 | 新建/审核/撤销 | 全局设置 | 不自动；另走 sysadmin |
| 门店负责人（owner + branch.configure + 本店 scope + 有效任职） | 禁止 | 仅本店 | 只读摘要，禁止写 | 只读，禁止写 | 禁止 |
| sysadmin | 禁止 | 禁止 | 禁止 | 禁止 | 已有 accounts.manage |
| 牙医 | 只读本人最小信息 | 只读本人 | 只读本人摘要 | 只读本人 | 禁止 |
| 前台/库管/财务 | 禁止 | 禁止 | 禁止 | 禁止 | 禁止 |

- `staff.manage_global` 建议仅授予 `owner` 角色，并在服务层再次校验 `organization.responsible_staff_id = actor.staffId`；**不能仅凭 owner 角色或 scope 放行**。
- 门店负责人不能因为“本店要排班”改全局姓名、在岗状态、资格或医生服务；否则会影响其他门店。
- sysadmin 不能借 `accounts.manage` 修改员工业务字段；员工接口必须独立鉴权。
- **角色并集不突破门店范围**：同一 actor 的多角色仍按“某一 role 同时具备 permission 和 branch scope”判断，不能先合并角色再合并门店。

### 4.2 新员工无 staff_branch 的创建/首次任职

1. 组织负责人通过 `POST /staff/staff-members` 创建全局主档；不要求 staff_branch，不创建账号/角色。
2. 首个任职由**有目标门店 branch.configure + scope + 有效本店任职**的负责人执行 `PUT .../branches/{branch_id}`；组织负责人若没有该店经营权限也不能绕过。
3. 门店负责人只可列出本店已任职员工；对未任职员工不展示完整档案。首次任职使用已知 `staff_id` 写入，属于 write-only 例外，不返回员工档案，不泄露证书/账号；不存在的目标统一 404。
4. 任职成功不授角色；之后由 sysadmin 创建账号并另行授权；账号创建仍要求有效任职。
5. 若 Codex 认为“门店负责人不能凭 ID 给未任职员工建档/任职”边界过宽，替代方案是在 `POST` 增加 `initial_branch_id` 并由组织负责人完成首任；不推荐为此开放全员选择器。

## 五、变更影响与拒绝/协调策略

统一原则：**T025 不自动取消预约、不释放患者已确认占用、不直接改预约状态**；配置变更与未来安排冲突时 409、零写入，由 T027/T035 的停诊/协调流程处理。影响摘要不包含患者姓名、电话、病历或患者编号。

| 变更 | 检查范围 | 结果 |
| --- | --- | --- |
| `staff_branch.enabled=false` | 该员工+该店未来 `已发布/暂停` 班次；该店 `待确认/已确认/候诊中/接诊中` 预约 | 有任一非零 → 409；`待确认` 单独计数，不当作已占用；不自动取消/释放 |
| `credential` verified→rejected/撤销 | 该员工所有未来 `已发布/暂停` 班次；所有未终结预约 | 有影响 → 409；不自动改班次/预约 |
| `credential` 到期（时间流逝） | 不写数据库；T027 发布、T035 确认时按 Clock 重新校验 | 不自动取消；返回不可用原因；由后续流程协调 |
| `dentist_service.enabled=false` | 该医生+该项目的未来 `已发布/暂停` 班次及预约 | 有影响 → 409；不自动取消 |
| `staff.employment_state=离岗` | 所有未来 `已发布/暂停` 班次、所有未终结预约；组织负责人/门店负责人/启用账号 | 有影响 → 409；账号需先由 sysadmin 停用（建议）；不自动停账号 |
| `staff_type` 牙医→其他人员 | 同上，并检查该医生未来班次/服务引用 | 有影响 → 409；不自动解绑历史 |
| 普通姓名/岗位修改 | 无排班影响 | 允许；审计+version；不影响历史接诊 |

- `待确认` 预约不占号，但它是未解决需求；配置停用/资格撤销时**计入影响并拒绝**，避免无法确认；响应分别给 `pending_appointment_count` 与 `active_appointment_count`。
- `已确认` 且 `coordination_state='待协调'` 仍是活动预约，必须计入，不释放占用。
- `草稿` 班次不阻止配置变更；T027 发布时必须重查任职/资格/服务，不能凭旧草稿发布。
- 影响摘要建议新增 `StaffImpact`（不扩展已有 `ApiImpact` 的 4 字段，避免破坏既有分支接口）：`published_schedule_count`、`paused_schedule_count`、`pending_appointment_count`、`active_appointment_count`、`first_starts_at`、`last_ends_at`、`truncated`；不返回患者 ID。

## 六、锁序、幂等、版本和同值语义

### 6.1 推荐锁序

1. **账号维护锁 734192014**：只用于账号/角色维护；员工业务事务**不得**获取它。
2. **幂等键 advisory 锁**：只有带 `Idempotency-Key` 的全局员工创建/PATCH/资格审核进入；先鉴权，再取幂等锁。
3. **branch 行锁**：仅影响单店的任职变更；按 branch_id 升序。
4. **staff 行锁**：全局员工/资格/服务修改锁目标 staff；任职变更在 branch 后锁 staff；多员工按 staff_id 升序。
5. **不锁患者**：T025 影响检查只做只读聚合，不触碰患者行。
6. **T027/T035 的未来约束**：排班发布/预约确认必须先 branch 行锁，再 staff 行锁，再 schedule/预约；T025 不反向取 branch/account 锁。

- 全局员工修改不取 branch 锁：T027 持有 branch 等 staff 时，全局修改可以先完成，不会形成“staff 等 branch、branch 等 staff”的环。
- 任职修改取 branch→staff，与 T027 同序；因此禁止“先锁 staff 再锁 branch”。
- 不持 staff/branch 行锁再获取 734192014；账号创建/角色授予也不反向取 staff 业务锁（现有账号服务只读 staff_branch/credential 做校验）。

### 6.2 幂等与版本

- **全局创建/PATCH/资格审核**：幂等作用域 `actor_account + operation + request_key`；同键同摘要返回原结果，不同摘要 409。操作名建议 `staff-create`、`staff-patch`、`credential-create`、`credential-review`。
- **任职/服务 set PUT**：契约明确无 version、属幂等例外；不要求 Idempotency-Key。同 `enabled` 且同规范化 `verification_basis` 为无副作用；变化才写 `verified_at`+审计。
- `staff.version` 用于全局 PATCH/在岗状态；`credential.version` 用于审核；**任职/服务没有可靠 version，不借用 staff.version**。
- 锁后必须重新鉴权并重读：目标是否仍在本店 scope、actor 是否仍为组织负责人/仍有 branch.configure；撤权等待锁后不得用旧结果提交。
- 所有读取/写入使用注入 `Clock`；不使用 `LocalDate.now()`/`Instant.now()`。

## 七、必需变更与需 Codex 审定事项

### 7.1 推荐的最小代码/契约变更（本轮不实施）

1. 迁移：
   - `staff.version integer NOT NULL DEFAULT 1 CHECK (version>0)`；
   - `credential.version integer NOT NULL DEFAULT 1 CHECK (version>0)`；
   - `credential.verified_by`、`credential.verified_at` 改为可空（pending 的真实状态）；
   - 新增 `credential_review`（若批准历史方案）；
   - 新增权限 `staff.manage_global`，只授 owner；服务层再校验唯一组织负责人。
2. 端点：
   - `GET/PATCH /staff/staff-members/{id}`；
   - `POST /staff/staff-members/{id}/credentials`；
   - `POST /staff/staff-members/{id}/credentials/{credential_id}/review`；
   - 原 `PUT branches/{branch_id}`、`PUT services/{item_id}` 补响应体（staff_id/branch_id/item_id/enabled/verified_at），不改变 set 语义。
3. 代码结构：
   - `StaffService`、`StaffController`、`StaffEligibilityService`（供 T027/T035 查询有效资格/任职/服务）；
   - `AuthorizationService.requireOrganizationOwner`（或等价方法）；
   - `BusinessLockRepository.lockStaff`；
   - `AuditService` 白名单补员工/资格字段；
   - `ApiErrorCode` 补 `STAFF_*` 错误；
   - `SecurityConfig` 加 `/staff/staff-members/**` 的 staff_web 路由；
   - 集成测试 `StaffIntegrationTest`、固定 Clock 的 `StaffCredentialClockTest`。
4. 不改 `account` 创建逻辑；账号、角色、会话仍由既有 sysadmin 流程维护。

### 7.2 必须由 Codex 审定

1. **是否批准 `staff.manage_global` + 唯一组织负责人**，而不是“任一 owner/门店负责人即可维护全员”；sysadmin 是否确认无业务权限。
2. **是否批准 `dentist_service` 继续为全局临床范围**；若要求按门店管理医生可做项目，需新增 `branch_id` 或新表，属于模型变更，不在 T025 偷改。
3. **是否批准新增 4 个端点**（详情/PATCH/资格新增/资格审核）；不批准则 T025 无法完成“资格核验和全局状态维护”，只能交付查询/建档/任职。
4. **是否批准 `staff.version`、`credential.version`、可空 `verified_by/at`、`credential_review`**；若不批准，必须明确接受“无可靠全局并发保护/无资格审核历史”的限制，不能声称已满足。
5. **影响策略**：是否确认“有 `待确认` 也算影响并 409”，以及“草稿班次不阻止、发布时重查”；是否接受 `credential` 自然到期不自动改预约，由 T027/T035 协调。
6. **离职与账号**：是否确认员工 `离岗` 不自动停账号（由 sysadmin 先停），以及组织负责人/门店负责人离岗必须有替代人。
7. **首任可见性**：是否允许门店负责人用已知 staff_id 给尚未在本店任职的员工做首次任职（write-only，不返回档案）；若不允许，需批准 `POST` 的 `initial_branch_id` 或由组织负责人首任。
8. **资格历史**：若拒绝 `credential_review`，是否接受以 `audit_log` 作为唯一历史，并限制同一证书撤销后重核。

## 八、推荐分批

### T025-A：员工查询/建档/全局修改/任职（先做）

- 迁移 `staff.version`、权限、可空核验人（若批准）。
- `GET/POST /staff/staff-members`、`GET/PATCH /{id}`。
- `PUT /{id}/branches/{branch_id}`；首任流程；任职影响检查。
- 不做资格审核、医生服务；创建时可写 pending credential（若字段批准）。
- 测试：权限/跨店/无任职创建/同值/影响/幂等/版本。

### T025-B：资格核验与历史

- `credential.version`、`credential_review`（若批准）。
- `POST credentials`、`POST credentials/{id}/review`；pending/verified/rejected、日期边界、审计/历史。
- 资格有效判定服务方法；不接排班。

### T025-C：医生服务与下游契约

- `PUT services/{item_id}`；启用前置资格/在岗；停用影响。
- `StaffEligibilityService` 供 T027/T035；只读目录测试夹具，不把 T026 未交付接口算完成。
- 与 T026 `branch_service`、T027 `schedule_service` 的联调另列；T025-C 不实现排班。

## 九、真实 PostgreSQL + HTTP 验收矩阵

必须使用隔离 schema（`DENTAL_TEST_DATABASE`/`PostgresTestSupport`），真实 PostgreSQL，不用 H2；并发用 `ConcurrentRunner`/行锁/`CountDownLatch`，不用固定 `sleep`。

| # | 场景 | 断言 |
| --- | --- | --- |
| 1 | 组织负责人创建员工，不提供 branch/account/role | 201；staff 存在；staff_branch/account/account_role 均不新增 |
| 2 | 门店负责人/普通 owner/sysadmin 调全局创建/PATCH | 403；无写入 |
| 3 | 同一 practice_no 重复创建；同键同内容重放 | 409 业务冲突/原结果；无第二行 |
| 4 | 新员工无 staff_branch，由本店负责人首次任职 | 成功；任职可查；不自动授角色/账号 |
| 5 | 门店负责人列员工/查未任职员工 | 只返回本店已任职；未授权详情 404，不泄露存在性 |
| 6 | 门店 A 负责人给门店 B 任职/改全局字段 | 404/403；B 的任职不变 |
| 7 | 全局 PATCH 版本冲突；同值 PATCH | 409 零写入；同值不递增 version/不写审计 |
| 8 | 停用任职时有已发布班次+待确认+已确认待协调 | 409；分别计数；预约/占用未释放；零写入 |
| 9 | 任职同值重复、依据变化 | 同值无副作用；依据变化更新 verified_at+审计 |
| 10 | pending 资格安排接诊/授 dentist 角色 | 拒绝；不能因创建自动获角色 |
| 11 | verified 资格 valid_to=今天 | 当天可用；Clock 推进到次日不可用；不自动改预约 |
| 12 | `no_expiry_confirmed=true` 且 valid_to 非空；false 且空 valid_to | 前者 422；后者不可判定有效 |
| 13 | 并发 verify/reject 同一 credential | 一成功一 409；审核历史保留正确 actor，不覆盖 |
| 14 | verified→rejected/撤销时有未来已发布/活动预约 | 409；不自动取消/释放 |
| 15 | 医生服务启用：非牙医/离岗/无有效资格；门店 owner 调 | 422/403；无写入 |
| 16 | 医生服务停用有已发布班次/活动预约 | 409；影响摘要不含患者身份 |
| 17 | 员工离岗有未来安排/启用账号/组织负责人 | 409；账号不自动停；需先由 sysadmin 处理 |
| 18 | 等待锁期间撤权/换组织负责人 | 锁后复查 403/404；零写入 |
| 19 | 幂等写入后审计故障/业务失败 | 整体回滚；同键重试可恢复；无半条 staff/credential |
| 20 | 列表游标/limit/敏感字段 | staff_id 稳定游标；最多100；不含证书编号/账号/角色/患者数据 |

## 十、未验证事项

- 未运行构建/测试；本文件不代表 T025 已完成。现有 `tasks.md` T025 仍未勾选，T024 也未勾选；不改任务状态。
- 未改 OpenAPI/迁移/代码/Word/图片/constitution；所有 schema 和端点建议待 Codex 批准后才进入实现。
- T026 的 `branch_service`、T027 的 `schedule_service` 尚未实现；本设计不把目录/排班接口算作已交付。
- 未做前端 `StaffSettingsPage`、真实浏览器/微信联调、真实用户库或云部署。
- 未重新登录 DentalPin；本文不声称其员工/财务实现已验证。
