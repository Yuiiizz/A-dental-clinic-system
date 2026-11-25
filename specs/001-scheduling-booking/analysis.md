# 规格—方案—任务 一致性审查报告

**对象**：`specs/001-scheduling-booking/` 的 spec.md、plan.md、research.md、data-model.md、contracts/api.md、contracts/permissions.md、tasks.md、quickstart.md、task-data-constraints.md。
**依据规则**：`.specify/memory/constitution.md` 1.0.0。
**方式**：静态交叉核对，未修改任何文件、未运行应用、未安装依赖、未连接数据库。
**日期**：2026-10-21。

## 结论摘要

24 条功能需求（FR-001～FR-024）均能找到实际实现任务和验收任务，覆盖完整；意向医生 / 确认医生 / 实际接诊医生的区分、自助与前台共用号源、正常自助不审批但保留前台、限制政策默认不启用、第一阶段不越界到诊疗收费库存成本等关键点，在规格、数据模型、接口与任务之间基本一致，未发现违反 constitution 的原则性问题。

主要风险集中在**数据模型的"设计增补"与"基线字段清单"未对齐**：多处关键字段只在增补正文出现、未进入字段表，而 tasks.md 的迁移任务把字段表当作"逐字段约束原文"，实现者照表建库会漏掉 `confirmed_dentist_id`、`restriction_enabled` 等核心字段（详见"严重"）。此外存在若干字段可空性矛盾、唯一约束语义矛盾、以及少数接口/页面缺口。

---

## 一、严重问题

### S1. "设计增补"字段未整合进"基线字段清单"，迁移字段表缺关键列（系统性）

- **位置**：`data-model.md` 的「设计增补与修正」01～07 各节 vs「基线字段清单」各表；`task-data-constraints.md` 全文同步缺失。
- **具体矛盾**：增补正文新增/修改的字段，未写进底部字段表。逐项核对：

  | 表 | 增补要求（节） | 字段表缺失 |
  | --- | --- | --- |
  | appointment | 01 新增 confirmed_dentist_id | 缺失（T009 却引用它做医生排他约束） |
  | credential | 06 新增 verification_state、no_expiry_confirmed | 缺失（T025 引用 verification_state） |
  | booking_policy | 04 新增 approved_at、status、restriction_enabled；07 新增 version | 全部缺失（T061 引用 status/restriction_enabled） |
  | booking_event | 04 新增 reviewed_by | 缺失（T062 引用 reviewed_by/reviewed_at 成组） |
  | contact_record | 05 新增 dispatch_key、lease_until、attempt_count、last_error_code；07 kind 增"预约申诉" | 全部缺失（T049 引用 dispatch_key，T064 引用申诉 kind） |
  | auth_session | 07 新增 client_type、csrf_token_hash | 缺失（T012 引用 client_type 隔离） |
  | patient | 07 新增 version | 缺失（POST /me/profile 依赖 expected_version） |
  | appointment_change | 07 新增 version | 缺失（答复/落实 expected_version 依赖） |

- **影响**：tasks.md T008/T009 把 `task-data-constraints.md` 称为"完整逐字段原文"，并要求"逐字段实施"。照字段表建库会漏掉上述列，其中 `confirmed_dentist_id` 是替代 `intended_dentist_id` 承担医生排他约束的核心快照字段，`restriction_enabled`/`status` 是"限制默认不启用、草稿可空"的落点。漏掉将直接导致 T009/T010 无法断言"旧意向医生排他已替换""辅助表结构符合附件"。
- **建议**：在 `data-model.md` 与 `task-data-constraints.md` 的字段表中补齐这些列（或在每张表上方加"增补字段见第 X 节"并在表内列出），保证两文件一致，使迁移任务有单一、完整的字段来源。

### S2. booking_policy 字段表与增补自相矛盾（可空性 + 缺列）

- **位置**：`data-model.md` booking_policy 表（approved_by、effective_at 标 `R`）；`task-data-constraints.md` 同表；对照增补 04"草稿可空，发布必填"。
- **具体矛盾**：字段表把 `approved_by`、`effective_at` 标为必填（R），但增补 04 明确二者在草稿可空、仅发布时必填；且表内没有 `status`（draft/published/retired）、`restriction_enabled`、`approved_at`。
- **影响**：按字段表建表后，任何草稿政策都无法保存（批准人/生效时间被强制必填），与"限制默认不启用、每店先有已发布基础政策""草稿"状态直接冲突，破坏 FR-018/SC-006 的验收前提。
- **建议**：将 approved_by、effective_at 改为可空（N），新增 approved_at、status、restriction_enabled 三列，并保留 UNIQUE(branch_id,version_no)，用应用层/CHECK 表达"发布时 approved_by/approved_at/effective_at 必填、restriction_enabled 默认 false"。

### S3. appointment.request_key 全局唯一约束与幂等作用域矛盾

- **位置**：`data-model.md` appointment 表 request_key 标 `R;UQ`（"创建申请幂等键"）；`contracts/api.md` 通用约定"Idempotency-Key 作用域为当前账号+操作"；`data-model.md` 增补 05 operation_request 的 `UNIQUE(actor_account_id,operation,request_key)`。
- **具体矛盾**：幂等键的作用域被描述为"账号+操作+键"（operation_request 也按此建唯一约束），但 appointment.request_key 是**全局唯一**。若 request_key 是客户端传入的 Idempotency-Key，两名不同患者/账号使用相同字符串会被全局唯一约束误判为 409 冲突。
- **影响**：可能错误拒绝不同账号的合法请求，破坏 FR-008 的幂等正确性。
- **建议**：明确 request_key 语义——若为客户端幂等键，去掉 appointment 上的全局 UQ，改按 (actor, operation, key) 作用域（与 operation_request 一致）；若为服务端生成的全局唯一值，写明生成规则并与客户端 Idempotency-Key 的映射关系。**（此为待确认设计点，标注为疑问亦可，但当前两种表述并存属于矛盾。）**

---

## 二、重要问题

### I1. role.code 枚举缺"库管、财务"两角色

- **位置**：`data-model.md` role 表 code 字段仅列 `patient、frontdesk、dentist、owner、sysadmin`（5 个）；同表"含义"及 `contracts/permissions.md`、`quickstart.md` 均要求"七类固定职责"含库管、财务，且合成数据要求"本店前台+其他店库管"兼岗账号用于越权测试。
- **矛盾/影响**：缺少库管、财务角色代码，T015 的"前台+库管组合不拼接权限"用例与 T022 的合成数据无法构造。
- **建议**：role.code 补全七类（库管、财务），或在迁移中预置其 code 供后续模块。

### I2. resource_unavailable 缺 state/recorded_by/recorded_at，与 dentist_unavailable 不对称

- **位置**：`data-model.md` resource_unavailable 表（仅 unavailable_id/resource_id/starts_at/ends_at/reason）对比 dentist_unavailable 表（有 state=有效/已撤销、recorded_by、recorded_at）。
- **矛盾/影响**：资源故障/维护无法记录"已解决/撤销"与经办人，与"维修恢复须核实"、FR-013"资源不可用先停止新增"的协调闭环不匹配，也无法留痕。
- **建议**：为 resource_unavailable 增补 state（有效/已撤销）、recorded_by、recorded_at，与 dentist_unavailable 对齐。

### I3. 六张新增辅助表缺字段级类型/长度/可空表

- **位置**：`data-model.md` 增补 02～05 的 identity_challenge、branch_booking_config、branch_opening_interval、restriction_event、operation_request、queue_counter 六表，仅在正文半结构化描述，未像基线表那样给全类型/长度/可空。
- **矛盾/影响**：T009 要"逐字段"建这六张表，但若干字段未给类型或长度（如 identity_challenge.token_hash 未给长度、operation_request.result_code/result_ref 未给长度、branch_booking_config 各分钟数字段未给类型）。实现者需自行猜测，违反"不隐藏更改"原则，且不同实现会不一致。
- **建议**：为六张表补与基线表同规格的字段类型/长度/可空/键表。

### I4. 已发布排班"修改"路径缺失

- **位置**：`spec.md` US1 场景 4「已有确认预约，修改影响其执行的班次→列出受影响预约并协调」；`data-model.md` schedule 表"已发布变更带 version"；`contracts/api.md` 员工工作台仅有 GET/POST /staff/schedules、publish、suspend，无 PATCH/PUT 修改已发布班次；`tasks.md` T023「已发布改动影响」。
- **矛盾/影响**：若"修改班次"只能通过"暂停旧班+新建发布"表达，需写明；否则 US1 场景 4、FR-002/FR-013 的"修改影响班次→协调"无接口承载，T023/T031 相应断言无法落地。
- **建议**：明确"修改已发布排班"的接口与状态流转（修改=暂停+重建，或新增 PATCH 接口并带 expected_version），并同步 T023 用例。**（待确认设计点。）**

### I5. 复诊"最多一笔有效预约"、改约"同一原单最多一个已确认后继"无数据库兜底

- **位置**：`data-model.md` followup_plan/appointment.previous_id 约束；`状态与事务` 说明"复诊归属、停诊和政策必须在持锁后重读校验"。
- **矛盾/影响**：与 queue_ticket.appointment_id、booking_event.appointment_id 的 UQ 不同，这两条"最多一个"规则没有部分唯一索引兜底，完全依赖患者锁串行化。若锁边界或实现有疏漏，可能产生同一计划两笔有效预约或同一原单两个后继（FR-011/FR-024）。
- **建议**：评估增加部分唯一索引（如 `appointment(followup_plan_id) WHERE state IN (有效集合)`、`appointment(previous_id) WHERE state='已确认'`）作兜底，并记录为何采用/不采用。**（疑问，非确认缺陷。）**

### I6. "过号"状态在本阶段无产生路径

- **位置**：`spec.md` FR-017（区分过号）、FR-020（已签到过号不计入）；`data-model.md` queue_ticket.state 含"过号"；`contracts/api.md` 无过号/已接诊转换端点（"本阶段不开放叫号"）；`tasks.md` T059 却声称验证"过号/已签到不能记未到"。
- **矛盾/影响**：本阶段没有任何接口或任务把 queue_ticket 置为"过号"，FR-017 的过号区分与 FR-020 的过号豁免路径无法被直接演练；T059 的断言无对应实现。虽 no-show 的"从未签到"门槛可隐式排除过号，但"过号"状态本身不可产生。
- **建议**：明确过号由前台人工标记（补接口/任务），还是整体推迟到叫号模块，并相应调整 FR-017/T059 措辞或补任务。**（待确认设计点。）**

---

## 三、一般问题

- **G1 术语漂移**：`spec.md` FR-017 用"离店"，`data-model.md` queue_ticket.state 用"已退出"（另有呼叫中/已接诊）。建议在字典注明等价关系。
- **G2 注册字段缺口**：`contracts/api.md` POST /auth/patient-register 未收 allergy_notes，但 `patient.allergy_notes` 在 allergy_status 为"有/不确定"时必填；注册时选"有过敏"无法立即填说明，只能事后 POST /me/profile。建议注册接口条件必填或说明分两步。
- **G3 多项目组合确认路径不清**：FR-005 要求"复杂多项目组合转人工核实"，appointment_item 是多行表，但 POST /me/appointments 与 POST /staff/appointments/{id}/confirm 都只收单个 item_id，多项目如何确认无明确接口路径。**（疑问。）**
- **G4 consent_basis 可空与人工渠道必填矛盾**：FR-012 要求前台代办保留"确认依据"，但 `appointment.consent_basis` 标可空（N），未按 channel 表达"人工渠道必填"。建议加条件约束或在应用层明确。
- **G5 门店一致冗余未声明为约束**：appointment.branch_id 与 schedule.branch_id、queue_ticket.branch_id 与 appointment.branch_id 的一致性仅在 prose 说明，未在字段表/迁移约束显式列出，建议落地为 CHECK 或明确应用校验。
- **G6 状态机缺协调链路**：`状态与事务`表未覆盖 appointment_change 的 propose→答复→落实全链路及 coordination_state 的流转（仅在 appointment_change.state 描述），建议补一张协调状态机表。
- **G7 T006 粒度偏大**：`tasks.md` T006 一次把整个 api.md 展开为 OpenAPI（覆盖 24 条 FR 的所有 DTO/枚举/错误）。建议按身份/排班/预约/协调/签到/政策分组拆分，便于评审与并行。
- **G8 引用未定义实体**：`data-model.md` 提到 visit_addendum、care_item 但未定义（明确属后续临床模块范围）。建议标注"后续模块实体"以免被误读为缺失表。
- **G9 排他约束表达式未列出**：`data-model.md` 用普通 timestamp 起止列描述，未给出患者/确认医生/资源三处排他约束的具体表达式与"有效状态"集合；T010 仅断言"存在"。建议在迁移约束附件中列出表达式。

---

## 四、FR 覆盖核对（简表）

24 条 FR 均有实现任务与验收任务，非仅靠需求编号凑数（详细映射见 tasks.md「需求到任务对应」）：

| 需求 | 主要实现任务 | 主要验收/测试任务 | 结论 |
| --- | --- | --- | --- |
| FR-001 | T024/T028/T029/T030 | T023/T031/T068 | 完整 |
| FR-002 | T024～T029 | T023/T031/T068 | 完整 |
| FR-003 | T016/T017/T021 | T018/T032/T039 | 完整 |
| FR-004 | T033/T037 | T032/T039 | 完整 |
| FR-005 | T026/T034/T035 | T032/T039 | 完整（多项目路径见 G3） |
| FR-006 | T034/T035/T036 | T032/T039 | 完整 |
| FR-007 | T023/T027/T034/T035 | T031/T032/T039 | 完整 |
| FR-008 | T019/T035/T036 | T032/T039 | 完整（见 S3） |
| FR-009 | T036/T038 | T032/T039 | 完整 |
| FR-010 | T041/T043 | T040/T044 | 完整 |
| FR-011 | T042/T043 | T040/T044 | 完整（兜底见 I5） |
| FR-012 | T046/T050 | T045/T052 | 完整 |
| FR-013 | T047/T050/T051 | T045/T052 | 完整（修改路径见 I4） |
| FR-014 | T048/T050/T051 | T045/T052 | 完整 |
| FR-015 | T054/T055/T056 | T053/T059 | 完整 |
| FR-016 | T055/T056 | T053/T059 | 完整 |
| FR-017 | T041/T055/T056/T062 | T040/T053/T060 | 完整（过号见 I6） |
| FR-018 | T061/T063 | T060/T067 | 完整（缺列见 S1/S2） |
| FR-019 | T041/T062/T063/T064 | T060/T067 | 完整 |
| FR-020 | T035/T042/T043/T062/T063/T064 | T060/T067 | 完整 |
| FR-021 | T012/T013/T014/T020/T021/T038/T046/T058 | T015 | 完整 |
| FR-022 | T011/T019/T049 | T068/T070 | 完整 |
| FR-023 | T049/T050 | T045/T052 | 完整 |
| FR-024 | T034/T035/T036/T041/T042 | T032/T039/T040 | 完整 |

成功标准 SC-001～SC-007 均有对应验收任务（T031/T039/T044/T052/T059/T067/T069/T070 等）。

## 五、已确认无误的关键点（简要）

- **三种医生语义区分正确**：意向（intended_dentist_id，申请意向、不作防重）／确认（confirmed_dentist_id，排班赋值、排他快照）／实际接诊（visit_record.dentist_id，后续模块）在增补 01 明确区分。
- **自助与前台共用号源**：FR-012 + api POST /staff/appointments"同一校验，不绕过资源规则" + T046"仍调用 BookingService"；正常自助直接确认、无需审批，前台岗位保留（FR-012、T050）。
- **限制政策默认不启用**：FR-018 + 增补 04 restriction_enabled 默认 false；普通取消／未到／已签到过号／诊所原因／申诉五类区分清楚（FR-017/019/020、booking_event.kind）。
- **首阶段范围不越界**：spec/plan/tasks 多处声明本阶段到签到为止，诊疗、收费、发票、库存、成本为后续模块，未误称完成。
- **并发与事务顺序**：锁顺序（患者→医生→排班→资源→子记录，签到末尾锁 queue_counter、禁止反锁患者）与取消/签到/停诊的串行化设计一致，无死锁顺序错误（兜底唯一约束见 I5）。
- **越权防护**：患者端从会话取本人、员工按记录 branch_id 过滤、角色门店不拼接、撤权递增 auth_version 即时失效，均有对应任务与测试。

## 六、指标

- 功能需求总数：24（覆盖 24/24，100%）
- 成功标准：7（均有验收任务）
- 任务总数：74（Phase1～9 连续、无重复编号、均未勾选）
- 严重问题：3；重要问题：6；一般问题：9
- Constitution 原则冲突：0（未发现违反项）

## 七、下一步建议

1. 优先修复 S1/S2：让 `data-model.md` 与 `task-data-constraints.md` 的字段表补齐增补字段并修正 booking_policy 可空性，再进入 T008/T009。
2. 澄清 S3（request_key 语义）与 I4（排班修改路径）、I6（过号路径）三个设计点。
3. I1/I2/I3 的字典与字段补全可与迁移任务同期处理。
4. 一般问题可在 OpenAPI（T006）与迁移设计阶段逐项落实，不阻塞整体排期。
