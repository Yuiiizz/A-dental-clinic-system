# 员工查询与建档（T025-A）

实现日期：2026-10-26。范围包含 T025-A 员工列表/建档/详情，以及 T025-B1 资格新增、首次审核和历史；不含 PATCH、任职写入、资格撤销/重核、医生服务写入、排班或前端。

## 权限

- 全局员工列表/建档/详情：唯一 `organization.responsible_staff_id` 的当前在岗员工，且其 owner 角色具有 `staff.manage_global`；必须使用有效 staff_web 会话、完成强制改密。sysadmin 的 `accounts.manage` 不自动获得员工业务权。
- 门店列表/详情：同一 role 的 `branch.configure` + 该 role 的 branch scope + 操作者本人在该店有效且已核验的 `staff_branch`。只能读取该店已有任职（包括停用关系）的员工最小档案；不返回该员工在其他门店的关系、证书原文、账号/角色或患者数据。
- 无权目标与不存在统一返回 404，不泄露员工是否存在。

## GET /staff/staff-members

- `cursor` 是稳定的 `staff_id` 游标；空表示从头。`limit` 默认20、范围1–100；非法值返回422 `INVALID_REQUEST`。
- 全局负责人返回全组织最小档案；门店负责人只返回本店已有任职员工（跨店/停用不重复）。
- 列表项：`staff_id,name,staff_type,job_title,employment_state,version`。不返回资格、账号、角色、患者字段。
- `next_cursor` 仅在还有下一页时返回。

## POST /staff/staff-members

请求：`name,staff_type,job_title,practice_no?,credential?`。`Idempotency-Key` 必填。

- 创建强制 `employment_state=在岗`、`staff.version=1`。
- 牙医必须填写非空且唯一的 `practice_no`；其他人员可为空。姓名不是唯一身份键。
- 可选 `credential` 只能生成 `pending`；`verified_by/verified_at` 必须为空，`no_expiry_confirmed` 默认 false。客户端不能提交核验状态、核验人/时间、任职、账号/角色等服务端字段。
- 日期须为 `YYYY-MM-DD`；省略、空白或显式 null 均表示未填写该日期；非法日期字符串拒绝，不能静默变成无期限。有起止日期时须有序；`no_expiry_confirmed=true` 时不得同时填写非空 `valid_to`。录入已过期证件仍只是 pending，不自动变成 verified。
- 不创建 `account/account_role/role_branch_scope/staff_branch`，不自动授予资格或 `dentist` 角色。
- 同键同内容重放返回原员工；同键不同内容 409；不同键相同 `practice_no` 返回 409 `STAFF_PRACTICE_NO_EXISTS`。唯一冲突整体回滚，不继续在失败事务中操作。
- 幂等摘要包含全部接受字段；审计只写 `state_to` 等白名单摘要，不写证书号、姓名/电话或完整请求。

成功返回员工最小详情（含 version 与 pending 资格摘要），HTTP 201。响应不返回 `certificate_no`、`verified_by`、账号、角色或患者信息。

## GET /staff/staff-members/{id}

- 全局负责人可查任意员工；门店负责人只能查本店已有任职关系（含停用）的员工。
- 使用同一数据库快照读取 staff、可见门店关系和资格摘要。
- 返回：staff 基本字段、`version`、`branches`（仅可见门店）、`credentials`（`credential_id,credential_type,verification_state,valid_from,valid_to,no_expiry_confirmed,verified_at,effective`）。
- `effective` 按 Asia/Shanghai 当前日期计算：`verified` 且起始日未到未来、到期日包含当日；`no_expiry_confirmed=false` 且无到期日不算有效。`pending/rejected` 恒为 false。
- 不返回证书编号、核验人 ID、账号/角色、其他门店关系或患者数据。

## 资格录入、首次审核与历史（T025-B1）

### POST /staff/staff-members/{id}/credentials

- 仅全局组织负责人；`Idempotency-Key` 必填。
- 建 `pending` 资格，`version=1`，并追加 `submit` 历史；不建账号、不授角色。
- 日期规则同建档内嵌资格：省略/空白/null 表示未填写，非法字符串422；`no_expiry_confirmed=true` 时 `valid_to` 必须为空；过期证件可作为 pending 保存。
- 同键同内容重放返回原资格；同键异内容409；不同键相同 `(staff_id,type,certificate_no)` 409。
- 响应不返回证书原文。

### POST /staff/staff-members/{id}/credentials/{credential_id}/review

- 仅全局组织负责人；只允许 `pending -> verified` 或 `pending -> rejected`。
- 请求：`decision`、`basis`（1–300）、`expected_version`。
- 锁内核对版本和 pending；成功 `credential.version+1`，写 `verify/reject` 历史；并发相反审核同版本最多一个成功。
- `verified` 时：到期日按上海自然日含当日；已过期返回422 `STAFF_CREDENTIAL_EXPIRED`；`valid_to` 为空且 `no_expiry_confirmed=false` 拒绝；`no_expiry_confirmed=true` 必须无 `valid_to`；未来 `valid_from` 可以审核真实性，但 `effective=false`。
- `rejected` 不需要日期当前有效；审核人/时间取会话与 Clock；`verified_by/at` 在 rejected 表示本次审核经办，不表示核验通过。
- 不提供 verified 撤销、rejected 重核或证件事实修改。

### GET /staff/staff-members/{id}/credentials/{credential_id}/reviews

- 仅全局组织负责人；只读追加式历史。
- `review_id` 稳定游标，默认20最大100；credential 必须属于路径 staff，否则404。
- 返回 `review_id,action,from_state,to_state,actor_staff_id,basis,occurred_at,resulting_version`；不返回证书原文。
- 迁移V007之前的历史不补造；迁移前版本以现存字段为基线。

## 迁移 V006/V007

- V006：`staff.version`；`credential.verified_by/verified_at` 可空且成组；verified 必须有核验人时间；`staff.manage_global` 仅授 owner。
- V007：`credential.version`；追加式 `credential_review`（submit/verify/reject、from/to状态、actor、basis、occurred_at、resulting_version）；不补造迁移前历史。
- 不改 V001–V006；旧 pending 不伪造核验人。

## 验收与限制

- 真实隔离 PostgreSQL + HTTP：`CredentialIntegrationTest` **14 项**、`StaffIntegrationTest` **19 项**、`StaffCredentialClockTest` **2 项**通过；完整隔离测试实际 **295 项，0 失败/0 错误/0 跳过**，覆盖资格新增/首次审核/历史、内嵌 pending、并发相反审核、权限/跨员工、日期/无期限、固定 Clock、跨午夜等锁、审核撤权/重放、审计失败回滚、分页、旧 T025-A 回归和迁移前记录不补造历史。
- 迁移列与实际字段由 `SchemaMigrationTest` **12 项**校验；`data-model.md`/`task-data-constraints.md` 两份清单同步。
- 本批未实现 PATCH、任职写入、资格撤销/重核、医生服务、排班和前端；资格自然到期/已有预约协调、跨店任职和角色并发仍留后续审定。
- 本批契约运行 Spectral：**0 错误、20 条既有警告**；OpenAPI `operationId` 共 **84** 个。


## T025-B2 第一批：资格撤销

新增 POST /staff/staff-members/{id}/credentials/{credential_id}/revoke。请求仅 basis（非空，最多300字符）、expected_version（正整数），以及 Idempotency-Key；权限仍为全局组织负责人。只允许 verified→rejected，追加 action=revoke 历史，与首次驳回区分。不允许重核或修改证件事实。

成功返回现有 CredentialDetailResult；幂等重放返回当前权威视图。版本冲突或终态重复操作409；未授权403，归属不符404，非法输入422；使用既有错误码。权限在幂等锁前后及业务锁后重查。原核验人/时间保留，撤销人/时间/依据进入历史。不更改账号、角色、会话、预约、排班或资源占用；自然到期不自动改审核状态。

V008只扩展历史动作/转换约束，不修改V001–V007。不代表资格准入、预约协调或审核页面已经完成；统一未来时段校验及下游接入仍待实施。

## T025-B2 第二批：共享资格判断（2026-10-27）

新增 CredentialValidity（日期/半开时段规则）与 StaffEligibilityService（真实事务内员工锁和资格读取）。员工详情的单证有效性与医生角色授予使用同一日期规则；授予dentist角色在既有维护锁/account锁之后取得staff锁，锁后取一次当前上海日期，避免等待跨午夜时使用旧日期。资格撤销不反向取得account锁。

未来区间[start,end)至少有一张已核验dentist_practice资格完整覆盖；不拼接多张部分覆盖资格。午夜结束不多算一天；多证中一张失效不影响另一张完整有效资格。自然到期只改变动态有效性，不篡改审核状态。非牙医或离岗返回不具备资格；无事务调用拒绝，保证行锁不会自动提交后立即释放。

本服务只回答执业资格，不替代门店任职/项目能力/排班/停诊/资源/患者同意检查。未来排班与预约消费者尚未接入，不能宣称接诊准入或已有预约协调已经实现。新代码未热更新当前演示jar；用户仍可使用已启动的管理页面。

## 资格证件事实受控读取（2026-11-20）

审核人需要在实际核对原件后才能作出首次审核决定。`GET /staff/staff-members/{id}/credentials/{credential_id}/facts` 仅对唯一组织负责人开放，按员工与资格双重 ID 精确读取证书编号、类别、有效日期、无期限声明、审核状态和版本。普通员工详情、门店范围详情、列表和审核历史继续不含证书编号。跨员工 ID 返回 404，门店负责人、前台、系统管理员和患者不能读取。响应设置 `Cache-Control: no-store`。

每次成功读取写一条访问审计，只记录操作者、资格 ID 和版本，不记录证书编号；审计写入失败则本次读取失败。证书事实读取不代表已核实原件，也不自动改变资格状态。管理端现已接入受控读取、首次审核和撤销，隔离测试通过并已发布；组织负责人真实账号的页面点按仍待验收，不能据此宣称现场审核验收已完成。
