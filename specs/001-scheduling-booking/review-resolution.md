# 审查决议落实记录

**对象**：`specs/001-scheduling-booking/` 的 spec.md、plan.md、data-model.md、task-data-constraints.md、contracts/api.md、contracts/permissions.md、tasks.md、quickstart.md。
**依据**：[analysis.md](analysis.md) 的 S1～S3 / I1～I6 / G1～G9，以及 8 项用户决议。
**方式**：直接修改规格类文件；未改 Word、constitution、tools，未运行代码/安装/数据库/还原图片/git。
**日期**：2026-10-21。

本记录逐项说明分析报告 18 个问题的处理结果。历史审查结论保留在 [analysis.md](analysis.md)，不回溯改写。

---

## 一、严重问题（S1～S3）

### S1 设计增补字段未整合进字段清单 —— 已修复（决议1）
- `data-model.md` 与 `task-data-constraints.md` 字段清单标题改为「最终字段清单（基线＋设计增补已合并）」，不再存在两份互相矛盾的定义；「变更说明」保留为解释性文字。
- 已补齐：`appointment.confirmed_dentist_id`、`credential.verification_state/no_expiry_confirmed`、`booking_policy` 全列、`booking_event.reviewed_by`、`contact_record.dispatch_key/lease_until/attempt_count/last_error_code/kind`、`auth_session.client_type/csrf_token_hash`、`patient.version`、`appointment_change.version`。

### S2 booking_policy 字段表与增补自相矛盾 —— 已修复（决议2）
- `booking_policy`：`status` 限 draft/published/retired；`restriction_enabled` 默认 false；`approved_by`、`approved_at`、`effective_at` 草稿可空（N）、发布必填；`version` + `UNIQUE(branch_id,version_no)`；已发布版本不可原地改参数。
- 同步 `tasks.md` T061、`contracts/api.md` 政策发布接口。

### S3 appointment.request_key 全局唯一与幂等作用域矛盾 —— 已澄清（决议3）
- 客户端 `Idempotency-Key` 只存入 `operation_request.request_key`，唯一作用域 `UNIQUE(actor_account_id,operation,request_key)`。
- `appointment.request_key` 保留全局唯一，但语义改为**服务器生成的内部 UUID 字符串**，非客户端键。
- 同账号同操作同键返回原结果，不同摘要 409；不同账号使用相同客户端键不冲突；成功时 operation 记录引用预约 id。

---

## 二、重要问题（I1～I6）

### I1 role.code 缺库管、财务 —— 已修复（决议4）
- 七角色代码统一为 `patient、frontdesk、dentist、owner、sysadmin、inventory(库管)、finance(财务)`，同步 `contracts/permissions.md`。

### I2 resource_unavailable 缺状态/经办/发生时间 —— 已修复（决议4）
- 增补 `kind`（维护/故障）、`state`（有效/已撤销）、`recorded_by`、`recorded_at`、`resolved_at`、`resolve_type`（误登记撤销/已修复）、`resolve_basis`、`resolved_by`，与 dentist_unavailable 对齐。
- 新增 `POST /staff/resource-unavailability/{id}/resolve`；恢复须人工核实（resolved_by 取当前会话、不覆盖原登记人，解除一条后仍检查其他有效禁用记录），不因预计结束时间已过自动恢复可约。同步 T047、FR-013。

### I3 六张辅助表缺字段级类型/长度/可空 —— 已修复（决议1）
- `identity_challenge`、`branch_booking_config`、`branch_opening_interval`、`restriction_event`、`operation_request`、`queue_counter` 六表补齐与基线表同规格的类型/长度/可空/默认/约束。

### I4 已发布排班「修改」路径缺失 —— 已澄清（决议5）
- 草稿班次可带 `expected_version` 修改；已发布班次的医生、门店、时段或服务发生实质变更，通过「暂停旧班次→新建并发布→协调原预约」实现，不提供直接改写已发布班次的接口。
- 同步 `spec.md` US1 场景4、`contracts/api.md`、`tasks.md` T023。

### I5 复诊/改约「最多一个」无数据库兜底 —— 已修复（决议6）
- 新增两个部分唯一索引：
  - `appointment(followup_plan_id) WHERE state IN ('已确认','候诊中','接诊中')`（一个复诊计划至多一笔活动预约）；
  - `appointment(previous_id) WHERE confirmed_at IS NOT NULL`（同一原单至多一个已确认后继；已确认后即便取消，仍阻止第二个已确认后继；未确认候选不受限）。
- 同步 `data-model.md` 兜底小节、`tasks.md` T009/T010/T034/T042。

### I6 「过号」状态本阶段无产生路径 —— 已澄清（决议7）
- 过号与回队留给后续叫号模块，本阶段不新增叫号接口/任务。
- 本阶段仅以合成过号状态验证「已签到者不能被记未到」；同步 FR-017、`tasks.md` T059、`quickstart.md`。

---

## 三、一般问题（G1～G9）

- **G1 术语漂移（离店/已退出）** —— 已修正：「已退出」是候诊队列终态（离开队列），「离店」是导致退出的行为/原因之一（如已签到后离店取消），二者不写成同义词；`data-model.md`/`task-data-constraints.md` 的 queue_ticket 说明已注明该区分。
- **G2 注册缺 allergy_notes** —— 已修复（决议7）：`POST /auth/patient-register` 增加 `allergy_notes?`，allergy_status 为有/不确定时必填；同步 api.md、T017。
- **G3 多项目组合确认路径不清** —— 已澄清（决议7）：本阶段每笔预约（自助或前台代办）只确认单个明确项目或复诊建议；复杂多项目需求先人工评估、可安排检查评估，暂不实现多项目组合预约；`appointment_item` 多行表不作为完整组合预约误导。同步 FR-005、api.md、T034。
- **G4 consent_basis 可空与人工渠道必填矛盾** —— 已修复（决议8）：人工渠道（电话/微信登记/现场）代办与确认时 `consent_basis` 必填，自助渠道可空；同步 api.md、T046。
- **G5 门店一致冗余未声明为约束** —— 已注明（决议6）：branch_id/confirmed_dentist_id 等跨表一致性通过事务内校验或复合外键落地，**不写查询其他表的普通 CHECK**。
- **G6 协调状态机缺链路** —— 已修复（决议7）：`appointment_change` 补 待答复→已同意/已拒绝/已撤回→已落实 流转；同步 spec FR-014、api.md、T048。
- **G7 T006 粒度偏大** —— 已修复（决议8）：T006 按身份/基础配置排班/预约/协调/签到/政策拆为 6 个子步骤，保留 T006 编号，未全表重编号。
- **G8 引用未定义实体（visit_addendum、care_item）** —— 已注明：属后续临床模块范围，第一阶段不实现；本记录明确其范围，不改动字段清单。
- **G9 排他约束表达式未列出** —— 已修复（决议6，后续再修）：`data-model.md` 新增「排他约束与部分唯一索引（数据库兜底）」小节，给出患者、确认医生、资源三处 btree_gist + tstzrange(starts_at,ends_at,'[)') 排他约束与两个部分唯一索引的确定名称 SQL。

---

## 四、决议 → 问题对照表

| 决议 | 内容要点 | 对应问题 |
| --- | --- | --- |
| 1 合并数据字典 | 合并基线+增补为最终字段表；补全六辅助表；统一 timestamptz | S1、I3、G5 |
| 2 政策字段 | 草稿可空/发布必填；restriction_enabled 默认 false；状态与版本约束 | S2 |
| 3 幂等键语义 | 客户端键只存 operation_request；appointment.request_key 为服务器内部 UUID | S3 |
| 4 角色与资源恢复 | 七角色；resource_unavailable 经办/时间/撤销恢复；不自动恢复 | I1、I2 |
| 5 排班修改 | 草稿可改；已发布实质变更=暂停+新建发布+协调 | I4 |
| 6 数据库兜底 | 排他约束表达式 + 两个部分唯一索引；跨表校验用事务/复合外键 | I5、G9、G5 |
| 7 阶段边界 | 过号留后续模块；单一项目/复诊；allergy_notes、人工依据、协调状态流转 | I6、G2、G3、G6 |
| 8 任务同步 | T006 拆子步骤；修正受影响任务/验收/FR 映射 | G4、G7、G8、G1 |

所有开发任务（tasks.md）保持未勾选。

---

## 五、追加小范围修正（2026-10-21）

本轮对上一轮结果做小范围修正，不扩展功能、不写应用代码。要点：

1. **时间范围类型**：排他约束由 tsrange 改为 `tstzrange(starts_at, ends_at, '[)')`；三处排他约束与两个部分唯一索引给出确定名称（`ex_appointment_patient_overlap`、`ex_appointment_dentist_overlap`、`ex_resource_booking_overlap`、`uq_appointment_followup_active`、`uq_appointment_previous_confirmed`）与真实字段，移除 `CREATE UNIQUE INDEX ...` 占位。仅写入设计文档，未创建迁移文件。
2. **预约状态与协调状态分离**：「待协调」是 `coordination_state`，不是预约状态；已确认预约进入待协调不释放患者/牙医/资源占用。已完成预约的历史区间不纳入排他（接诊结束即释放、时段已过去，纳入会误阻未来排班），复诊活动预约唯一性按活动状态限制、不因保留历史而阻止下一次复诊；两种约束的状态集各自说明目的，不机械共用。
3. **资源恢复审计**：`resource_unavailable` 增补 `resolved_by`（N;FK:staff，实际操作人），与 resolved_at/resolve_type/resolve_basis 成组填写、不得覆盖 recorded_by；可用性判断为该时段无任何 state='有效' 的禁用记录，解除一条后仍检查其他记录。同步 resolve 接口（resolved_by 取当前会话）、T047/T052 验收。
4. **术语**：已退出＝队列终态，离店＝行为/原因，不写同义。
5. **发票范围**：开票是完整系统必要功能，「第一阶段不提供开票接口」仅暂缓、非删除；列入后续收费结算阶段，关联患者/门店/账单/收款/退费，保留患者申请查询、财务开票处理与权限；演示数据不表述为真实开票。

状态：以上均为静态核对，**尚未在 PostgreSQL 执行**任何 SQL 或迁移验证。
