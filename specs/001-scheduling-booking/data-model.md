# 第一阶段数据设计

## 依据与使用方式

以根目录Word的数据字典为基线。本文件列出第一阶段依赖闭包及明确变更，不把历史SQL直接当作最终迁移。
原报告未修改。下文“基线”字段用于追踪，实施时必须应用“设计增补”，不能同时保留互相矛盾的约束。
时间点统一timestamptz，日期保留date。原timestamp字段迁移为带时区时必须明确原时区Asia/Shanghai，不能隐式猜测。
PK、FK及UQ分别为主键、外键、唯一；R必填，N可空。bigint对前端统一返回十进制字符串，避免JavaScript精度丢失。

## 第一阶段边界

主动实现身份授权、基础配置、排班预约、协调联系、签到和政策。依赖中的visit_record、followup_plan、dentist_assignment仅提供受控查询/合成测试输入；不伪造临床记录以建立复诊计划。
resource.asset_id依赖asset及supplier，先保留结构且不开放采购资产维护入口；不填假资产编号。后续资产模块接入同一关系。

## 设计增补与修正（实施必须应用）

### 01 意向与确认医生

appointment新增confirmed_dentist_id bigint，可空FK→staff。已确认、候诊中、接诊中、已完成必须非空且等于关联schedule.dentist_id。
intended_dentist_id仍表示申请意向，不作为实际确认医生，也不作为防重依据。
替换历史SQL中基于intended_dentist_id的排他约束为confirmed_dentist_id，另保留患者排他约束。
这属于有目的的冗余快照：确认事务由排班赋值；有预约的排班不得直接换医生，须暂停并走新预约改约。
实际接诊医生仍由后续visit_record记录，不能自动改变长期主治。

### 02 初次登录与旧档绑定

新增identity_challenge：challenge_id UUID PK；purpose（wechat_registration/patient_binding）；token_hash UQ必填；app_id/open_id可空；patient_id可空FK→patient；issued_by可空FK→account；expires_at必填；consumed_at可空；failed_attempts非负默认0；created_at必填。
wechat_registration要求服务端已验证app_id/open_id，尚未建完整account；patient_binding要求patient_id、issued_by且由有该患者业务范围的前台在核验后发放。
注册上下文10分钟、绑定凭证10分钟且最多5次失败，均为开发安全配置，不是诊所调研结果；凭证至少128位随机熵，只保存摘要。
新患者原子建立patient/account/wechat_identity；绑定旧档原子消费凭证并创建或关联唯一账号。
账号继续执行patient_id与staff_id恰好一个非空；不为了未绑定微信用户放宽这个约束。
绑定竞争锁患者、核查wechat_identity唯一性，失败不消费凭证。已绑定其他患者/账号不自动转移或合并。

### 03 签到和营业配置

新增branch_booking_config：branch_id PK/FK→branch；self_checkin_enabled bool默认false；early_minutes、late_minutes可空非负；slot_step_minutes正整数默认15；version正整数；updated_by FK→staff；updated_at。
启用自助签到必须填写两项分钟数，首次配置由负责人确认。slot步长仅影响展示，不缩短真实服务时长。
新增branch_opening_interval：interval_id PK；branch_id FK→branch；valid_on date；starts_at/ends_at timestamptz；version（行级存储）。API 以 branch.version 为集合聚合版本；按日期范围替换，起止有序、同店区间不得重叠、门店日期按上海时区解释；跨午夜拆为两日；范围外保留。
opening_hours保留公开文字说明，但可发布班次必须落在结构化营业区间；修改区间先检查现有班次，不靠解析自然语言营业说明判断。
签到有效窗为[start-early,min(start+late,end))，下界包含、上界不含；医生停诊或资源故障仍阻止自助签到。
门店轮换签到码由后端签发，含branch_id、签发时间、到期时间及随机nonce，有效60秒，密钥仅在后端；所有患者可用同一有效门店码，不将其当一次性个人凭证。可转发风险需人工核验。

### 04 政策批准、展示和计次

booking_policy原来已有approved_by，不能再创建重复批准人字段。调整为草稿可空，发布必填；新增approved_at、status（draft/published/retired）、restriction_enabled默认false。
effective_at在草稿可空，发布必填；UNIQUE(branch_id,version_no)。同店生效版本按发布时间区间选择，禁止两个版本相同effective_at；发布后不可原地改参数，仅新版本替代。
每店需一个已发布、restriction_enabled=false的基础政策才能开放预约，预约仍必须绑定政策，未启用处罚不等于没有取消说明。
booking_event新增reviewed_by FK→staff可空，与reviewed_at及判定依据成组；审计记录每次复核和豁免，不覆盖失去原始事实。
新增restriction_event：restriction_id FK→booking_restriction、event_id FK→booking_event（联合PK），event_id另加UQ；保存触发限制所使用的全部事件，不只保存最后一个trigger_event_id。
事件patient/branch/policy由其appointment推导，与restriction完全一致才可关联。所有关联检查在患者锁下完成。
以复核时刻T计算(T-window_days,T]内、同店同政策、已核实且尚未使用的事件；按occurred_at、event_id排序达阈值才触发。限制[starts_at,ends_at)以复核时刻起算。
这明确了spec中“已过期窗口不补罚”：不能以很久以前的事件时刻重新启动过期窗口。同步规格该表述。
生效限制期不叠加或自动延长；到期更新state再评估未使用的新事件。已用于限制的事件即使限制撤销也保留使用关系，不再重复处罚。
豁免后重算原触发证据，低于阈值撤销当前限制；新批准政策默认restriction_enabled=false，须明确批准后才能启用，不追溯加重。

### 05 幂等、审计与后台恢复

新增operation_request：operation_id UUID PK；actor_account_id FK→account；operation varchar(80)；request_key varchar(64)；request_hash char(64)；target_id bigint可空；result_code varchar(40)；result_ref varchar(40)；created_at/completed_at；UNIQUE(actor_account_id,operation,request_key)。
客户端 Idempotency-Key 只存入 operation_request.request_key，唯一作用域为账号＋操作＋键；不同账号使用相同客户端键互不冲突。appointment.request_key 保留全局唯一，但为服务器生成的内部 UUID 字符串，不是客户端键。
创建成功时 operation_request.target_id/result_ref 引用预约编号；同账号同操作同键返回原结果，不同摘要返回409。先认证授权，再判断请求摘要。
结果仅保存业务引用和状态，不永久保存整份含隐私响应。记录和业务修改同事务提交，失败回滚；外部请求不放事务内。
contact_record新增dispatch_key UUID UQ、lease_until、attempt_count非负、last_error_code；联系state保留报告字典，发送任务使用领取租约，发送后持久化渠道结果。电话联系不伪称渠道自动送达。
未收到明确外部结果时记未知并查询/人工核实；不承诺消息恰好发送一次。原业务成功不依赖通知成功。
queue_ticket原预约唯一和(branch_id,queue_date,queue_no)唯一保留；新增queue_counter(branch_id FK、queue_date联合PK、last_number非负)，行锁递增，取消不回收历史号码。

### 06 资格和特殊申请

credential新增verification_state（pending/verified/rejected）以及no_expiry_confirmed bool默认false，解决空有效期不能自动代表有效的问题；资格发布时核验类型、日期及明确长期有效确认。
staff公开信息使用白名单投影（姓名、职称、经核验可公开的介绍），不暴露身份证件、证书全文或内部账号。
特殊申请保留appointment待确认，资源占用为空；工作人员明确服务和确认时间后，走同一确认事务，不能直接改state绕过校验。
报告主治快照patient.primary_dentist_id与dentist_assignment关系由后续主治变更服务维护，本阶段只读，不提供任意修改入口。

### 07 版本和授权补充

patient、appointment_change、booking_policy新增version integer默认1且正数，用于对应接口expected_version；其他可编辑主数据同样以版本字段或明确的条件更新防止静默覆盖，迁移按接口逐项列出。
appointment_change原state保存报告值，答复和落实分别递增version；已拒绝、已撤回不可再落实。
auth_session新增client_type（staff_web/patient_miniapp）和csrf_token_hash可空；员工网页会话须CSRF校验，患者Bearer不读取Cookie身份。两类会话不能互换。
contact_record新增kind可取预约申诉，申请必须引用本人的预约或限制触发预约，审计附restriction_id；同一限制未关闭申诉只创建一项待办，不将仅写日志当作可处理申诉。
基线联合外键必须保留：role_branch_scope(account_id,role_id)→account_role；service_price_history(branch_id,item_id)→branch_service。
配置涉及营业、任职、资格和资源时，先锁实际影响的医生或资源并复核未来安排；必要时拒绝修改并走停用协调。不得仅凭页面加载时的version跳过跨表检查。

## 状态与事务

数据库状态沿用报告中文值；API亦使用相同中文枚举，避免同一状态混用confirmed和已确认。新增基础设施状态按上文英文枚举，逐项定义。

| 操作 | 原状态 | 成功后状态及联动 | 失败 |
| --- | --- | --- | --- |
| 确认预约 | 待确认或新申请 | 已确认，确认医生、占用、政策快照落地 | 全回滚，待确认不占位 |
| 签到 | 已确认且可执行 | 候诊中，唯一queue_ticket候诊 | 不造候诊号 |
| 取消 | 待确认/已确认/候诊中 | 已取消，释放占用，退出队列，关联复诊回待协调 | 开始接诊后拒绝 |
| 改约 | 已确认/候诊中 | 原单已改约，新单已确认，旧队列退出，原计划改绑新单 | 保留原安排或停诊待协调 |
| 核实未到 | 已确认、从未签到、end已过 | 未到、释放占用、计划待协调、核实事件 | 已签到或未结束拒绝 |
| 停诊 | 已发布班次 | 暂停或不可用区间有效；受影响预约待协调 | 不直接换医生 |

关键写事务锁顺序：患者→所有涉及医生按ID→排班按ID→资源按ID→本次预约/候诊等子记录；幂等占位记录先于这些业务锁且所有相同操作一致。
签到末尾才锁queue_counter；不允许持计数器反向锁患者。配置更新遵循资源单向短事务，不批量锁患者。
停诊短事务先锁医生及排班关闭入口，提交后再逐患者协调；预约提交锁医生后重读排班与停诊区间，因此无窗口漏约。
资源禁用同理先锁资源关闭入口；并发占号读到禁用后失败，历史影响持久化待处理。页面的“可执行”同时动态读取停诊/禁用，不能等后台标记才显示异常。
资源按类别数量分配具体resource_id，轮转分钟计入resource_booking结束时间；不得仅记录“需要一张牙椅”而不锁定实际资源。
患者、确认医生和资源排他约束覆盖有效占用；改约先使旧占用退出有效集合，再写新单，同事务失败全回滚。
数据库只保证本表约束；同门店、资格、复诊归属、停诊和政策必须在持锁后重读校验。

**补充状态流转**：
- 改约方案（appointment_change）：待答复 → 已同意／已拒绝／已撤回 → 已落实。已拒绝、已撤回不可再落实；已落实必须先同意且有落实时间与答复依据。
- 排班修改：已发布班次的医生、门店、时段或服务发生实质变更，采用「暂停旧班次 → 新建并发布 → 协调原预约」，不直接覆盖已发布班次；草稿可带 version 直接修改。
- 资源禁用撤销：误登记撤销与故障已修复分开记录（resolve_type），禁止仅因预计 ends_at 已过自动恢复可约。

## 排他约束与部分唯一索引（数据库兜底）

迁移须 `CREATE EXTENSION btree_gist`。时间区间统一使用 `tstzrange(starts_at, ends_at, '[)')`（左闭右开，与 `timestamptz` 列匹配），不再使用 `tsrange`。

**预约状态与协调状态分离**：`appointment.state`（待确认/已确认/候诊中/接诊中/已完成/已取消/未到/已改约）与 `appointment.coordination_state`（无需协调/待协调/已处理）是两个独立维度。「待协调」是协调状态，不是预约状态：已确认预约因停诊或资源禁用进入待协调时，其 `state` 仍为已确认，患者/牙医/资源占用与 `resource_booking.state='有效'` 均保持，**不因进入待协调自动释放**；只有取消、改约、核实未到等事务按各自规则释放占用。排他约束只按 `state` 过滤，不看 `coordination_state`。

**已完成的历史区间不纳入排他**：排他约束只负责「活动占用」的时段互斥；已完成预约已释放占用，其历史接诊事实由 `visit_record` 保留，二者职责分离。预约/改约接口不接受过去时段（服务端拒绝 starts_at 早于当前时间的提交），因此不能通过回填过去时段绕过冲突检查；历史数据导入走独立校验通道，不经过预约接口。

**排他约束（确定名称，字段与最终字段清单一致）**：

```sql
-- 患者排他：同一患者占用中的预约时段不得重叠
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_patient_overlap
  EXCLUDE USING gist (patient_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
  WHERE (state IN ('已确认','候诊中','接诊中'));

-- 确认牙医排他：同一确认牙医（非空）占用中的预约时段不得重叠
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_dentist_overlap
  EXCLUDE USING gist (confirmed_dentist_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
  WHERE (confirmed_dentist_id IS NOT NULL AND state IN ('已确认','候诊中','接诊中'));

-- 资源排他：同一资源两条有效占用时段不得重叠
ALTER TABLE resource_booking ADD CONSTRAINT ex_resource_booking_overlap
  EXCLUDE USING gist (resource_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
  WHERE (state = '有效');
```

**部分唯一索引（跨行规则，不能用普通 CHECK）**：

```sql
-- 复诊计划活动预约唯一：每份复诊计划至多一笔活动预约。
-- 仅限制「未终结」的活动预约；已完成/已取消/未到与待确认不计数，
-- 使保留历史区间不会阻止下一次正常复诊。状态集虽与时间区间排他相同，但目的独立（防同计划并发活动预约 vs 防物理时段重叠），不机械共用。
CREATE UNIQUE INDEX uq_appointment_followup_active
  ON appointment (followup_plan_id)
  WHERE followup_plan_id IS NOT NULL AND state IN ('已确认','候诊中','接诊中');

-- 改约后继唯一：同一原单至多一个已确认后继。
-- 曾成功确认的后继即使后来取消，也阻止第二个已确认后继；未确认候选（confirmed_at IS NULL）不受限。
CREATE UNIQUE INDEX uq_appointment_previous_confirmed
  ON appointment (previous_id)
  WHERE previous_id IS NOT NULL AND confirmed_at IS NOT NULL;
```

门店归属、确认医生与排班一致性等跨表校验，采用事务内持锁重读或可行的联合外键，不写普通 CHECK 查询其他表。已有基线联合外键保留：`role_branch_scope(account_id,role_id)→account_role`、`service_price_history(branch_id,item_id)→branch_service`。

## 关系、范式与生命周期

任职staff_branch、角色门店role_branch_scope、排班服务schedule_service等保留多对多关联表。
禁止把角色代码、资源ID集合或治疗列表拼在一个文本字段；备注不是核心结构关系的替代。
确认医生、候诊门店及政策版本属于约束明确的历史/查询冗余。槽位由排班推导，不另建无业务理由的永久空号表。
业务FK默认RESTRICT，不开放物理删除历史；配置停用保留引用。可过期安全凭证按保留策略清理，不删除业务账迹。
通用audit_log.target_type/target_id为多态审计引用，不冒充数据库FK；保留业务ID及操作前后最小必要摘要。
新增安全字段及个人联系方式对返回、日志分别脱敏；限制操作理由面向本人可见，内部审计仅授权人员可查。

## 最终字段清单（基线＋设计增补已合并）

以下各表为最终生效字段，已合并上文「设计增补与修正」的内容（增补字段不再单独维护一份）。时间点统一 `timestamptz`，日期保留 `date`。

### staff — 工作人员

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| staff_id | bigint | PK | 人员编号 |
| name | varchar(60) | R | 姓名 |
| staff_type | varchar(20) | R | 牙医或其他人员 |
| job_title | varchar(60) | R | 业务岗位 |
| practice_no | varchar(60) | N;UQ | 牙医执业标识 |
| employment_state | varchar(20) | R | 在岗或离岗 |

牙医与其他员工合表，牙医必须填写执业标识；岗位不直接决定权限。离岗停用账号并交接未完事项，保留历史接诊人。

### credential — 人员资格

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| credential_id | bigint | PK | 资格记录编号 |
| staff_id | bigint | R;FK:staff.staff_id | 持有人 |
| credential_type | varchar(40) | R | 资格类别代码 |
| certificate_no | varchar(80) | R | 核实的证件编号 |
| valid_from | date | N | 起始日期 |
| valid_to | date | N | 截止日期 |
| verified_by | bigint | R;FK:staff.staff_id | 核实人 |
| verified_at | timestamptz | R | 核实时间 |
| verification_state | varchar(20) | R | pending、verified或rejected |
| no_expiry_confirmed | boolean | R | 是否确认无有效期限制，默认false |

资格类别采用固定代码；UNIQUE(staff_id,credential_type,certificate_no)。日期均有值时须有序；空有效期不表示已通过有效性核验，须 verification_state=verified 且（有效期内或 no_expiry_confirmed=true）方可用于安排接诊。

### patient — 患者

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| patient_id | bigint | PK | 患者编号 |
| name | varchar(60) | R | 姓名 |
| birth_date | date | N | 出生日期 |
| phone | varchar(30) | R | 本人或监护人联系电话 |
| email | varchar(120) | N | 邮箱 |
| emergency_name | varchar(60) | N | 紧急联系人 |
| emergency_phone | varchar(30) | N | 紧急联系电话 |
| primary_dentist_id | bigint | N;FK:staff.staff_id | 当前主治牙医 |
| allergy_status | varchar(20) | R | 未申报、无已知、有或不确定 |
| allergy_notes | text | N | 当前过敏申报说明，过敏或不确定时必填 |
| version | integer | R | 乐观锁版本，从1起 |

姓名与电话不设唯一，允许家庭共用号码；主治人须为牙医。有过敏或不确定时必须说明。患者更新申报不覆盖历次接诊的核对快照。当前主治通过主治交接记录维护；临时替诊只影响当次安排。

### account — 登录账号

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| account_id | bigint | PK | 账号编号 |
| login_name | varchar(100) | N;UQ | 员工登录名；仅微信登录的患者可空 |
| patient_id | bigint | N;UQ;FK:patient.patient_id | 患者身份 |
| staff_id | bigint | N;UQ;FK:staff.staff_id | 员工身份 |
| enabled | boolean | R | 账号是否启用 |
| password_hash | varchar(255) | N | 带算法参数的密码摘要，待激活账号可空 |
| must_change_password | boolean | R | 首次登录或重置后须修改密码 |
| auth_version | integer | R | 授权版本，从1起，改权或停用时递增 |
| created_at | timestamptz | R | 创建时间 |

患者与员工身份恰有一个非空；档案可先于账号存在。员工启用须有规范化登录名及密码摘要；小程序患者经微信绑定启用，不强制设置密码。手机号不自动合档。角色变更或停用递增auth_version并撤销会话，患者不得获员工角色。

### schedule — 出诊安排

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| schedule_id | bigint | PK | 排班编号 |
| dentist_id | bigint | R;FK:staff.staff_id | 排班牙医 |
| starts_at | timestamptz | R | 开始时间 |
| ends_at | timestamptz | R | 结束时间 |
| service_type | varchar(20) | R | 初诊、复诊或两者 |
| state | varchar(20) | R | 草稿、已发布、暂停、已结束 |
| branch_id | bigint | R;FK:branch.branch_id | 出诊门店 |
| published_at | timestamptz | N | 发布时刻 |
| version | integer | R | 排班并发版本 |
| travel_buffer_minutes | integer | R | 跨门店衔接预留分钟数 |

开始早于结束。草稿核实门店任职、资格、项目、时长和资源后发布；停诊另记不可用时段。牙医跨门店时段也不得重叠，相邻跨店班次满足两班travel_buffer_minutes较大值。草稿可带 version 直接修改；已发布班次的医生、门店、时段或服务发生实质变更，采用暂停旧班次→新建并发布→协调原预约，禁止直接改写已发布班次或历史已接诊班次。

### appointment — 预约就诊

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| appointment_id | bigint | PK | 预约编号 |
| patient_id | bigint | R;FK:patient.patient_id | 患者 |
| schedule_id | bigint | N;FK:schedule.schedule_id | 确认的排班 |
| intended_dentist_id | bigint | N;FK:staff.staff_id | 申请时的意向牙医 |
| confirmed_dentist_id | bigint | N;FK:staff.staff_id | 确认牙医快照；已确认、候诊中、接诊中、已完成必须非空且等于 schedule.dentist_id |
| starts_at | timestamptz | R | 申请或确认的开始时间 |
| ends_at | timestamptz | R | 预计结束时间 |
| visit_type | varchar(20) | R | 初诊或复诊 |
| purpose | varchar(200) | R | 就诊事项 |
| special_needs | varchar(300) | N | 特殊需求 |
| channel | varchar(20) | R | 患者端、电话、微信登记或现场 |
| state | varchar(20) | R | 待确认、已确认、候诊中、接诊中、已完成、已取消、未到或已改约 |
| coordination_state | varchar(20) | R | 无需协调、待协调或已处理 |
| previous_id | bigint | N;FK:appointment.appointment_id | 改约前的记录 |
| followup_plan_id | bigint | N;FK:followup_plan.plan_id | 落实的复诊计划 |
| confirmed_at | timestamptz | N | 确认时间 |
| checked_in_at | timestamptz | N | 签到时间 |
| consent_basis | varchar(300) | N | 确认及替诊同意依据 |
| version | integer | R | 乐观锁版本，从1起 |
| request_key | varchar(36) | R;UQ | 服务器生成的内部 UUID 字符串，全局唯一；非客户端 Idempotency-Key |
| request_hash | varchar(64) | R | 规范化创建请求的SHA256摘要 |
| requested_by | bigint | R;FK:account.account_id | 提交创建申请的患者或前台账号 |
| cancelled_by_type | varchar(20) | N | patient、clinic或system |
| closure_reason | varchar(300) | N | 取消、改约或未到的原因 |
| branch_id | bigint | R;FK:branch.branch_id | 就诊门店 |
| policy_id | bigint | R;FK:booking_policy.policy_id | 创建时展示并绑定的预约规则 |
| cancelled_at | timestamptz | N | 取消或改约的生效时刻 |

预约属于一名患者和一家门店，confirmed状态必须有已发布有效排班、指定牙医和资源。小程序满足资格、服务、限制与资源条件后在一次事务中确认，无需前台逐单审批；需人工核实事项留待确认且不占资源。时段为半开区间，患者、牙医及资源均不得冲突。一个复诊计划最多一笔有效预约。改约通过新旧预约普通自关联保留历史，不能成环，同一原单最多一个已确认后继；自助改约同时占新放旧，失败保留原可用安排。诊所停诊不能继续承诺旧时段。幂等去重由 operation_request（账号＋操作＋客户端键）承载，appointment.request_key 为服务器生成 UUID（全局唯一）；request_hash 检测相同键内容变化，version 防覆盖；取消必填发起方、原因、时刻。

### resource — 诊疗资源

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| resource_id | bigint | PK | 资源编号 |
| name | varchar(80) | R | 牙椅或设备名称 |
| resource_type | varchar(20) | R | 牙椅或独立预约设备 |
| category_id | bigint | R;FK:resource_category.category_id | 设备能力类别 |
| operating_state | varchar(20) | R | 可用、停用或退役 |
| branch_id | bigint | R;FK:branch.branch_id | 资源所属门店 |
| asset_id | bigint | N;UQ;FK:asset.asset_id | 对应资产卡片，虚拟资源可空 |

门店资源的类别及可用状态用于排班。资产可关联一个排班资源，资源和资产门店须一致；不是所有资产均需预约。不可用时段与占用分表，维修恢复须核实。

### resource_unavailable — 资源禁用时段

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| unavailable_id | bigint | PK | 禁用编号 |
| resource_id | bigint | R;FK:resource.resource_id | 资源 |
| kind | varchar(20) | R | 维护或故障 |
| starts_at | timestamptz | R | 开始 |
| ends_at | timestamptz | R | 预计结束，不代表自动恢复 |
| reason | varchar(200) | R | 维护、消毒等原因 |
| state | varchar(20) | R | 有效或已撤销 |
| recorded_by | bigint | R;FK:staff.staff_id | 登记人员 |
| recorded_at | timestamptz | R | 登记时间 |
| resolved_at | timestamptz | N | 撤销／恢复时间 |
| resolve_type | varchar(20) | N | 误登记撤销或已修复 |
| resolve_basis | varchar(300) | N | 撤销依据说明 |
| resolved_by | bigint | N;FK:staff.staff_id | 实际恢复/撤销操作人；与 resolved_at/resolve_type/resolve_basis 成组填写，不得覆盖原登记人 recorded_by |

起止有序；kind 区分未来维护与当前故障。ends_at 是预计结束，禁止仅因 ends_at 已过自动恢复可约；撤销/恢复须人工核实并成组记录 resolved_by、resolved_at、resolve_type（误登记撤销／已修复）与 resolve_basis，resolved_by 为实际操作人、不得覆盖原登记人 recorded_by。资源可用性判断：对候选时段 [s,e)，若存在 state='有效'（尚未人工解除）的 resource_unavailable 记录且其 starts_at < e，则该资源不可约——禁用自 starts_at 起持续阻止，预计 ends_at 不参与判断、不能作为自动恢复依据；查询可约时段与提交预约必须采用同一规则。解除一条后仍须检查其他有效禁用记录，不能直接判为可用。新增禁用须查找冲突预约并转入协调，不能静默覆盖。

### resource_booking — 资源占用

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| booking_id | bigint | PK | 占用编号 |
| appointment_id | bigint | R;FK:appointment.appointment_id | 预约 |
| resource_id | bigint | R;FK:resource.resource_id | 资源 |
| starts_at | timestamptz | R | 占用开始 |
| ends_at | timestamptz | R | 占用结束 |
| state | varchar(20) | R | 有效或已释放 |

同资源有效占用不得重叠；占用可含必要周转时间。牙椅诊疗预约确认时必须落实牙椅，所需独立设备同步检查。

### visit_record — 接诊记录

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| visit_id | bigint | PK | 接诊编号 |
| appointment_id | bigint | R;UQ;FK:appointment.appointment_id | 本次预约 |
| dentist_id | bigint | R;FK:staff.staff_id | 实际接诊牙医 |
| allergy_result | varchar(20) | R | 未核对、无已知、有或不确定 |
| allergy_snapshot | text | N | 本次核对依据与说明 |
| clinical_notes | text | N | 本次诊疗说明 |
| started_at | timestamptz | R | 接诊时间 |
| completed_at | timestamptz | N | 完成时间 |

每笔预约至多一次接诊记录，未到者不建记录。接诊牙医须与最终确认安排一致；有过敏或不确定时必填说明，完成前不能仍为未核对。完成后补记写入visit_addendum，注明作者、时间和原因并留审计，不覆盖原文。

### service_item — 治疗项目

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| item_id | bigint | PK | 项目编号 |
| name | varchar(100) | R | 项目名称 |
| unit | varchar(30) | R | 计价单位 |
| current_price | decimal(12,2) | R | 组织目录基准价，门店实际参考价取branch_service |
| enabled | boolean | R | 是否用于新登记 |
| appointment_enabled | boolean | R | 是否允许在预约阶段选择 |
| suggested_minutes | integer | R | 预约参考时长 |
| category_id | bigint | R;FK:service_category.category_id | 所属诊疗服务分类 |

统一项目编码和分类，current_price仅为新门店设价基准，实际展示和报价读取门店服务价格。预约项目只是患者意向；牙医检查后形成care_item，不自动按预约收费。

### followup_plan — 复诊计划

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| plan_id | bigint | PK | 计划编号 |
| source_visit_id | bigint | R;FK:visit_record.visit_id | 提出建议的接诊 |
| dentist_id | bigint | R;FK:staff.staff_id | 制定牙医 |
| suggested_from | date | R | 建议开始日期 |
| suggested_to | date | R | 建议截止日期 |
| duration_minutes | integer | R | 预计接诊时长 |
| purpose | varchar(200) | R | 复诊事项 |
| state | varchar(20) | R | 待协调、已预约、已完成或已终止 |
| supersedes_id | bigint | N;UQ;FK:followup_plan.plan_id | 被更正的旧计划 |
| termination_reason | varchar(300) | N | 终止或更正原因 |

从来源接诊取得患者，不复制患者编号。建议日期有序、时长为正；每份计划至多一笔当前有效预约。更正临床建议新增计划并终止旧计划，保留历史关联。

### contact_record — 联系与提醒记录

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| contact_id | bigint | PK | 联系编号 |
| plan_id | bigint | N;FK:followup_plan.plan_id | 未约成的复诊计划 |
| appointment_id | bigint | N;FK:appointment.appointment_id | 预约协调或提醒 |
| kind | varchar(20) | R | 人工联系、到诊提醒或预约申诉 |
| channel | varchar(20) | R | 电话、微信登记、当面或系统通知 |
| planned_at | timestamptz | N | 计划发送时间 |
| contacted_at | timestamptz | N | 实际联系或发送时间 |
| state | varchar(20) | R | 待发送、发送中、已送达、失败或已联系 |
| result | varchar(300) | N | 联系结果或失败原因 |
| acknowledged_at | timestamptz | N | 患者确认知晓时间 |
| acknowledgment_basis | varchar(300) | N | 知晓依据 |
| next_contact_at | timestamptz | N | 未办结事项的跟进时间 |
| staff_id | bigint | N;FK:staff.staff_id | 人工经办人，系统发送可空 |
| retry_of_id | bigint | N;UQ;FK:contact_record.contact_id | 失败重试的原记录 |
| dispatch_key | uuid | N;UQ | 发送任务领取租约的派发键 |
| lease_until | timestamptz | N | 租约到期时间 |
| attempt_count | integer | R | 尝试次数，非负默认0 |
| last_error_code | varchar(40) | N | 最近失败代码 |

计划与预约外键恰有一个非空。人工联系必填经办人、实际时间和结果，状态为已联系；到诊提醒只关联预约且必填计划时间。预约申诉（kind=预约申诉）必须引用本人的预约或限制触发预约，审计附 restriction_id；同一限制未关闭申诉只创建一项待办。一次联系一行，失败重试新增行；送达、知晓和方案同意分开，微信内容由前台登记。

### audit_log — 操作日志

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| audit_id | bigint | PK | 日志编号 |
| actor_account_id | bigint | N;FK:account.account_id | 人工操作者，系统事件可空 |
| actor_type | varchar(20) | R | 用户或系统 |
| target_type | varchar(40) | R | 对象类别 |
| target_id | bigint | R | 对应业务编号 |
| operation | varchar(60) | R | 操作类型 |
| before_data | text | N | 变更前快照 |
| after_data | text | N | 变更后快照 |
| reason | varchar(300) | N | 变更原因 |
| occurred_at | timestamptz | R | 时间 |

用户操作须有账号，系统事件写明来源。日志追加且不可改，覆盖预约状态、人员资格、调价及更正过程。目标类型与编号为审计定位，不是跨表外键；关键收费与复诊历史仍保留在业务表中。不记录密码、会话令牌及完整健康文本；诊疗变更记录字段名和业务版本定位，业务原文留原表。安全管理员只看账号及访问事件摘要，经营负责人只看业务审计摘要。

### dentist_assignment — 主治交接记录

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| assignment_id | bigint | PK | 交接编号 |
| patient_id | bigint | R;FK:patient.patient_id | 患者 |
| dentist_id | bigint | R;FK:staff.staff_id | 承担主治的牙医 |
| starts_at | timestamptz | R | 生效时间 |
| ends_at | timestamptz | N | 终止时间 |
| previous_id | bigint | N;UQ;FK:dentist_assignment.assignment_id | 前一任主治记录 |
| recorded_by | bigint | R;FK:staff.staff_id | 登记人员 |
| agreed_at | timestamptz | R | 患者或监护人确认时间 |
| agreement_basis | varchar(300) | R | 确认依据 |
| reason | varchar(300) | R | 初次建立或交接原因 |

同一患者主治任期不重叠，至多一条未结束记录；结束晚于开始。前后记录须同一患者、时间衔接且无环；交接原子结束旧任期并建立新任期。临时替诊不新增主治交接。patient.primary_dentist_id为当前主治查询副本，与未结束任期同步更新；无当前任期时为空。首次整理旧资料按可核实时间登记，不补造历史。

### dentist_unavailable — 医生停诊时段

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| absence_id | bigint | PK | 停诊编号 |
| dentist_id | bigint | R;FK:staff.staff_id | 停诊牙医 |
| starts_at | timestamptz | R | 停诊开始 |
| ends_at | timestamptz | R | 停诊结束 |
| reason | varchar(300) | R | 请假或临时停诊原因 |
| state | varchar(20) | R | 有效或已撤销 |
| recorded_by | bigint | R;FK:staff.staff_id | 登记人员 |
| recorded_at | timestamptz | R | 登记时间 |

起止有序；一段停诊可影响多笔排班，一笔排班也可受多段停诊影响，按牙医及时间相交计算，不建立重复对应表。新增有效停诊须识别冲突预约并标待协调；不能仅更新排班状态。撤销后重新核验资源和安排，不自动恢复已改约或取消记录。

### appointment_change — 改约方案

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| change_id | bigint | PK | 方案编号 |
| original_id | bigint | R;FK:appointment.appointment_id | 需协调的原预约 |
| proposed_id | bigint | R;UQ;FK:appointment.appointment_id | 本方案候选预约 |
| absence_id | bigint | N;FK:dentist_unavailable.absence_id | 关联医生停诊 |
| unavailable_id | bigint | N;FK:resource_unavailable.unavailable_id | 关联资源禁用 |
| proposed_by | bigint | R;FK:staff.staff_id | 提交方案的前台 |
| proposed_at | timestamptz | R | 提交时间 |
| reason | varchar(300) | R | 患者申请或安排异常原因 |
| state | varchar(20) | R | 待答复、已同意、已拒绝、已撤回或已落实 |
| responded_at | timestamptz | N | 患者答复时间 |
| response_basis | varchar(300) | N | 答复内容及登记依据 |
| applied_at | timestamptz | N | 成功落实时间 |
| version | integer | R | 乐观锁版本，从1起，答复与落实递增 |

原预约与候选须不同且同一患者；候选previous_id必须指向原预约。一笔原预约可有多种历次方案，候选至多归属一份方案；同时至多一份待答复或已同意方案。已同意不代表已确认，落实时重新检查牙医停诊、排班与资源并原子改约；失败保留原安排。拒绝或撤回的候选置已取消。成功后继至多一个；答复时间与依据成组填写，已落实必须先同意且有落实时间。异常来源可均为空，也可同时存在，不能用XOR误限复合原因。停诊来源须对应原安排牙医且时间相交；资源禁用来源须与原预约使用资源及时间相交。方案只适用于尚未开始接诊的原预约；患者同意时回填候选确认依据，落实后与候选预约的确认信息一致。

### service_price_history — 项目调价记录

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| price_id | bigint | PK | 调价编号 |
| item_id | bigint | R;FK:service_item.item_id | 目录项目 |
| price | decimal(12,2) | R | 生效参考单价 |
| effective_at | timestamptz | R | 即时生效时间 |
| changed_by | bigint | R;FK:staff.staff_id | 管理人员 |
| reason | varchar(300) | R | 初始定价或调价原因 |
| branch_id | bigint | R;FK:branch.branch_id | 调价适用门店 |

UNIQUE(branch_id,item_id,effective_at)。门店首次开放项目同时建立首条价格历史；调价追加版本并同事务刷新branch_service.current_price。冻结的告知、实施、结算、发票快照不跟随目录调价。

### resource_category — 资源类别

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| category_id | bigint | PK | 类别编号 |
| category_code | varchar(30) | R;UQ | 业务类别代码 |
| name | varchar(80) | R;UQ | 类别名称 |
| description | varchar(300) | N | 牙椅或设备适用范围 |
| enabled | boolean | R | 是否用于新增资源需求 |

用于区分牙椅、口内扫描等不同能力的预约资源。类别停用仅阻止新增选择，不删除历史资源；本表不建立耗材库存。

### service_resource_requirement — 服务资源需求

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| item_id | bigint | PK;FK:service_item.item_id | 服务项目 |
| category_id | bigint | PK;FK:resource_category.category_id | 必需资源类别 |
| quantity | integer | R | 同一时段所需数量 |
| turnover_minutes | integer | R | 该资源预留周转时间 |

联合主键保证同项目同类别只一条需求；数量为正、周转非负。用于预约容量检查，不是临床操作规范。多个拟做项目共用同一牙椅时不简单累加，应按项目是否同时使用安排占用；无法明确设备时由前台与牙医核实后确认。需求修改不自动改写已确认占用，受影响预约须重新核验。

### dentist_service — 牙医可提供服务

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| dentist_id | bigint | PK;FK:staff.staff_id | 牙医 |
| item_id | bigint | PK;FK:service_item.item_id | 可预约项目 |
| enabled | boolean | R | 是否开放新预约 |
| verified_at | timestamptz | R | 最近业务核实时间 |

工作人员必须为牙医；该配置用于诊所开放预约的服务范围，不代替执业资格。确认预约同时检查资格、在岗、服务开放范围及出诊时段；停用不删除历史，需核查未来受影响预约。配置维护人由操作日志记录。

### appointment_item — 预约拟做项目

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| appointment_id | bigint | PK;FK:appointment.appointment_id | 预约 |
| item_id | bigint | PK;FK:service_item.item_id | 拟做项目 |
| estimated_quantity | decimal(10,2) | R | 预计数量 |
| remark | varchar(300) | N | 预约阶段需核实的事项 |

预计数量为正；正式确认至少有一个可预约项目，尚未明确治疗内容时可选择初诊检查或复诊评估。拟做项目用于服务资格、时长和资源检查，不代表确诊、实施或费用同意，不能直接生成结算。一个项目至多一行，多牙位等细节可在接诊时另行核实；改约候选复制需求后仍须重新确认。

### queue_ticket — 候诊登记

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| ticket_id | bigint | PK | 候诊编号 |
| appointment_id | bigint | R;UQ;FK:appointment.appointment_id | 已签到预约 |
| queue_date | date | R | 当日营业日期 |
| queue_no | integer | R | 当日候诊号码 |
| registered_by | bigint | N;FK:staff.staff_id | 人工签到经办人，自助签到可空 |
| registered_at | timestamptz | R | 进入候诊时间 |
| state | varchar(20) | R | 候诊、呼叫中、过号、已接诊或已退出 |
| priority | integer | R | 人工调整的排序等级 |
| priority_reason | varchar(300) | N | 非默认等级的说明 |
| closed_at | timestamptz | N | 接诊或退出时间 |
| branch_id | bigint | R;FK:branch.branch_id | 候诊号所属门店，受约束冗余 |
| checkin_channel | varchar(20) | R | 小程序或前台 |

UNIQUE(branch_id,queue_date,queue_no)，预约唯一。小程序患者在预约门店扫描短效签到码，服务端校验本人、门店和可签到时窗后自动取号，无需前台确认；人工入口可协助。branch_id须等于预约门店。过号后回队复用原号，不能记未到。「已退出」是候诊队列终态（离开队列）；「离店」是导致退出的行为/原因之一（如已签到后离店取消），两者不写成同义词。

### role — 系统角色

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| role_id | bigint | PK | 角色编号 |
| code | varchar(30) | R;UQ | patient、frontdesk、dentist、owner、sysadmin、inventory、finance |
| name | varchar(60) | R | 角色名称 |
| identity_type | varchar(20) | R | patient或staff |

患者、前台、牙医、经营负责人、系统管理员、库管、财务七类固定职责，可批准兼岗。角色并集不突破各自门店及患者数据范围；负责人和系统管理员不默认获得病历全文。

### permission — 操作权限

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| permission_id | bigint | PK | 权限编号 |
| code | varchar(80) | R;UQ | 接口使用的稳定权限代码 |
| name | varchar(80) | R | 操作说明 |

权限代码与用例及服务接口对应，由版本迁移维护，不由客户端传入决定授权。

### account_role — 账号角色

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| account_id | bigint | PK;FK:account.account_id | 被授权账号 |
| role_id | bigint | PK;FK:role.role_id | 授予角色 |
| granted_by | bigint | N;FK:account.account_id | 执行授权账号，初始化可空 |
| granted_at | timestamptz | R | 最近授权时间 |
| approval_basis | varchar(300) | R | 负责人确认依据或首次初始化依据 |

联合主键防止重复授予。牙医角色只授予资格已核实的在岗牙医；角色身份与账号身份一致。系统管理员依据负责人确认执行；不可自行提权，不可撤销最后一个可用系统管理员。撤销删除当前关联并写审计，历史依据留日志；初始化除外，授予人必填。

### role_permission — 角色权限

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| role_id | bigint | PK;FK:role.role_id | 角色 |
| permission_id | bigint | PK;FK:permission.permission_id | 操作权限 |

多对多关联，固定授权矩阵由版本维护；不能把角色并集解释为任意患者数据可访问。

### auth_session — 登录会话

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| session_id | bigint | PK | 会话编号 |
| account_id | bigint | R;FK:account.account_id | 所属账号 |
| client_type | varchar(20) | R | staff_web或patient_miniapp |
| token_hash | varchar(64) | R;UQ | 随机会话令牌的SHA256摘要 |
| csrf_token_hash | varchar(64) | N | 员工网页 CSRF 令牌摘要，患者端可空 |
| auth_version | integer | R | 登录时授权版本 |
| created_at | timestamptz | R | 建立时间 |
| last_seen_at | timestamptz | R | 最后访问时间 |
| expires_at | timestamptz | R | 绝对失效时间 |
| revoked_at | timestamptz | N | 注销或强制失效时间 |

仅存高熵随机令牌摘要；管理端使用安全Cookie，小程序使用受控短期令牌和安全存储。每次访问校验启用状态、授权版本、撤销及过期；患者和员工失效时长可配置，退出及调权撤销旧令牌。

### service_category — 诊疗服务分类

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| category_id | bigint | PK | 分类编号 |
| code | varchar(30) | R;UQ | 稳定分类代码 |
| name | varchar(80) | R;UQ | 分类名称 |
| display_order | integer | R | 显示顺序 |
| enabled | boolean | R | 是否开放目录展示 |

检查评估、洁治牙周、充填、牙髓治疗、拔牙、正畸、修复七类；分类不代表诊所已经具备全部资质或能力。停用只影响新选择，保留历史。

### organization — 经营主体

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| organization_id | bigint | PK | 经营主体编号 |
| legal_name | varchar(120) | R | 营业登记全称 |
| credit_code | varchar(30) | R;UQ | 统一社会信用代码 |
| contact_phone | varchar(30) | R | 公开联系电话 |
| responsible_staff_id | bigint | R;FK:staff.staff_id | 经营负责人 |
| state | varchar(20) | R | 筹备、营业、停用 |

本设计服务同一经营主体下的多家门店，不作为跨企业共享病历平台。独立法人门店需另行明确数据授权及开票主体，不能仅修改名称套用。

### branch — 门店档案

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| branch_id | bigint | PK | 门店编号 |
| organization_id | bigint | R;FK:organization.organization_id | 所属经营主体 |
| code | varchar(30) | R;UQ | 内部稳定门店代码 |
| name | varchar(100) | R | 公示门店名称 |
| address | varchar(300) | R | 实际地址 |
| phone | varchar(30) | R | 门店电话 |
| license_no | varchar(80) | R | 医疗机构许可或备案标识 |
| license_valid_to | date | N | 证照载明有效期，无期限另行核验 |
| license_no_expiry_confirmed | boolean | R | 是否已明确核验“无期限”，默认false；null有效期不等于永久有效 |
| opening_hours | varchar(200) | R | 营业时间说明，不替代医生排班 |
| manager_id | bigint | R;FK:staff.staff_id | 门店负责人 |
| state | varchar(20) | R | 草稿、待核验、营业、停用 |
| verified_by | bigint | N;FK:staff.staff_id | 档案核验人 |
| verified_at | timestamptz | N | 核验时间 |
| version | integer | R | 并发修改版本 |

新增填写全称、主体、地址、电话、资质、负责人和营业说明，经核验才发布。停用先处理未完预约、库存和账务；历史门店不得删除。排班另存，不把营业时间文本当号源。`license_no_expiry_confirmed=false` 时后续核验必须有有效 `license_valid_to`；为 true 时须有明确核验依据并留审计。T024-A 只创建草稿，核验/营业/停用动作后续实现。

### staff_branch — 员工门店任职

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| staff_id | bigint | PK;FK:staff.staff_id | 员工 |
| branch_id | bigint | PK;FK:branch.branch_id | 任职门店 |
| enabled | boolean | R | 是否允许当前在该店工作 |
| verified_at | timestamptz | R | 最近核实时间 |

多对多表示跨店执业或兼岗的工作范围，任职不是系统权限；安排接诊还须核实相应执业条件。历史调整由审计保留。

### role_branch_scope — 角色门店范围

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| account_id | bigint | PK;FK:account.account_id | 员工账号 |
| role_id | bigint | PK;FK:role.role_id | 已授予的角色 |
| branch_id | bigint | PK;FK:branch.branch_id | 该角色可访问的门店 |

另以联合外键(account_id,role_id)引用account_role。每个角色分别限定门店，避免财务角色拥有A店就使牙医角色获得A店权限。患者不使用本表，跨店负责人逐店授权；授权变更立即撤销旧会话。

### branch_service — 门店服务开放

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| branch_id | bigint | PK;FK:branch.branch_id | 门店 |
| item_id | bigint | PK;FK:service_item.item_id | 项目 |
| current_price | numeric(12,2) | R | 该店现行参考单价 |
| enabled | boolean | R | 该门店是否开放 |
| auto_bookable | boolean | R | 能否依据已发布时段自助约成 |

同一目录项目可在不同店采用不同价格。与门店价格历史同事务更新；公开参考价不代替检查后告知。初诊通过评估项目预约，无需自行选择准确治疗方案。

### schedule_service — 排班可约服务

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| schedule_id | bigint | PK;FK:schedule.schedule_id | 已配置排班 |
| item_id | bigint | PK;FK:service_item.item_id | 允许预约的项目 |
| duration_minutes | integer | R | 本班次该项目预约时长 |

联合主键。项目须同时在门店及牙医开放范围内，按初复诊限制筛选。时长用于预约资源估计，不能当治疗已完成证据。

### wechat_identity — 微信登录绑定

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| identity_id | bigint | PK | 绑定编号 |
| account_id | bigint | R;UQ;FK:account.account_id | 患者账号 |
| app_id | varchar(80) | R | 小程序标识 |
| open_id | varchar(128) | R | 当前小程序下的微信标识 |
| bound_at | timestamptz | R | 绑定时间 |

UNIQUE(app_id,open_id)。服务端换取标识并核验绑定；客户端不能自报openid。手机号一致不自动合并历史档案，历史档案用诊所发放的一次性核验凭证绑定。新患者可在线建新档，疑似重复转人工；不保存微信会话密钥明文。

### doctor_preference — 熟悉医生收藏

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| patient_id | bigint | PK;FK:patient.patient_id | 本人 |
| dentist_id | bigint | PK;FK:staff.staff_id | 收藏的牙医 |
| source | varchar(20) | R | 本人选择、熟人推荐或既往就诊 |
| created_at | timestamptz | R | 收藏时间 |

仅保存本人选择的医生与来源类别，不保存推荐人的健康记录或联系方式，不付转诊佣金。收藏不改变主治任期，也不保证医生任何时间均可接诊。

### booking_policy — 预约规则版本

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| policy_id | bigint | PK | 规则版本 |
| branch_id | bigint | R;FK:branch.branch_id | 适用门店 |
| version_no | integer | R | 店内规则版本 |
| late_hours | integer | R | 临近取消小时界限 |
| window_days | integer | R | 滚动统计天数 |
| event_limit | integer | R | 触发次数 |
| restriction_days | integer | R | 暂停自助新预约天数 |
| status | varchar(20) | R | draft、published或retired |
| restriction_enabled | boolean | R | 是否启用自动限制，默认false |
| effective_at | timestamptz | N | 生效时刻；草稿可空，发布必填 |
| approved_by | bigint | N;FK:staff.staff_id | 批准人；草稿可空，发布必填 |
| approved_at | timestamptz | N | 批准时间；发布必填 |
| version | integer | R | 乐观锁版本，从1起 |

UNIQUE(branch_id,version_no)。同店生效版本按发布时间区间选择，禁止两个版本相同 effective_at；已发布版本不可原地改参数，仅新版本替代。拟定参数为提前24小时界限、滚动7天内2次计入事件、暂停自助新预约7天；属于待诊所批准的设计值，不是调研事实。预约创建时绑定当时版本，规则不追溯加重。每店需一个已发布且 restriction_enabled=false 的基础政策才开放预约；首期不启用预约收费或自动罚款。

### booking_event — 预约履约事件

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| event_id | bigint | PK | 事件编号 |
| appointment_id | bigint | R;UQ;FK:appointment.appointment_id | 对应预约，一次预约最多一个计入事件 |
| kind | varchar(30) | R | 临近取消或核实未到 |
| occurred_at | timestamptz | R | 事件发生时间 |
| state | varchar(20) | R | 待核实、计入、豁免 |
| recorded_by | bigint | N;FK:account.account_id | 记录发起账号，系统事件可空 |
| reason | varchar(300) | R | 判断及豁免依据 |
| reviewed_at | timestamptz | N | 最近复核时间 |
| reviewed_by | bigint | N;FK:staff.staff_id | 复核人，与 reviewed_at 及判定依据成组 |

提前取消、诊所发起调整、已签到过号不计入。未到须过原时段结束后人工核实。紧急情况、平台故障等可豁免并留痕；申诉不删除原事件。只按事件发生时间统计，不按复核时间重新计次。

### booking_restriction — 自助预约限制

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| restriction_id | bigint | PK | 限制编号 |
| patient_id | bigint | R;FK:patient.patient_id | 被限制患者 |
| branch_id | bigint | R;FK:branch.branch_id | 限制门店 |
| policy_id | bigint | R;FK:booking_policy.policy_id | 采用规则版本 |
| trigger_event_id | bigint | R;UQ;FK:booking_event.event_id | 触发事件 |
| starts_at | timestamptz | R | 开始时间 |
| ends_at | timestamptz | R | 自动到期时间 |
| state | varchar(20) | R | 生效、到期、已撤销 |
| reviewed_by | bigint | N;FK:staff.staff_id | 复核撤销人 |
| reason | varchar(300) | R | 限制或撤销依据 |

同患者同店最多一条生效限制。只暂停新建自助预约，既有预约、取消、记录查询和必要就医协调仍可用。到期不重复使用旧事件触发；复核豁免后重算并撤销不再满足条件的限制。

### supplier — 供应商档案

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| supplier_id | bigint | PK | 供应商编号 |
| name | varchar(120) | R | 法定名称 |
| credit_code | varchar(30) | R;UQ | 主体标识 |
| qualification_ref | varchar(300) | R | 资质文件位置及核验依据 |
| valid_to | date | N | 资质有效期 |
| verified_by | bigint | R;FK:staff.staff_id | 核验人 |
| verified_at | timestamptz | R | 核验日期 |
| enabled | boolean | R | 可否新增采购 |

入库前核验供应资格和有效期；停用保留采购历史，不能用供应商名称替代稳定编号。

### asset — 设备资产卡片

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| asset_id | bigint | PK | 资产编号 |
| branch_id | bigint | R;FK:branch.branch_id | 使用门店 |
| supplier_id | bigint | N;FK:supplier.supplier_id | 供应方 |
| asset_code | varchar(40) | R;UQ | 资产编码 |
| name | varchar(100) | R | 设备名称 |
| serial_no | varchar(100) | N | 出厂序列号 |
| acquired_on | date | R | 取得日期 |
| in_service_on | date | R | 达到可使用状态日期 |
| original_cost | numeric(14,2) | R | 资本化取得成本 |
| residual_value | numeric(14,2) | R | 预计净残值 |
| useful_months | integer | R | 预计使用月数 |
| state | varchar(20) | R | 在用、维修、闲置、报废 |
| disposed_on | date | N | 处置日期 |

会计口径由负责人和财务确认，首期管理测算采用直线法、启用次月计提。非税法统一年限；折旧政策改变保留审批与未来计算依据，不重算已关账历史。资产跨店迁移首期走人工核实和月末切换，不回写旧折旧归属。

## 运行辅助表（本阶段新增，共6表）

### identity_challenge — 一次性身份凭证

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| challenge_id | uuid | PK | 凭证编号 |
| purpose | varchar(30) | R | wechat_registration或patient_binding |
| token_hash | varchar(64) | R;UQ | 凭证摘要 |
| app_id | varchar(80) | N | 小程序标识，注册用途 |
| open_id | varchar(128) | N | 微信标识，注册用途 |
| patient_id | bigint | N;FK:patient.patient_id | 绑定用途的目标患者 |
| issued_by | bigint | N;FK:account.account_id | 签发账号，绑定用途为前台 |
| branch_id | bigint | N;FK:branch.branch_id | 门店，绑定用途 |
| expires_at | timestamptz | R | 到期时间 |
| consumed_at | timestamptz | N | 消费时间 |
| revoked_at | timestamptz | N | 撤销时间，重签或历史行迁移时置位 |
| failed_attempts | integer | R | 失败次数，非负默认0 |
| created_at | timestamptz | R | 创建时间 |

wechat_registration 需服务端已验证 app_id/open_id 且尚未建完整 account；patient_binding 需 patient_id、issued_by、branch_id，由有该患者业务范围的前台线下核验本人后签发（服务端只留痕、不证明到场）。注册上下文10分钟、绑定凭证10分钟且最多5次失败；凭证至少128位随机熵，只存摘要。每患者至多一条未消费且未撤销的绑定凭证（部分唯一索引含已过期记录，签发前在患者锁下先撤销过期/耗尽记录）；重签显式引用旧凭证并原子撤销。签发权限 patients.bind_issue 仅默认授予 frontdesk。

### branch_booking_config — 门店签到配置

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| branch_id | bigint | PK;FK:branch.branch_id | 门店 |
| self_checkin_enabled | boolean | R | 自助签到开关，默认false |
| early_minutes | integer | N | 签到提前分钟，非负 |
| late_minutes | integer | N | 迟到宽限分钟，非负 |
| slot_step_minutes | integer | R | 时段步长，正整数默认15 |
| version | integer | R | 并发版本，正整数 |
| updated_by | bigint | R;FK:staff.staff_id | 更新人 |
| updated_at | timestamptz | R | 更新时间 |

启用自助签到必须填写 early_minutes 与 late_minutes，首次配置由负责人确认；slot_step_minutes 仅影响展示，不缩短真实服务时长。

### branch_opening_interval — 结构化营业区间

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| interval_id | bigint | PK | 区间编号 |
| branch_id | bigint | R;FK:branch.branch_id | 门店 |
| valid_on | date | R | 营业日期 |
| starts_at | timestamptz | R | 开始 |
| ends_at | timestamptz | R | 结束 |
| version | integer | R | 行级存储版本；API 聚合版本使用 branch.version |

起止有序、同店区间不得重叠（不限于同一 valid_on）；允许相邻；跨午夜拆为两日；门店日期按 Asia/Shanghai 解释。按日期范围替换，范围外保留；修改后须仍完整覆盖未结束已发布班次和活动预约，且与 branch 锁、branch.version 递增同事务。

### restriction_event — 限制触发事件

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| restriction_id | bigint | PK;FK:booking_restriction.restriction_id | 限制 |
| event_id | bigint | PK;UQ;FK:booking_event.event_id | 触发事件 |

联合主键；event_id 另加 UQ，保证每事件只归属一条限制。保存触发限制所使用的全部事件，不只最后一个 trigger_event_id。事件 patient/branch/policy 须与 restriction 完全一致才可关联。

### operation_request — 幂等操作记录

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| operation_id | uuid | PK | 操作编号 |
| actor_account_id | bigint | R;FK:account.account_id | 发起账号 |
| operation | varchar(80) | R | 操作标识 |
| request_key | varchar(64) | R | 客户端 Idempotency-Key |
| request_hash | char(64) | R | 规范化请求 SHA256 摘要 |
| target_id | bigint | N | 业务对象编号 |
| result_code | varchar(40) | N | 结果代码 |
| result_ref | varchar(40) | N | 结果引用（如预约编号） |
| created_at | timestamptz | R | 创建时间 |
| completed_at | timestamptz | N | 完成时间 |

UNIQUE(actor_account_id,operation,request_key)。不同账号使用相同客户端键互不冲突；同账号同操作同键返回原结果，不同摘要返回409。仅保存业务引用与状态，不保存含隐私的完整响应；与业务修改同事务提交。

### queue_counter — 候诊号计数器

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| branch_id | bigint | PK;FK:branch.branch_id | 门店 |
| queue_date | date | PK | 营业日期 |
| last_number | integer | R | 已发最大候诊号，非负 |

联合主键；签到末尾行锁递增，取消不回收历史号码。

本阶段最终共48表（42张基线依赖表＋6张运行辅助表），不代表这些模块已全部开放界面。
