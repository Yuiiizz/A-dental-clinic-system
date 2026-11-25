# 门店创建、列表、详情、资料修改、状态与营业区间（T024-A/B/C）

日期：2026-10-25。范围：T024-A 的 `GET /staff/branches`、`POST /staff/branches`、`GET /staff/branches/{id}`，T024-B 的 `PATCH /staff/branches/{id}`、`POST /{id}/submit|verify|disable|reopen`，以及 T024-C 的 `GET/PUT /staff/branches/{id}/opening-intervals`。**未实现**医生排班、患者预约接口、前端；T024 整体仍未完成，T021 整体仍未勾选。

## 接口

- `GET /api/v1/staff/branches?cursor&limit`
  - 有界游标分页（默认 20，最大 100）；按 `branch_id` 升序，cursor 为上一页最后一个 branch_id。
  - 仅返回同时满足：同一 role 具有 `branch.configure` + 该 role 的 `role_branch_scope` + 有效且 `verified_at` 非空的 `staff_branch`。
  - sysadmin/frontdesk 无 `branch.configure` 返回 403；患者会话被安全过滤器拒绝；无可见门店返回空页。
  - 列表项：branch_id、code、name、state、version、manager_id。
- `POST /api/v1/staff/branches`（`Idempotency-Key` 必填）
  - 请求：code、name、address、phone、license_no、license_valid_to?、opening_hours、manager_id。
  - 不接受 `organization_id`、`state`、`verified_by`、`verified_at`、`license_no_expiry_confirmed`；未知字段 422。
  - 授权：当前有效 `staff_web`、已完成强制改密、员工在岗、唯一 organization 的 `responsible_staff_id`、owner 角色具有 `branches.create`；sysadmin 不绕过。
  - `manager_id` 首期必须等于当前创建者。
  - 创建 `state=草稿`、`version=1`、核验字段为空、`license_no_expiry_confirmed=false`。
  - 同事务只建立该店：创建者 `staff_branch`（enabled、verified_at=Clock）和其既有 owner 角色的 `role_branch_scope`；不新授 owner、不修改其他门店。
  - 授权变化按既有规则递增 `account.auth_version` 并撤销该账号全部会话；首次响应 `reauthentication_required=true`，客户端须重新登录。
  - 幂等重放重新鉴权并检查目标可见性，不再次授 scope、不改版本、不撤会话；重放响应 `reauthentication_required=false`。
  - code 唯一冲突安全 409；失败整体回滚门店/任职/scope/审计/幂等占位。
- `GET /api/v1/staff/branches/{id}`
  - 同一权限链；无权限目标与不存在统一 404。
  - 返回可编辑档案、manager_id、state、version、verified_by/at、license_valid_to、license_no_expiry_confirmed、reauthentication_required。
  - 不返回营业区间（下批按范围设计）、患者、财务、密码或会话信息。

## T024-B 资料修改与状态

- `PATCH /api/v1/staff/branches/{id}`：`Idempotency-Key` + `expected_version`；请求中未出现的字段不修改，必填字段显式 null 拒绝；拒绝 `manager_id`、`organization_id`、`state`、`version`、`verified_by`、`verified_at`、`license_no_expiry_confirmed` 及未知字段。
  - 草稿/待核验/停用可改档案字段；营业状态只允许名称、电话、营业说明、代码等非核验事实。
  - 营业状态修改执业地址、许可证号或许可有效期返回 422，须先停用，再修改并重新核验。
  - 许可事实变化自动令 `license_no_expiry_confirmed=false` 并清除 `verified_by/at`；普通 PATCH 不能把无期限确认置 true。
  - 审计记录 code/address/name/phone/license_no/license_valid_to/opening_hours、无期限标志和 verified_by/at 的前后值；清空用明确的空字符串表达，不以“未记录”代替。address 最长 300 字符仍写入，总编码仍受 2000 字符上限约束。
  - `manager_id` 本批不可变更；`opening_hours` 只是展示说明，不替代真实营业区间。
- `POST /{id}/submit`：草稿 → 待核验。
- `POST /{id}/verify`：待核验 → 营业；`verification_basis` 必填；有 `license_valid_to` 时按 Asia/Shanghai 日期和注入 Clock 判断不得过期；无有效期时必须显式 `license_no_expiry_confirmed=true` 并提供依据。核验人取当前 staff、时间取 Clock，不接受客户端伪造。审计同时记录本次核验的 license_no、license_valid_to、无期限标志和 verified_by/at，能辨认核验的是哪个许可版本。
- `POST /{id}/disable`：营业 → 停用；原因必填。存在 `schedule.state='已发布' AND ends_at>now()` 或 `appointment.state IN ('待确认','已确认','候诊中','接诊中')` 时 409，返回精确 `schedule_count`/`appointment_count`/最早开始/最晚结束，不含患者身份。
  - 当前预约查询不对 `ends_at` 再筛选：计划结束时间已过但仍处活动状态的记录会继续阻止停用。这是防止遗漏未结事项的保守规则；计划结束不等于业务已结束，不静默自动取消。
  - `待确认` 在现有契约中不占号，不称为资源占用；保留其阻止停用是因为它是未处理事项，须先取消/关闭。`待协调` 是活动预约的协调状态，与 `待确认` 区分，但仍属于未结束事项。
- `POST /{id}/reopen`：停用 → 待核验；清除核验标记和无期限确认，不直接恢复营业、旧预约或排班。
- 所有写操作先做只读鉴权，再锁 branch 行；无权限员工不会先等待/持有目标门店行锁。锁后、经幂等锁等待后及重放时继续复查权限/可见性，不能用前置检查替代后续检查。
- 版本、状态、审计与幂等结果同事务；版本冲突、状态不符、停用影响冲突均零门店/审计/幂等副作用。未在持 branch 行锁后反向获取账号维护锁。
- 状态动作重放返回原操作结果（原 state/version），不倒退当前门店；当前状态应通过 GET 读取。成功动作后重放旧键不重复递增版本或审计。

## T024-C 营业区间

- `GET /{id}/opening-intervals?from_date&to_date`：两端包含、最多31个连续自然日；按 valid_on/starts_at 稳定排序；无权限/不存在统一404。版本和区间在**同一条 SQL 快照**中读取，不会混合旧版本与新区间。
- `PUT /{id}/opening-intervals`：`from_date`、`to_date`、`intervals`、`expected_version`、`Idempotency-Key`。
  - `branch.version` 是区间集合聚合版本，不新增 opening_version/表。
  - 每次只替换日期范围内记录；范围外保留且不删除重建。
  - 空 `intervals` 表示闭店，仍检查历史保护/重叠/覆盖。
  - 区间归属 `valid_on` 必须与 Asia/Shanghai 开始日期一致；结束最晚到次日 00:00；跨午夜须按自然日拆成多行。
  - 同店区间不允许重叠，允许相邻；重复/范围外/未知字段/超31日/单次超过1000个区间/起点不早于终点拒绝。
  - 今天已开始（starts_at <= now）的区间必须在请求中原样保留；**所有请求中 start<=now 的区间必须与已有事实完全相同**，禁止用普通 PUT 凭空回填过去或已开始的营业事实。今天未开始和未来区间可调整；历史日期 from_date 不可写。
  - 历史/跨午夜保护在**取得 branch 行锁后的 business 回调**中按同一个 now 计算；等锁跨到次日后新键拒绝，已成功旧键重放只返回当前视图、不重新执行历史检查。
  - 覆盖冲突按“替换后完整区间并集”判断：只要未结束已发布班次、已确认/候诊中/接诊中预约仍被连续覆盖即可；中间空隙不能算覆盖，扩大/同值可成功。**待确认不占号，不参与营业区间覆盖校验**；待协调只是已确认预约的协调状态，不排除。整店停用时待确认仍按 T024-B 规则作为未处理事项阻止，这是不同业务语义。
  - 影响摘要按排班/预约记录去重计数，返回最早开始/最晚结束，不返回患者身份；下游排班/预约写入尚未接入同一 branch 锁协议，本批只有合成夹具。
  - 同值替换不递增版本/不写审计；过期版本、覆盖不足、历史/重叠冲突均零写入。
  - 审计记录范围、替换前后区间数量；幂等摘要含 branch/范围/版本/规范化区间，输入顺序不影响。
  - 幂等重放不重复写入，但**返回当前权威视图**（当前 branch.version 与当前区间），不承诺原响应快照。

## 授权与并发

- 账号维护锁 `734192014` 先于幂等 advisory 锁；取得幂等锁后再锁 organization/account 行并重新核验负责人、owner 权限与 manager。
- 等待账号锁期间被撤权：重新鉴权失败，不创建、不补回 scope。
- 创建不新增业务表；新增 V005 仅 `branch.license_no_expiry_confirmed` 与 `branches.create` 权限（仅 owner）。
- 列表/详情不调用 `requireBranch` 的简化版本；本服务显式要求 `staff_branch.verified_at IS NOT NULL`，不笼统声称既有检查已覆盖。

## 测试

真实隔离 PostgreSQL + HTTP（`BranchIntegrationTest`，40 项）及日期单元测试 `BranchLicenseClockTest`/`OpeningIntervalClockTest`（各1项）：

- T024-A 创建/列表/详情、角色与门店隔离、幂等、并发的既有测试继续通过。
- T024-B 合法生命周期（草稿→待核验→营业→停用→待核验→营业）、非法状态转换、核验依据/过期许可/无期限确认、伪造核验人字段。
- PATCH 缺省/显式 null、营业状态关键档案拒绝、cross-store/角色拼接/患者/未改密拒绝、服务端字段伪造拒绝。
- 版本冲突零副作用；同版本并发恰一成功；PATCH 幂等重放、异内容冲突、撤权后重放 404 不恢复 scope。
- 状态动作 submit/verify/disable/reopen 各自同键重放：返回原操作结果，不重复版本/审计，后续状态不被旧键倒退；非法转换、缺少核验依据、伪造 verified_by/verified_at、未核实任职均拒绝。
- 鉴权先于行锁：独立连接持 branch 行锁时无权限员工直接 404、不进入等锁；有权限请求等锁期间撤 scope，释放后 404 且零变更。
- 审计：停用后修改地址（300 字符最大合法值）/代码/许可事实及显式清空有效期，审计含前后值和明确空字符串；核验审计含许可号和有效期；审计失败整体回滚。
- 中途故障：测试 schema 在审计写入处强制失败，门店版本/名称/审计/幂等整体回滚；移除故障后同键重试成功。
- 停用影响：未来/进行中已发布班次、已确认预约、待确认/待协调活动记录分别阻止；已完成/已取消不阻止；impact 精确 count/时间且不含患者资料。
- 固定 Clock 的上海日期边界：有效期当天可核验，前一天不可；集成测试另用当天/前一天验证端点边界。
- 审计白名单字段限额：address 300 字符可写入；16 个 200 字符字段的总编码超过 2000 时仍拒绝（共享审计集成测试）。
- T024-C：单日/31日/超31日、较远未来、历史拒绝、今天已开始区间原样保护、新增过去区间拒绝、GET 有界/排序、相邻/重叠/范围外/重复/未知字段、午夜终点/跨日拆分；空列表闭店、范围外不变；同值/扩大成功、午休空隙/覆盖不足拒绝；**仅待确认预约可缩短/闭店，已确认待协调预约单独拒绝**；版本冲突、同版本并发、幂等重放当前视图、异内容、撤权后 404；删除旧区间后审计故障整体回滚并同键重试；删除旧区间后中途故障回滚；等锁跨午夜拒绝、成功请求跨日重放不重复写；GET 与写入交错返回单一快照。
- 固定 Clock：许可证日期和营业区间“今天/历史”边界均用注入 Clock 直接测试。
- 契约运行 Spectral；真实 UI/微信联调未做。

**测试数**：全量 257 项通过，0 失败/0 错误/0 跳过；其中 BranchIntegrationTest 40 项、BranchLicenseClockTest 1 项、OpeningIntervalClockTest 1 项。

## 未完成

排班、前端、真实 UI/微信联调、真实并发业务验收未做。T024 仍不勾选；T021 仍不勾选。

### 时间精度补充（2026-10-25 Codex）
营业区间写入采用整秒精度，非零小数秒返回422；.000与整秒规范化一致。读取保留数据库已有时间精度，不截断历史小数秒数据。历史小数秒记录不会自动改写；当前整秒写入接口不能原样提交这类记录，若实际存在需另行受控处理，不通过隐式舍入修正。锁内一次取得now，上海today、历史保护与覆盖判断均复用该时刻。
