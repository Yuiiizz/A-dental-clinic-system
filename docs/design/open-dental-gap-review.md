# Open Dental 参考设计差距核对（只读分析）

日期：2026-10-23。范围：只核对设计差距，输出最小优化建议；**未修改代码、契约、迁移、Word 或任务状态，未提交/推送**。T021-A 登录修复仍待 Codex 独立验收，本文件不宣称其通过。

> 2026-10-23 Codex 审定：方向采纳，首期不新增表；具体约束见 `docs/design/open-dental-decisions.md`。本文件已按审定更正措辞；**未批准任何 DDL**，后续实施仍逐项核对。

## 结论摘要

- **首期不新增表**。排班/营业/诊椅/设备、预约确认与到店事件、材料批次与折旧，现有 48 表迁移或 Word 60 表全量设计已有承载。
- 取消后“待重新安排”只作为预约工作台实施时的**候选**，**不立即改接口**；仅明确请求回访或有可核实人工协调依据才建待办，可关闭/暂缓，不自动追踪。
- 未预约复诊清单、治疗计划、收款分配、外加工、候补，均放到后续阶段；不把 Open Dental 的预约/诊疗/收费模块整体搬入。
- **不采用**其 `Confirmed` 定义字典、`Pattern`、`scheduleop`、`histappointment`、保险/编码/收费规则、`patient.Guarantor` 家庭账户和“熟人推荐即 referral”的建模。
- 本轮未找到独立 PPT/教材文件；只把项目 Word 报告当项目基线，不当成外部教材。Word 中的类图/ER 图是嵌入图片，本轮核对了文字说明、类到表映射和数据字典，未逐张目视核对图片连线。

## 1. 依据与基线

| 项 | 事实 |
| --- | --- |
| 项目 Word | 根目录 `1602240113徐乐怡牙科系统分析与设计.docx` 与 `系统分析与设计文档/` 同名副本 SHA256 相同（`2F3B9C26...CE7CA7`），即 `spec.md` 引用的报告。全文 60 表、495 字段、142 项外键；读取第 3.2、4.1–4.15、5、6.1–6.4、7 章及数据字典。 |
| 当前迁移 | V001 42 张 Word 基线表 + V002 6 张运行辅助表 = 48 表；T010 时点字段数 386。48/386 只是阶段基线，V004 等后续迁移会改变字段总数，**不是完成度**；当前 Java 业务代码仅 identity/shared，排班、预约、诊疗、收费仍未实现。 |
| 未建表 | Word 60 表中另有 18 张未进入首期：`queue_call`、`visit_addendum`、`care_item`、`asset_depreciation`、`asset_maintenance`、`operating_expense`、`stock_item`、`stock_batch`、`warehouse`、`purchase_receipt`、`purchase_receipt_line`、`stock_movement`、`bill`、`bill_line`、`payment`、`refund`、`invoice`、`invoice_line`。 |
| 当前规格边界 | `plan.md` 明确第一阶段不交付诊疗和收费写入口；`data-model.md` 只把 `visit_record`、`followup_plan`、`dentist_assignment` 作为受控查询/合成输入；本阶段每预约单一项目。 |
| Open Dental XML | `docs/references/OpenDentalDocumentation26-2.xml`，430 张表；本轮按实际 `summary`/`column` 读取，不以表名推测关系。官方说明：[预约](https://www.opendental.com/site/0_appointments.html)、[治疗计划](https://www.opendental.com/site/0_treatmentplan.html)。 |
| 未核对 | 项目内没有独立 PPT/教材；未核对老师课件原文的类图/范式/权限页。Word 类图/ER 图未逐张目视；未连接真实数据库、未做浏览器/接口验证。 |

“已覆盖”在本文件中指**设计或迁移有承载**，不表示接口、页面、测试已实现或验收。

## 2. Open Dental XML 证据索引

> 以下 XML 的 FK/关联说明按**逻辑引用**记录；未核对其 DDL，不声称物理约束。XML 未见某字段，不等于整个产品没有该能力，只代表本次读取的 26-2 文档未给出该字段。

| 编号 | 对象 | 实际 summary / column 证据 |
| --- | --- | --- |
| E1 | `appointment` | summary：预约可出现在 Appointments 模块，也可在 unscheduled list；同一对象还存 **Planned appointment**，planned 不排期而是被复制；另有 `histappointment` 保存历史。`AptStatus` 枚举含 Scheduled/Complete/UnschedList/ASAP/Broken/Planned。`Confirmed` 在 XML 中标注为 `definition.DefNum` 的逻辑引用，summary 明确“can also be used to show patient arrived, in chair, etc.”。另有 `DateTimeArrived`/`DateTimeSeated`/`DateTimeDismissed`。 |
| E2 | `schedule` / `operatory` / `clinic` | `schedule` summary：“One block of time. Either for practice, provider, employee, or blockout.” `SchedType` 区分 Practice/Provider/Blockout/Employee；`scheduleop` 把 schedule 块关联到 operatory；`operatory` summary：“Each row is a single operatory or column in the appts module.”；`clinic` summary：“usually a separate physical office location”。 |
| E3 | `recall` / `webschedrecall` | summary：每个患者每种类型只有一条 recall；字段 `DateDue`、`DateScheduled`、`DatePrevious`、`RecallStatus`、`RecallInterval`；`appointment.UnschedStatus` 用于 unscheduled/planned；`webschedrecall` 记录提醒发送尝试与结果。 |
| E4 | `treatplan` / `proctp` / `procedurelog` | `treatplan` 存 Saved/Active/Inactive 治疗计划，`TPStatus` 三态；`proctp` 是“copies of procedures attached to saved treatment plans”，含 `FeeAmt`/`PatAmt` 等计划快照；`procedurelog.ProcStatus` 含 TP（treatment planned）与 C（complete）等。 |
| E5 | `payment` / `paysplit` | `payment` summary：“Always has at least one split.” `PayAmt` 必须等于 splits 之和；`paysplit` summary：“Always attached to a payment. Always affects exactly one patient account and one provider.”，可关联 `ProcNum`/`ProvNum`/`ClinicNum`。 |
| E6 | `labcase` / `laboratory` / `labturnaround` | `labcase` 关联患者、实验室、可选预约；字段 `DateTimeDue`、`DateTimeCreated`、`DateTimeSent`、`DateTimeRecd`、`DateTimeChecked`、`LabFee`、`Instructions`；`laboratory` 是实验室档案；`labturnaround` 是实验室服务周转天数。 |
| E7 | `supply` / `supplyorder` / `equipment` | `supply` 是 dental/office supply（有 Price、LevelDesired 等）；`supplyorder`/`supplyorderitem` 是采购订单；`equipment` summary 明确“used for property tax tracking”，有 PurchaseCost/MarketValue。XML 未见批次效期、成本层、月折旧或期间费用字段；这些按本项目需求记录，不据此评价产品整体能力。 |
| E8 | `referral` / `patient` / `patientlink` | `referral` summary：“All info about a referral is stored with that referral even if a patient.”；`patient.Guarantor` 是“Head of household”；`patientlink` 记录 merge/clone。 |

## 3. 可决策矩阵

| # | 实际痛点 | 本项目已有（48表/Word 全量设计） | OD 证据 | 可借鉴思路 | 本地适配与风险 | 覆盖判断 | 建议阶段 | 最小改动 | 验收场景 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 预约状态、患者确认、到店/入椅/离台混在一起；提醒送达被当同意 | `appointment.state/coordination_state/confirmed_at/checked_in_at/consent_basis`；`queue_ticket.registered_at/state/closed_at`；`visit_record.started_at/completed_at`；`contact_record.acknowledged_at/acknowledgment_basis`（48表；Word 6.3.21/27/34/38） | E1 | 用“预约状态 + 事件时间 + 确认依据”表达；到店=`checked_in_at`，候诊=`queue_ticket`，接诊=`visit_record`；不引入 `Confirmed` 字典 | 现有两处状态（appointment/queue）不是重复建模，但必须同事务更新；`contact_record` 送达只证明通知，不改确认；电话/现场 `consent_basis` 合同已要求必填 | 已覆盖（规则需验收） | 首期验收 | 无表；补“通知送达≠确认”“签到/接诊原子更新”断言 | 电话预约缺 `consent_basis` → 422；提醒送达后 `confirmed_at/consent_basis` 不变；签到、接诊、取消各自关闭对应队列/占用 |
| 2 | 医生有空但诊椅/设备不可用；把营业时间当号源 | `schedule` + `branch_opening_interval` + `branch_booking_config`；`resource/resource_unavailable/resource_booking`；`service_resource_requirement`；`schedule_service`（48表） | E2 | 保持医生、门店营业、资源三层独立；预约确认时分配具体 `resource_id`，不把椅位写死到排班 | 不引入 `operatory`/`scheduleop`/`Pattern`；静态椅位会破坏共享号源；资源禁用按“存在有效禁用且 starts_at<e”持续阻止，不因预计结束自动恢复 | 已覆盖（无需改） | 首期 | 无 | 医生空闲但椅禁用→无 slot；门店未营业→无 slot；两患者抢最后资源→仅一成功；跨店缓冲仍生效 |
| 3 | 未预约复诊没有清单；取消后无人跟进；候补可能自动占号 | `followup_plan`（source_visit_id/suggested_from/suggested_to/state）；`appointment.followup_plan_id`；`contact_record`（plan_id/appointment_id/next_contact_at/acknowledged_at）；`appointment` state/previous_id；`appointment_change`（Word 6.3.23/37/38；48表已建 followup/contact/appointment） | E3 | `followup_plan` 表达医生建议的复诊窗口；取消后以 `appointment`+`contact_record` 建“待重新安排”读模型；候补只做人工候选+明确同意 | 不引入按 interval 自动 recall/planned appointment；未预约复诊依赖诊疗写入，首期只有只读/合成；**不能取消即自动追踪**；不能仅用 `previous_id` 有后继或同患者任意预约判断关闭；候补不能自动 hold，通知送达不能当同意 | 部分覆盖 | 工作台实施时候选；不立即改接口 | 无新表；先核对现有字段/枚举能否无歧义表示；仅明确请求回访或有可核实协调依据建待办；支持已联系/拒绝回访/已自行重约/无法联系/无需继续等关闭或暂缓；不自动追踪 | 取消不自动进入；明确请求/依据才可建；可关闭/暂缓；不以 `previous_id` 或同患者任意预约单独判断关闭；通知送达不当作同意；候补不自动占号 |
| 4 | 治疗计划与实际治疗未分开；价格记录散 | Word `care_item`（quoted_*/actual_*/state/supersedes/site_description）；`bill_line` 价格快照；48表未建 care_item | E4 | 单次就诊用 `care_item` 的告知/实施分列已够；跨就诊多项目方案后续加 plan header + item；接受后生成 `care_item` | 不照搬 `proctp`/保险估算；预约项目不能自动生成实施/收费；**一个计划项目可能分多次治疗，不能把 plan_item 固定为 0..1 个 care_item**；首期单一项目边界不变 | 部分覆盖 | 后续（诊疗阶段） | 后续新增 `treatment_plan`+`treatment_plan_item`；plan_item 与 care_item 的关系按治疗次数/收费单位另行审定，不批准 DDL | 同一计划项目可分多次治疗；支持部分接受；接受不等于实施；只对实施记录生成 care_item/账单；报价快照不随目录变 |
| 5 | 分次付款、退款；实收≠应收；一笔收款跨多账单 | Word `bill/bill_line/payment/refund`；`payment.bill_id`；`refund.care_id`；6.4 实收净额；48表未建 | E5 | 首版维持一笔收款归属一张账单，支持同账单分次收款；退款按项目；跨账单分摊暂不做 | 不照搬 `paysplit` 的保险/provider 字段；单 bill 时 `external_trade_no` 唯一；跨账单无法在不改 `payment.bill_id` 的情况下表达，不能提前扩张 | 部分覆盖（同账单分次/退款已覆盖，跨账单暂不做） | 后续（收费） | 无表；首版不改 `payment.bill_id`；跨账单需求确认后再评估 `payment_allocation` | 同一账单可分次付款且不超付；退款不超原项目；发票独立于收款；不出现跨账单分摊记录 |
| 6 | 牙冠/义齿外加工到货影响复诊；加工成本 | Word 明确修复服务“不管理加工厂生产”；无 labcase；`supplier` 是采购，`followup_plan` 是复诊，`contact_record` 是沟通 | E6 | 后续优先评估复用 `supplier` 作为供方身份；只有专属资格/能力信息不足时才扩展；另建 `lab_case` 跟踪加工件 | 不照搬 `labcaseproduct`/`labturnaround`；加工费只作成本，不自动收患者钱、不自动改约；到期未到不能当临床结论；费用不能重复进耗材和经营费用 | 未覆盖 | 后续候选（优先） | 不立即加表；先评估复用 `supplier`；`lab_case` 追溯患者、治疗/方案、门店、返工；预约仅可选关联 | 加工单送出/到货/检查；逾期清单；可关联预约但不强制；返工可追溯；费用在结算前不进患者应收且不重复计成本 |
| 7 | 材料批次效期/进价/消耗；设备折旧；门店成本 | Word `stock_item/stock_batch/warehouse/purchase_receipt(_line)/stock_movement`；`asset/asset_depreciation/asset_maintenance/operating_expense`；48表未建 | E7 | 保持批次成本层、只追加流水、临床领用绑定 care_item、月折旧与期间费用分来源 | XML 可见 supply/order/equipment；未见批次成本层/月折旧/期间费用字段，这些是**本项目需求**，不是该产品功能；防重复计成本 | 已覆盖（全量设计） | 后续（成本） | 无 | 过期/隔离批次不可领；负库存拒绝；实耗才计成本；同资产同月折旧唯一；采购/折旧/期间费用不重复 |
| 8 | 熟人推荐不等于 referral；家庭共用电话被当身份 | `doctor_preference.source`（本人选择/熟人推荐/既往就诊）；`patient` 电话不唯一；`account` 与 patient 1:1；绑定不凭手机号 | E8 | 保留 source 枚举，不存推荐人身份/病历；不建 referral/Guarantor；家庭账户另立授权 | 不采用 Open Dental `referral`/`patient.Guarantor`/`patientlink`；分享只公开名片；同一手机号不能登录他人 | 已覆盖/不采用 | 不采用（家庭账户后续另议） | 无 | 分享名片无推荐人信息；同手机号不能认领；referral 不泄露健康信息 |

## 4. 结论清单

### 已覆盖，无需改

- **排班/营业/诊椅/设备分离**：`schedule`、`branch_opening_interval`、`resource`、`resource_unavailable`、`resource_booking`、`service_resource_requirement` 已能表达“医生、门店、资源同时满足才放号”；不要引入 `operatory`/`scheduleop`/`Pattern`。
- **预约状态与到店事件分离**：`appointment.state` 是预约生命周期，`queue_ticket` 是队列生命周期，`visit_record` 是接诊事实；不新增 arrived/seated/dismissed 三列，避免重复。
- **通知与确认分离**：`contact_record` 的送达/知晓与 `appointment.consent_basis` 已分开；合同已要求电话/现场代办必填确认依据。
- **电话/现场与临时替诊**：`POST /staff/appointments` 与患者端共用同一号源；`dentist_unavailable`+`appointment_change`+`consent_basis` 已覆盖停诊/替诊协调，不自动替换医生，通知送达不等于患者同意。
- **单次就诊的“告知 vs 实施”**：Word `care_item` 已把 quoted/actual、state、supersedes、site 分开，`bill_line` 保存价格快照；首期不建 care_item，不越界。
- **应收/实收/退款/发票分离**：Word `bill`、`payment`、`refund`、`invoice` 独立，6.4 按成功收款减成功退款统计实收净额；不把 `payment` 当结算单状态，也不把发票当收款。
- **材料/设备/成本**：Word 60 表设计已覆盖批次效期、成本层、库存流水、月折旧、维护费、期间费用；XML 可见 supply/order/equipment，但未见这些细分字段，故按本项目需求保留，不据此评价产品整体能力。
- **熟人推荐/家庭**：`doctor_preference.source` + 患者 1:1 账号 + 绑定不凭手机号已够；不采用 referral/Guarantor。

### 首期候选（1 项，工作台实施时核对，不新增表，不立即改接口）

1. **取消后“待重新安排”待办**：由 `appointment` + `contact_record` 组成，但实施前先核对现有字段/枚举能否无歧义表示。只有患者明确请求回访，或存在可核实的人工协调依据，才允许建立待办；不能所有已取消预约自动进入，也不能因取消自动推断医疗需要。
   - 关闭/暂缓至少支持：已联系、拒绝回访、已自行重约、无法联系、无需继续；允许人工关闭。
   - 关闭判断不能只看 `previous_id` 有后继（患者自主新建预约未必有 `previous_id`），也不能因同患者有任何预约就关闭。
   - 验收：取消不自动进入；明确请求/依据才可建；可关闭/暂缓；不以 `previous_id` 或同患者任意预约单独判断关闭；通知送达不当作同意；候补不自动占号。

### 后续有价值（按优先级，最多 8 项）

1. **未预约复诊工作台**（诊疗阶段）：`followup_plan` 已能表达医生建议窗口，但首期只读；后续增加 staff 只读/跟进入口，仍不按固定 interval 自动生成治疗周期。
2. **外加工 `lab_case`**（修复/正畸，优先候选）：先评估复用 `supplier` 作为供方身份，避免重复建实验室主档；只有专属资格/能力信息才考虑扩展。`lab_case` 需追溯患者、治疗/方案、门店和返工历史，预约仅可选关联。
3. **治疗计划与实际治疗分开**（诊疗阶段）：多项目、跨就诊、部分接受时再评估 plan header + item；一个计划项目可能分多次治疗，plan_item 与 care_item 的关系不固定 0..1，按治疗次数和收费单位另行审定。
4. **收款**（收费阶段）：首版维持一笔收款归属一张账单，支持同账单分次收款；跨账单分摊暂不做，需求确认后再议。
5. **手动候补**（后续可选）：只做人工候选 + 明确同意，再走正常预约竞争校验；不预占、不自动加号、不保证通知后仍有空位。
6. **转诊单/外部转诊**（后续，可选）：与“熟人推荐”分开；如做，需独立转诊对象和授权，不复用 `doctor_preference.source`。
7. **家庭账户/代理授权**（后续，可选）：不基于共用电话；需明确监护人/代理人、范围和撤销，不能把 `patient` 家庭关系当登录授权。
8. **完整预约编辑历史**（后续，可选）：现有 `audit_log`+`version`+`previous_id` 先满足关键追溯；若确需逐次编辑快照，再加只追加历史，不照搬 `histappointment`。

### 不采用

- Open Dental 的 `Confirmed` 定义字典和通用 `definition` 表：会把“患者确认/到店/入椅”混成一个外键，且与现有 `appointment.state`、`queue_ticket`、`visit_record` 重复。
- `Pattern` 时间模式、`scheduleop` 静态椅位绑定、独立 `operatory` 表：现有 `schedule`+`resource` 动态分配更适合共享号源；不复制其时间编码。
- `histappointment` 整行历史表：先用 `audit_log`+`version`+`previous_id`；除非有逐次编辑快照的明确验收。
- 美国保险/理赔/诊疗编码/收费表（`insplan`、`claim`、`claimproc`、`procedurecode`、`feesched` 等）：不采用；中国发票、湖南医保/税务规则必须另查官方来源，不凭 Open Dental 推断。
- `paysplit` 的保险/provider 分摊：不照搬；我们以 `bill_line`/`refund.care_id` 为项目依据。
- `patient.Guarantor`/家庭账单、`patientlink` merge/clone：不用于登录授权或默认家庭共享；家庭账户后续另建模。
- `referral` 表用于“熟人推荐”：不采用；`doctor_preference.source` 足够，且不存推荐人健康信息。
- 自动 recall 周期、自动候补占号：不采用；必须由医生建议/患者同意触发。

## 5. 候选表最小结构（仅后续，未实施）

> 以下 PK/FK 是本项目建议，不是从 Open Dental 直接推导的物理外键；未在迁移中创建。

### 5.1 治疗计划（后续诊疗阶段，未批准 DDL）

- `treatment_plan`：`plan_id` BIGINT PK；`patient_id` R FK→patient；`source_visit_id` R FK→visit_record（方案在接诊中提出）；`dentist_id` R FK→staff；`state` R（draft/active/accepted/declined/completed/superseded）；`version` R；`supersedes_id` N UQ FK→self；`accepted_at` N；`accept_basis` N；`created_at` R。
- `treatment_plan_item`：`plan_item_id` PK；`plan_id` R FK→treatment_plan；`item_id` R FK→service_item；`site_description` N；`quoted_quantity` R；`quoted_price` R；`status` R；`supersedes_id` N UQ FK→self；`item_order` R；`UNIQUE(plan_id,item_order)`。
- 计划项目与实际治疗不固定 0..1：一个项目可能分多次治疗；后续按治疗次数和收费单位审定是 1:N、按收费单位合并，还是用 `treatment_plan_item_care` 关联表。**不设单值 `care_id`**，不批准 DDL。
- 基数/历史：患者 1:N 计划，计划 1:N 明细；不物理删除，版本/替代保留；报价是历史快照，不随目录调价；接受不等于实施，不自动开账单；不存保险估算。
- 为什么现有表不能承载：`care_item` 以 `visit_id` 为必填、按单次就诊，且没有计划头/版本/部分接受；`followup_plan` 是复诊建议，不是多项目治疗方案。

### 5.2 外加工（后续候选，未批准 DDL）

- **先复用 `supplier` 作为供方身份**，不另建重复实验室主档；只有供应商档案无法表达专属资格/能力（如加工类型、资质、周转）时，才评估扩展字段或侧表。
- `lab_case`：`lab_case_id` PK；`patient_id` R FK→patient；`branch_id` R FK→branch；`supplier_id` R FK→supplier；`treatment_plan_id` N FK→treatment_plan；`care_id` N FK→care_item（若一件加工涉及多个项目，后续用关联表，不固定单值）；`appointment_id` N FK→appointment（仅可选关联）；`sent_at` N；`due_at` N；`received_at` N；`checked_at` N；`state` R；`fee_snapshot` N；`instructions` N；`rework_of_id` N UQ FK→self；`created_by` R FK→staff；`version` R。
- 基数/历史：患者 1:N；门店 1:N；供方 1:N；治疗/方案 1:N；预约 1:N（一个预约可有多件加工，不设预约唯一）；返工用自关联保留；不物理删除，状态+时间戳保留；`fee_snapshot` 只作加工成本，不自动生成患者应收，也不能重复进入耗材和经营费用。
- 为什么现有表不能承载：`supplier` 可复用为供方身份，但不跟踪加工件生命周期；`followup_plan` 是临床复诊；`contact_record` 是沟通；`care_item.brand_spec` 只存品牌规格。若 Codex 认为 `supplier` 够用，则只新增 `lab_case`，不建 `laboratory`。

### 5.3 收款分摊（当前暂不做；仅未来需求确认后，未批准 DDL）

- 首版维持一笔收款归属一张账单，支持同账单分次收款；不建 `payment_allocation`。
- 若未来确认必须“一笔收款跨多张账单”：`payment_allocation`：`allocation_id` PK；`payment_id` R FK→payment；`bill_id` R FK→bill；`amount` R；`UNIQUE(payment_id,bill_id)`；同一 payment 的 allocation 合计必须等于 `payment.amount`。
- 迁移影响：`payment.bill_id` 需改为可空/弃用；旧单回填；外部交易号仍唯一；只在真实跨账单收款时启用。
- 为什么现有表不能承载：`payment` 只有单个 `bill_id`，无法表示一笔银行/微信收款覆盖多张结算单；但首版不需要该能力，不能为仿照 `paysplit` 提前扩张。

## 6. 待 Codex 决定点（最多 5）

1. 取消后“待重新安排”是否等预约工作台实施时再核对现有字段，且只对明确请求/可核实依据建待办、可关闭、不自动追踪？
2. 治疗计划的一个项目可分多次治疗；plan_item 与 care_item 按治疗次数还是收费单位关联，是否确认不固定 0..1？
3. 外加工是否先复用 `supplier`、仅在其无法表达专属资格/能力时才扩展，不另建重复实验室主档？
4. 首版是否维持“一笔收款归属一张账单、同账单分次收款”，暂不做跨账单分摊？
5. 候补是否坚持“人工候选 + 明确同意 + 重新检查号源，不预占、不自动加号”？

## 7. 参考

- Open Dental 官方：[预约](https://www.opendental.com/site/0_appointments.html)、[治疗计划](https://www.opendental.com/site/0_treatmentplan.html)、[快速手册](https://www.opendental.com/site/quickmanual.html)
- 数据库 XML：`docs/references/OpenDentalDocumentation26-2.xml`
- 项目基线：`specs/001-scheduling-booking/spec.md`、`plan.md`、`data-model.md`、`tasks.md`、`contracts/api.md`；`docs/development/authorization.md`、`database-migrations.md`
- 项目 Word：`1602240113徐乐怡牙科系统分析与设计.docx`（SHA256 `2F3B9C26BDBBF3D4FB9C29F97E58A576B06D96DF5EC39F279F17267860CE7CA7`）
