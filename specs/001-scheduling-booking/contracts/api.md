# 第一阶段HTTP接口契约

前缀`/api/v1`。JSON UTF-8。下面是供后续实现的契约，不代表接口已经存在。
权限统一按[permissions.md](permissions.md)，数据及状态统一按[数据模型](../data-model.md)。

## 通用约定

- ID以字符串传输；时间RFC3339且带时区，例如2026-11-01T09:00:00+08:00；营业日YYYY-MM-DD。
- 状态保留报告中文值；不允许客户端直接PATCH state绕过业务动作。
- 请求体对象拒绝未知字段（OpenAPI 中 `additionalProperties: false`），未声明字段返回 422 UNKNOWN_FIELD，不得静默忽略。
- 列表返回`{items,next_cursor}`，limit默认20最大100。公开查询仅返回公开字段。
- 成功响应`{data,request_id}`；错误`{code,message,request_id,field_errors?}`，不得返回SQL、堆栈或他人记录。
- 401未认证，403无操作权限，404不可见或不存在，409版本/资源/幂等冲突，422字段或业务前置不满足，429限速；预约限制为403并附本人可见的结束时刻和人工联系方式。
- 业务数据变更带`Idempotency-Key`（作用域为当前账号+操作；同键同摘要返回原结果，不同摘要409）；修改已有记录（目标表有 version 列）还带`expected_version`（版本字段统一命名，不作 version）。合理例外：登录/登出/改密、单次消费凭证（注册/绑定）、天然幂等的 set 型 PUT/DELETE（任职、服务范围、角色门店、收藏）；目标表无 version 列的状态迁移（资源禁用解除、履约事件复核）用幂等键+状态前置条件保证。
- 授权必须先于幂等结果读取。客户端超时以同键重试，不能换键盲目再建。响应未明不是失败已确定。
- 员工会话 Cookie 名 `A_CLINIC_SESSION`（HttpOnly+Secure+SameSite=Lax），CSRF 请求头 `X-CSRF-Token`；患者不透明 Bearer 仅存摘要。
- 请求中的patient_id只允许员工代办端传入；患者端一律从会话确定本人。服务器计算结束时间、资源分配、确认医生和政策。

## 身份与基础配置

| 方法与路径 | 输入 | 输出/限制 |
| --- | --- | --- |
| POST /auth/wechat | code | 已绑定返回业务会话；未绑定返回短期registration_token，绝不相信客户端openid |
| POST /auth/patient-register | registration_token,name,phone,birth_date?,allergy_status,allergy_notes? | 创建本人档案和账号，消费上下文，返回会话；allergy_status 为有/不确定时 allergy_notes 必填 |
| POST /auth/patient-bind | registration_token,binding_token | 核验并绑定旧档，凭证单次；失败限速 |
| POST /staff/patients/{id}/binding-token | branch_id,patient_name,patient_phone,appointment_id?,verification_method,in_person | 前台本店签发一次性绑定凭证（10分钟），仅该次显示；姓名+电话匹配档案且线下核验后签发 |
| POST /staff/patients/{id}/binding-token/reissue | 同签发 + previous_challenge_ref | 引用当前有效旧凭证重签，撤销旧凭证；旧凭证已失效返回 409 STALE_BINDING_REISSUE |
| POST /auth/staff-login | login_name,password | Cookie会话、是否强制改密及CSRF令牌 |
| POST /auth/password-change | current_password,new_password | 撤销旧会话，返回新会话；不得记录明文 |
| POST /auth/logout | 无 | 撤销当前会话 |
| GET /auth/me | 无 | 本人最小信息、各角色门店范围、强制改密标记 |
| GET/POST /me/profile | 联系方式、过敏申报及版本（修改时） | 本人资料；不修改历史接诊快照 |
| GET/POST /staff/branches | 分页/新增机构内门店档案 | 列表按同一 role 的 branch.configure + branch scope + 有效已核实 staff_branch；POST 仅唯一 organization 负责人且 owner 有 branches.create，创建草稿 v1，manager_id 首期=创建者，建立本店启动授权并撤销创建者会话；重放不重复授权 |
| GET /staff/branches/{id} | 门店管理详情 | 同列表权限；无权限/不存在统一404；返回档案、state/version、核验摘要与 license_no_expiry_confirmed，不含营业区间/患者/财务 |
| PATCH /staff/branches/{id} | 可选档案字段 + expected_version | 负责人，Idempotency-Key；缺省不改、必填不可 null、拒绝服务端字段；营业中不得改执业地址/许可证，许可事实变化清除核验/无期限确认；版本冲突零写入 |
| POST /staff/branches/{id}/submit | expected_version | 草稿→待核验；仅本店 branch.configure 权限 |
| POST /staff/branches/{id}/verify | expected_version,verification_basis,license_no_expiry_confirmed? | 待核验→营业；许可未过期或明确无期限；核验人/时间取会话与 Clock |
| POST /staff/branches/{id}/disable | expected_version,reason | 营业→停用；未结束已发布班次/活动预约返回精确 impact 409，零写入 |
| POST /staff/branches/{id}/reopen | expected_version | 停用→待核验；不直接恢复营业/旧排班/预约 |
| GET /staff/staff-members | 分页 cursor/limit | 全局负责人（唯一 responsible_staff_id + owner + staff.manage_global）可查全组织最小档案；其他具本店 branch.configure + 同角色 scope + 操作者有效已核验任职者，只能查本店已有任职员工（含历史/停用关系）；稳定 staff_id 游标，默认20最大100；跨店任职不重复 |
| POST /staff/staff-members | name,staff_type,job_title,practice_no?,credential?（可选 pending 资格） | 仅上述全局负责人，Idempotency-Key 必填；创建在岗 staff、version=1，可选 pending credential（verified_by/at 为空）；不建 staff_branch/account/account_role/role_branch_scope，不自动授予资格；牙医 practice_no 必填且唯一；不同键相同 practice_no 409 |
| GET /staff/staff-members/{id} | 无 | 全局负责人可查最小详情；门店负责人只看本店授权任职范围内对象，统一404不泄露存在性；返回 staff 字段/version、可见门店关系及资格状态摘要；practice_no 对普通员工可为 null；不返回证书原文、账号、角色、患者或其他门店关系；同一快照读取 |
| POST /staff/staff-members/{id}/credentials | credential_type,certificate_no,valid_from?,valid_to?,no_expiry_confirmed? | 仅全局负责人，Idempotency-Key；写 pending credential（version1）与 submit 历史；不授角色/账号；过期证件可 pending，非法日期或 true 时 valid_to 非空 422；不允许客户端写核验状态/人/时间 |
| POST /staff/staff-members/{id}/credentials/{credential_id}/review | decision(verified/rejected),basis,expected_version | 仅全局负责人；仅 pending→verified/rejected；version+1 并追加 verify/reject 历史；不提供撤销/重核；并发同版本仅一成功，其余409；过期不得 verified |
| GET /staff/staff-members/{id}/credentials/{credential_id}/reviews | cursor,limit | 仅全局负责人；只读追加式历史，review_id 稳定游标，默认20最大100；可返回 basis，但不返回证书原文；credential 必须属于路径员工 |
| GET /staff/staff-members/{id}/credentials/{credential_id}/facts | 无 | 仅唯一组织负责人；单条返回证书编号、类别、有效日期、审核状态和版本；成功读取留下不含编号的访问审计，响应 no-store；跨员工 404，其他角色 403；读取本身不代表证件已核实 |
| PUT /staff/staff-members/{id}/branches/{branch_id} | enabled,verification_basis | T025-B 契约保留；负责人；停用前列受影响班次；本批未实现 |
| PUT /staff/staff-members/{id}/services/{item_id} | enabled,verification_basis | T025-C 契约保留；负责人核实医生可开展项目；本批未实现 |
| GET/POST /staff/service-items | 类别、名称、时长、资源需求 | 负责人，价格仅保存配置，不开账单 |
| PUT /staff/branches/{id}/services/{item_id} | enabled,auto_bookable,current_price,reason | 同时维护初始/变动价格历史，不能只改快照 |
| GET/POST /staff/resources | branch_id,category_id,name | 负责人，禁止填写不存在的asset_id |
| GET/PUT /staff/branches/{id}/booking-config | 签到窗口、步长、expected_version、Idempotency-Key（写入） | 负责人读取与设置；未配置时返回关闭默认值，启用须配置提前/迟到分钟；同值不增版本 |
| GET/PUT /staff/branches/{id}/opening-intervals | GET 有界 from_date/to_date；PUT 日期范围+intervals+expected_version | 负责人；同角色 branch.configure + 本店 scope + 有效已核实任职。GET 最多31日且版本/区间同一快照；PUT 范围替换、branch.version 聚合版本、Idempotency-Key；今天已开始及过去区间不可新增/改写，历史不可写；覆盖只检查已发布班次和已确认/候诊中/接诊中预约（待确认不占号不参与），不足返回 impact 409，零写入；重放返回当前权威视图 |
| GET/POST /staff/accounts | 员工账号资料（POST 只收 staff_id、login_name、Idempotency-Key） | 系统管理员（已完成强制改密），目标须在岗且有有效已核验任职；创建待激活账号（无密码、不可登录），不自动授予角色 |
| GET/PUT /staff/accounts/{id}/role-scopes | GET 返回目标账号的 state、roles 与 version；PUT 收 expected_version + 角色+门店组合、批准依据 | 系统管理员；GET 单事务读取；PUT 管理员禁止自提权，版本不匹配 409 且不写角色/会话/审计；角色×门店分别限定，全量替换，同值重复无副作用，成功递增 auth_version 并撤销会话 |
| POST /staff/accounts/{id}/disable | 原因 | 最后管理员保护，停用并撤销会话；待激活账号（无密码）拒绝停用，返回 422 NOT_ACTIVATED，不把停用当取消首次激活资格 |

新增和修改资格、服务类别及资源类别遵循上述负责人权限，表单校验必填字段和引用归属；实现时在OpenAPI展开具体DTO，不直接开放任意表CRUD。

## 公开查询与患者预约

| 方法与路径 | 输入 | 输出/规则 |
| --- | --- | --- |
| GET /public/branches | cursor,limit | 营业门店公开信息 |
| GET /public/branches/{id} | 无 | 地址、电话、营业说明及当前公开规则 |
| GET /public/doctors | branch_id?,item_id? | 经核验公开医生列表 |
| GET /public/doctors/{id} | 无 | 公开名片及出诊门店，不附分享者信息 |
| GET /public/slots | branch_id,item_id,dentist_id?,visit_type,from,to | 可约起止、schedule_id、医生、当前policy_id；一次最多31日 |
| GET /me/doctor-preferences | 分页 | 本人收藏 |
| PUT/DELETE /me/doctor-preferences/{doctor_id} | source（新增时） | 本人选择/熟人推荐/既往就诊 |
| GET /me/followup-plans | 分页 | 本人已核实建议，只读，不自动生成治疗建议 |
| POST /me/appointments | schedule_id,item_id,starts_at；可选 visit_type、purpose、followup_plan_id；Idempotency-Key 必填 | 当前实现返回 201 已确认预约；复诊关联须本人待协调计划、原主治、足够时长；取消后回到待协调，改约保留关联 |
| GET /me/appointments | 状态、日期、分页 | 本人记录及coordination_state |
| GET /me/appointments/{id} | 无 | 凭证、取消规则、确认医生、本人可见协调结果 |
| POST /me/appointments/{id}/cancel | reason,expected_version | 已取消及版本，既有候诊同时退出 |
| POST /me/appointments/{id}/reschedule | 新schedule_id,item_id,starts_at,policy_id,expected_version | 旧单与新单ID；失败原单保留 |
| POST /me/changes/{id}/response | accept/reject,expected_version | 保存答复；接受不等于号源已落实，另返回落实结果/待协调 |
| GET /me/changes | 无 | 本人的最近100条协调方案；候选不占号，`applied=false` 不代表已改约 |
| POST /me/appointments/{id}/checkin | branch_checkin_token,expected_version,Idempotency-Key | 扫描本店60秒短效码，本人已确认预约在当天窗口内签到；与前台共用 queue_ticket/queue_counter，重复返回原号；同键重放仍核验归属但不重验过期码 |
| GET /me/restrictions | 无 | 本人限制、依据、到期和申诉途径 |
| POST /me/restrictions/{id}/appeal | reason | 建联系待办及审计，不自动撤销或重复创建同次申诉 |

预约请求不接受ends_at、confirmed_dentist_id、state或任意resource_ids；服务端按排班服务和资源条件计算；服务端拒绝 starts_at 早于当前时间的提交，不得通过回填过去时段绕过冲突检查（历史数据导入走独立校验通道）。
本阶段每笔预约只支持一个明确项目或复诊建议（单一 item_id），多项目组合暂不开放，转人工评估并安排检查评估。
policy_id必须与提交时可用版本相符；变化返回409 POLICY_CHANGED及新公开规则，让患者重读后提交。
需要人工核实的初步申请仍需明确门店、意向和拟约时间，不伪造可确认资源；若所选时段已失效返回提示，前台可以登记独立待确认需求。

## 员工工作台

| 方法与路径 | 输入 | 输出/规则 |
| --- | --- | --- |
| GET/POST /staff/schedules | 门店、医生、起止、初复诊、服务时长、缓冲 | 草稿，负责人操作 |
| POST /staff/schedules/{id}/publish | expected_version | 校验资格、营业、任职和资源后发布 |
| POST /staff/schedules/{id}/suspend | reason,expected_version | 关闭新增并列受影响预约；不自动替诊 |
| POST /staff/dentist-unavailability | doctor_id,starts_at,ends_at,reason | 唯一组织负责人登记全局停诊；相交时段立即阻止新预约；既有预约保留，逐患者协调另行落实 |
| POST /staff/resource-unavailability | resource_id,kind,starts_at,ends_at,reason | 本店有 branch.configure 且任职已核验的负责人登记维护/故障；阻止该资源新占用；既有预约保留 |
| POST /staff/resource-unavailability/{id}/resolve | resolve_type,resolve_basis | 负责人，resolve_type=误登记撤销/已修复；人工核实后成组记录 resolved_by（取当前会话，不接受客户端传入，不得覆盖原登记人）/resolved_at/resolve_type/resolve_basis；解除本条后仍校验其他有效禁用记录，不因预计结束时间已过自动恢复 |

当前 `GET /staff/appointments` 及工作台预约行返回 `unavailability_alert`（布尔）：按有效医生停诊或该预约占用资源的有效禁用实时计算。前台显示“待人工协调，尚未通知”并阻止直接签到；它不是正式 `coordination_state` 状态迁移，也不代表已经联系患者或完成改约。
| GET /staff/appointments | branch_id,date,filters,cursor | 前台/本人医生按范围过滤 |
| GET/POST /staff/patients | 精确核验检索/实际患者档案 | 前台，仅办理所需最小信息，非任意跨组织查档 |
| POST /staff/appointments | patient_id及预约字段、channel、consent_basis | 前台代办，同一校验，不绕过资源规则；人工渠道（电话/微信登记/现场）consent_basis 必填 |
| POST /staff/appointments/{id}/confirm | schedule_id,item_id,starts_at,policy_id,consent_basis,expected_version | 待确认转已确认，同一确认服务；consent_basis 必填 |
| POST /staff/appointments/{id}/cancel | reason,initiated_by,expected_version | 前台核实发起方；诊所原因不计处罚 |
| POST /staff/appointments/{id}/changes | 候选安排、原因、停诊/资源原因引用、版本 | 前台拟方案，候选待确认不占号 |
| POST /staff/changes/{id}/response | accept/reject,confirmation_basis,expected_version | 前台登记真实电话等答复，不默认同意 |
| POST /staff/changes/{id}/apply | expected_version | 已同意才落实，冲突409并保留协调事实 |
| POST /staff/changes/{id}/withdraw | expected_version,reason；Idempotency-Key | 前台撤回待答复或已同意方案，取消未占号候选；原预约和已记录的患者答复保持，已同意时需另行告知患者 |
| GET /staff/changes | branch_id | 本店最近100条协调方案；需本店代约权限和有效任职 |
| GET/POST /staff/contacts | 本店人工联系记录；受影响预约编号、渠道、实际结果、可选知晓依据及下次联系时间 | 当前仅支持停诊/资源禁用影响预约的人工联系；`已联系` 是已登记尝试，只有知晓依据才标记患者知晓；不自动改约或发送系统通知 |
| GET /staff/contacts/due | branch_id、可选 cursor/limit | 本店仍受影响且需首次联系、未安排跟进或下次联系已到期的预约编号与时间；不返回患者身份，不表示自动通知送达 |
| POST /staff/branches/{id}/checkin-code | Idempotency-Key | 前台在已核验营业门店且自助签到启用时生成60秒签名码，不含患者资料；同键重放原码，过期后须新键 |
| POST /staff/appointments/{id}/checkin | expected_version,verification_basis,exception_reason? | 前台核实，异常时间须理由；取消、已改约、停诊不可直接强签 |
| GET /staff/queue | branch_id,date | 前台或本人牙医可见的候诊最小资料 |
| POST /staff/appointments/{id}/no-show-review | reason,expected_version | 结束且未签到才可核实未到 |
| POST /staff/booking-events/{id}/review | counted/exempt,reason | 前台本店复核，重算限制并审计 |
| GET /staff/restrictions | branch_id,filters | 前台处理申诉，不显示其他门店记录 |
| POST /staff/booking-policies | branch_id,params,restriction_enabled | 负责人草稿，默认不启用限制 |
| POST /staff/booking-policies/{id}/publish | approval_basis,effective_at,expected_version | 负责人批准，版本不可原地加重 |

本阶段不开放叫号、临床诊断、支付或开票接口（开票为完整系统必要功能，列入后续收费结算阶段，暂缓实现而非删除）。现场无预约先建现场预约再签到。
已发布班次的医生、门店、时段或服务发生实质变更，通过「暂停旧班次→新建并发布→协调原预约」实现，不提供直接改写已发布班次的接口；草稿班次可带 expected_version 修改。

## 请求与响应示例

```json
{"schedule_id":"1001","item_id":"11","starts_at":"2026-11-01T09:00:00+08:00","visit_type":"初诊","policy_id":"3"}
```

```json
{"data":{"appointment_id":"5001","state":"已确认","coordination_state":"无需协调","confirmed_dentist_id":"21","starts_at":"2026-11-01T09:00:00+08:00","ends_at":"2026-11-01T09:30:00+08:00","version":1},"request_id":"example-request"}
```

```json
{"code":"SLOT_UNAVAILABLE","message":"该时段已不可预约，请选择其他时间。","request_id":"example-request"}
```

这些ID、时间及响应仅为合成示例。下一阶段任务须将此契约转成可校验OpenAPI及契约测试，未列全部字段的配置DTO以data-model字典及权限为准。


### 员工凭证实现取值（T013，2026-10-22）

员工登录名去首尾空白转小写，匹配 `[a-z0-9][a-z0-9._-]{2,99}`。新密码至少12个Unicode码点、最多72个UTF-8字节，使用BCrypt cost=12；现有密码不静默截断。登录/改密按账号键及直接连接IP进行进程内15分钟限流（10/60次，含成功请求），超限429；云端网关限流另行配置。登录响应及改密响应均禁止缓存。初始化仅通过非Web交互命令，初始sysadmin无临床或门店权限。详细运行方式见docs/development/staff-login.md。此处补充实现取值，不改变预约业务范围。


### 账号维护实现取值（T014，2026-10-22）

GET /staff/accounts仅系统管理员可用，游标为上一页最后一个正整数字符串账号ID，不包含患者账号或密码摘要。PUT角色范围最多7项、不允许同角色重复；每项最多100个不重复门店，须有有效已核验任职。sysadmin的门店列表为空，表示仅安装实例级身份维护，不表示临床全门店。dentist须有效已核验dentist_practice资格。角色批准依据拒绝空白；空角色列表只撤权。PUT禁止修改自己角色，授权变更/停用均撤销目标旧会话。新建账号POST仍待安全激活与幂等机制，不应在前端宣称已可用。


### 员工账号创建与首次激活实现取值（2026-10-22）

POST /staff/accounts 仅有效且已完成强制改密的sysadmin可调用，请求体只收staff_id与login_name，请求头Idempotency-Key必填。目标员工须在岗（employment_state=在岗）且存在有效已核验staff_branch任职，不能凭一个staff_id直接认定已核验；不自动授予任何角色。创建的是待激活账号：enabled=false、password_hash=NULL、must_change_password=true；响应仅返回最小账号信息（account_id、login_name、staff_id、enabled、must_change_password、state），不返回password_hash。state按是否已设密码区分：无密码=pending_activation；有密码且enabled=active；有密码且已停用=disabled。同键重放不改变账号，只按当前行重建准确状态。幂等作用域为账号+操作+键：同键同内容重放返回原账号（200），同键不同内容409 IDEMPOTENCY_CONFLICT，不同键同员工/同登录名统一转换为业务409 ACCOUNT_EXISTS，不返回SQL或含他人身份的详细信息。

首次激活通过服务器本地非Web交互命令完成（`--spring.main.web-application-type=none --app.activation.enabled=true`，需真实交互终端）。启动前守卫在数据库/服务器初始化前校验activation=true必须为非Web模式，且bootstrap与activation不得同时启用；命令内真实终端检查作为第二层保护。只能激活尚无password_hash的待激活账号，禁止借此重置密码或重新启用已激活后停用的账号；验证在岗及核验任职，行锁保护，一次成功后重复调用拒绝。输入批准依据、隐藏输入并确认初始密码（复用StaffPassword规则）；写摘要、启用、强制改密与审计同事务。不自动授予角色、不提供公网激活接口、不在命令行/日志输出密码。这是单实例/本地演示可实施的受控流程，需服务器管理员协助，不是完整自助激活体系；以后改邀请激活属于另一个明确变更。

GET /auth/me 增加must_change_password，供前端刷新后继续强制改密；只暴露最小字段。员工CSRF仅在登录/改密响应获得，管理端只在当前标签页sessionStorage保存该CSRF值，不保存密码或会话Cookie；CSRF丢失时要求重新登录，不能伪造CSRF、从HttpOnly Cookie读令牌或悄悄关闭保护。


## T025-B2 第一批：资格撤销

新增 POST /staff/staff-members/{id}/credentials/{credential_id}/revoke。请求仅 basis（非空，最多300字符）、expected_version（正整数），以及 Idempotency-Key；权限仍为全局组织负责人。只允许 verified→rejected，追加 action=revoke 历史，与首次驳回区分。不允许重核或修改证件事实。

成功返回现有 CredentialDetailResult；幂等重放返回当前权威视图。版本冲突或终态重复操作409；未授权403，归属不符404，非法输入422；使用既有错误码。权限在幂等锁前后及业务锁后重查。原核验人/时间保留，撤销人/时间/依据进入历史。不更改账号、角色、会话、预约、排班或资源占用；自然到期不自动改审核状态。

V008只扩展历史动作/转换约束，不修改V001–V007。不代表资格准入、预约协调或审核页面已经完成；统一未来时段校验及下游接入仍待实施。


### Codex 收款幂等（2026-10-29）
POST /staff/bills/{id}/payments 必须携带 Idempotency-Key。账号+操作+键范围内，同键同请求不重复登记；同键不同账单/金额/方式/备注返回409。重放返回当前账单视图，不保证原响应快照。前端未知结果保留键，收到有效成功响应才清除。新增部分收款300/1000重复请求回归。本段记录当时收款批次；实际接诊与复诊现状见下文。


## 实际接诊修正（2026-10-29）
新增 POST /staff/appointments/{id}/start-visit，须 clinical.record 门店权限且当前员工为确认医生。候诊进入接诊中，服务端记录 actual_started_at/actual_dentist_id；重复开始不重置时间。完成记录仅实际医生可提交，使用实际开始时间，不再复制预约开始时间。历史未知不回填，旧接诊中且缺少开始事实不得直接伪造完成，须后续受控历史更正流程。V014增加两个字段；后续复诊闭环和 Word 同步状态见 2026-11-16 节。


## 前台代约幂等补齐（2026-10-29）
POST /staff/appointments 必须传 Idempotency-Key 和 consent_basis。范围为账号+操作+键，同键同内容不重复预约，同键不同内容409；重放仍检查当前授权及预约可见性，返回当前预约视图。前端保留结果未知请求键，不自动重发；患者确认依据显式填写，复用 appointment.consent_basis。
此段仅记录当时前台代约批次的范围；患者端预约/改约及复诊关联的现状见后续章节。


## 2026-10-29 患者预约时间与取消边界

患者新预约和改约的新时段必须在服务端当前时间之后；检查在取得目标排班锁后执行，不能回填过去预约。改约失败由同一事务回滚，原预约时段和版本保留。

患者自行取消仅开放给“已确认”预约；候诊中、接诊中及终态不得通过自助取消跳过现场业务流程。需要离店或中止接诊时，应由工作人员按正式流程处理，本批未实现该流程。患者改约、取消先按本人归属检查，不存在与他人预约统一返回404。

本批未完成患者预约/改约的幂等键、版本保护、改约历史链及完整门店/资格/营业区间校验，不代表患者预约模块整体验收。


### 2026-10-29 患者创建预约幂等已接入
POST /me/appointments 必须携带 Idempotency-Key，按账号和操作隔离；重放重新检查身份及本人归属，返回当前预约视图。小程序网络/解析失败保留同键，不自动重发，切换会话清理键。改约幂等、expected_version、历史链尚未实现，不能视为本批交付。


## 2026-10-29 患者改约历史与防重复
改约请求增加必填 expected_version、reason 和 Idempotency-Key。旧预约转为已改约，原时间、医生、项目及原资源历史保留；新预约 previous_id 指向旧单，继承复诊计划、目的、就诊类型及特殊需求。旧单退出活动、释放资源、新单创建、资源竞争和审计在同一事务中，失败整体回滚。同一幂等键重放返回当前 original/replacement，两者从一条SQL取得；新请求旧版本返回409。复诊计划不因改约完成。

本批改约先按ID顺序锁涉及门店、排班，再锁旧预约；仍需统一前台创建、签到与其他写入路径的整体锁协议，不能声称已消除全部跨模块死锁。政策版本校验（原契约policy_id）、完整资格与营业区间等业务校验尚未补齐，不因本次契约对齐而取消需求。小程序未做开发者工具/真机验证；Word另行同步。

## 2026-11-16 患者复诊计划与预约关联

`POST /me/appointments` 当前实现不接收客户端 `policy_id`，服务端使用门店当前已发布政策；返回 201 已确认预约。可选 `followup_plan_id` 必须属于本人源就诊，计划状态为待协调，预约为复诊、原计划医生且时长满足计划。计划指定 `service_item_id` 时，创建和改约的预约项目必须一致；未指定的旧计划继续允许符合其他条件的项目。计划关联与状态「已预约」在创建事务内写入；患者或前台取消后若无活动关联则回到待协调。患者改约保留计划引用和旧单历史，不允许自助换医生或缩短计划时长。同键重放不重复写入，仍检查当前身份和可见性。

预约完成不代表复诊目的已达到，计划状态须依据实际医生的独立确认更新。临时替诊应走门店协调与患者明确同意，不能由患者端自行换医生。

`POST /staff/visits` 对关联复诊计划的预约要求实际接诊医生同时提交 `followup_goal_achieved` 和非空临床记录。`true` 将计划置为已完成，`false` 回到待协调；预约完成、就诊记录、计划状态与审计同事务。普通就诊不接受该字段。原目标变更、暂不复诊答复和更正版本仍待后续流程。

`POST /staff/followup-plans` 仅源就诊的实际接诊牙医本人可创建；服务端先核实门店临床权限和医师身份，再锁源就诊记录并复核。每次源就诊同时最多有一笔「待协调/已预约」计划，重复创建返回 409 `FOLLOWUP_PLAN_ALREADY_ACTIVE`。其他牙医不能代原医师签发，正式交接须另行设计受控流程。


## 2026-10-29 统一预约可用性校验

患者创建、患者改约、前台代预约现在共用同一服务端校验：

- 门店 `营业` 且已核验；
- 医生为在岗牙医，目标门店 `staff_branch` 已启用且已核实；
- 至少一条 `dentist_practice` 已核验资格完整覆盖预约日期区间；空截止日仅 `no_expiry_confirmed=true` 可用；
- `service_item`、`branch_service`、`dentist_service`、`schedule_service` 同时开放；患者自助额外要求 `branch_service.auto_bookable=true`；
- 完整 `[starts_at, ends_at)` 在已发布排班内；
- 完整区间被 `branch_opening_interval` 并集覆盖，允许相邻区间，午休缺口拒绝；无结构化区间不回退文本营业时间；
- 有效 `dentist_unavailable` 与资源维护记录阻止；
- 写锁顺序：branch → schedule → appointment；改约按 branch/schedule ID 升序；创建/发布排班先锁 branch。

失败返回既有 409 `SLOT_UNAVAILABLE`；失败不留下预约、资源或幂等残留。同键重放只检查当前身份/可见性，不重新执行可用性创建。该批未新增端点，OpenAPI 无需新增路径；未改政策版本校验、取消惩罚、收费/退款/库存/通知。详见 `docs/development/booking-availability.md`。


### 2026-10-29 可用性等锁边界修复

共享可用性方法 `@Transactional(MANDATORY)`，只能由已有事务的预约写入调用。取得 branch/schedule 行锁后重新取 Clock 检查时段；共享校验返回后、写入前前台重新检查门店权限，患者重新检查当前身份。数据库连接/SQL/锁异常不转换为 `SLOT_UNAVAILABLE`；无效 schedule 以查询空结果作为正常不可用。确定性测试覆盖等锁期间时钟推进、撤权、无效 ID、数据库故障和无事务调用。


### 2026-10-29 双端可约时段接线

`GET /me/booking` 与 `GET /staff/booking` 增加 `availability: [{schedule_id,item_id,slots[]}]`，由后端按已发布排班、结构化营业区间、时长、资格、服务范围、医生停诊/资源存在性生成候选项；患者自助要求 `auto_bookable=true`，前台代约不要求。候选项不代表预占，可能仍被其他预约占用；提交时服务端再次校验并返回 409，页面收到冲突后重新读取权威 availability 和预约列表。OpenAPI 已增加 `/me/booking` 说明。


### 2026-10-29 双端 availability

`GET /me/booking` 与 `GET /staff/booking` 增加 `availability: [{schedule_id,item_id,slots[]}]`。候选项由后端营业区间、排班、资格/任职、服务范围、停诊和牙椅存在性生成；患者自助要求 `auto_bookable=true`，前台代约不要求。候选项不代表预占，提交仍锁内复查，冲突 409 后页面重新查询。详见 `docs/development/booking-e2e.md` 与 `booking-availability.md`。


### 2026-10-29 授权隔离与真实候选修正

- `GET /staff/booking` 的 availability 只包含当前账号已授权门店的排班；患者列表只返回授权门店已有预约业务关联的最小患者信息，不再全库返回。无历史关联的新患者精确检索暂不新增接口。
- availability 在只读查询中排除医生的活动预约重叠，以及全部可用牙椅均被有效维护或 resource_booking 占用的情况；患者自助还排除本人跨医生、跨门店的活动预约重叠。活动占用状态为已确认、候诊中、接诊中；待确认不占号。维护预计结束不等于自动解除。候选仍不预占，提交锁内复查。
- `/me/booking` 与 `/staff/booking` 的 OpenAPI 响应已补 `availability`、`previous_id`。
- 页面保留原始 ISO 时段值；409 重查后提示，网络超时不宣称失败。


### 2026-10-30 待确认不占号

候选查询和前台/患者预约提交的活动占用状态统一为 `已确认/候诊中/接诊中`。`待确认` 不占医生、患者或牙椅：不写 `resource_booking`，不进入排他约束，不隐藏候选，不阻止确认预约；两个待确认可指向同一时段，确认事务重新锁内校验最多一个成功。取消待确认不释放资源；门店停用影响仍统计待确认作为未处理事项。数据库排他约束与实现状态集合一致。本项不改变请求/响应字段，OpenAPI 结构无需变更；新增真实 PostgreSQL 回归测试。


### 2026-10-30 前台新患者选择契约（后端已实现，页面待做）

本段为已实现后端契约；管理端页面尚未接入。

- `GET /staff/patients?branch_id=&name=&phone=`：精确姓名+规范化电话，单一机构内返回最多 10 个最小候选（`patient_id/display_name/phone_suffix/birth_year/match_type`），不返回完整电话、过敏、病历、账号或历史；无匹配 200 空候选。需前台 `patients.lookup` + 本店有效任职。姓名电话只用于找候选，**不代表已核实本人**。
- `POST /staff/patients/{id}/selection`：请求 `branch_id,name,phone,verification_method,verification_result,resolution,in_person`。`verification_method` 只能是 `id_document|visit_evidence`，必须 `verification_result=confirmed`、`in_person=true`；否则不签发。成功返回一次性 `selection_token`（只显示一次）和 `expires_at`，只存 SHA-256 摘要。需 `patients.lookup`。
- `POST /staff/patients`：只建 patient 档案，不建 account/wechat；需 `patients.create` + `Idempotency-Key`；同键重放不能重显 token，只返回已建档患者/`token_shown_once`，前台须重新执行受控选择签发新 token；同键不得重复建档。
- `POST /staff/appointments`：新增必填 `patient_selection_token`，绑定 actor、patient、目标预约门店；成功预约同事务核销，失败不核销。同一 Idempotency-Key 成功重放先核验当前权限和预约可见性，返回原预约，不要求旧 token 有效；不同幂等键使用同一已消费 token 返回 409，最多一笔预约。
- 新权限 `patients.lookup`、`patients.create` 默认仅 frontdesk；现有 `patients.bind_issue` 与预约写权限分离。
- 并发约束：`patient_selection` 行 `FOR UPDATE` + 条件 `consumed_at IS NULL` 更新；同一 token 不同幂等键并发最多一个成功。
- 审计只记录 actor/branch/patient_id/核验方式/结果/处理类型，不记录姓名、完整电话、证件号原文或自由文本。


### 2026-10-30 选择分类由服务端判定

签发 `selection_token` 前，服务端重新查询候选并计算精确匹配数、同电话、同名：
唯一精确且无冲突仅允许 `single_exact`；多精确仅允许 `manual_candidate`；同电话才允许 `shared_phone`；同名才允许 `same_name`。请求分类不符返回 409，不签发凭证、不写成功审计。建档的 `duplicate_resolution` 同样按实际冲突重算，无对应冲突时拒绝。
# 店内沟通（2026-11-09 实现补充）

前台与同门店牙医可通过 `GET /staff/internal-messages/contacts` 获取当前可联系员工，`GET /staff/internal-messages` 分页读取指定门店与对方的消息，`POST /staff/internal-messages` 用 `branch_id`、`peer_staff_id`、`body` 发送消息。发送需 `Idempotency-Key`、员工 Cookie 和 CSRF；正文 1–500 字，保存后不可编辑或删除。服务端逐次核验双方的同店角色范围与有效任职；owner/sysadmin 不自动获得权限。消息不构成业务审批，改约、替诊等必须另走正式接口。

## 供应商档案（2026-11-09 已实现）

`GET /staff/suppliers` 按 `supplier_id` 游标分页，只返回名称、主体标识、有效期、启用状态和核验时间；`GET /staff/suppliers/{id}` 返回授权人员可见的资质依据与核验员工编号。`POST /staff/suppliers` 要求 `name`、唯一 `credit_code`、人工核验依据 `qualification_ref`、未过期的 `valid_to` 和 `Idempotency-Key`；核验员工与时间由服务端写入。同键同内容重放不再创建，同键不同内容 409；不同键重复主体标识 409。接口只建供应商档案，不生成采购单、库存、应付款或税务结论。采购验收的演示按钮仍是本地演示，不得当真实入库。

## 普通耗材验收入库（2026-11-10 后端已实现）

- `GET/POST /staff/stock-items`：读取、建立普通耗材目录；`kind` 固定为“耗材”，不开放药品、停用或修改。全局目录操作要求当前账号至少有一个满足同角色权限、门店 scope、本人已核实有效任职、营业已核验门店的授权链；POST 需 `inventory.configure` 和幂等键。
- `GET/POST /staff/warehouses`：以 `branch_id` 读取或新建本店库位。读取需 `inventory.view`，创建需 `inventory.configure`；跨店目标不得拼接其他角色的 scope，POST 需幂等键。
- `GET /staff/purchase-receipts?branch_id=&batch_id=&cursor=&limit=` 与 `GET /staff/purchase-receipts/{id}`：只显示授权门店的已过账单据；详情含明细与批次事实。不存在、未过账和无权目标统一 404。列表按 ID 游标稳定分页，可用 `batch_id` 查某批次全部来源。
- `POST /staff/purchase-receipts`：需 `inventory.receive`、目标门店权限与 `Idempotency-Key`。请求含门店、供应商、唯一内部验收单号、供货凭证号及 1–50 行；行内含行号、耗材、库位、厂家、批号、效期、数量、单位取得成本和可选持有人/追溯引用。服务端在单一 READ COMMITTED 事务内锁定并复核门店、供应商、库位、耗材、批次；写草稿、明细和等量正向流水后过账，任何失败整笔回滚。仅接受已核验、启用、有效期未过的供应商，启用库位及普通耗材、未过期合格批次。已有同批次事实不同返回 409，不能覆盖。数量最多三位小数、单位成本最多四位小数，必须为正且不溢出。
- `GET /staff/stock-balances?branch_id=&warehouse_id=&stock_item_id=&batch_id=&cursor=&limit=`：只对已过账验收流水按库位与批次汇总，游标为 `warehouse_id:batch_id`。账面余额与取得成本不代表临床可用量、应付款、已付款或税额；取得成本合计对原始乘积求和后按 HALF_UP 舍入到分。
- 同键同内容重放仅返回当前仍可见的权威单据，不再次过账；同键异内容 409。服务端内部 `request_key` 为独立 UUID，不复制客户端幂等键。草稿、作废、调拨、领用、退货、盘点和药品流程尚未开放。旧工作台演示采购与真实验收互不混合。

### 2026-11-20 本店用户档案摘要

- `POST /staff/patients` 可提交可选 `source_channel`（现场、电话、转介绍、微信小程序、其他）。未采集为 null，不推断历史来源；字段参与幂等内容指纹。微信小程序自助建档由实际入口写入“微信小程序”。
- `GET /staff/patients/{id}/summary?branch_id=` 需 `patients.lookup` 和本店有效任职，且用户已有本店预约或当前操作者有未过期的本店选择记录；其他情况 404。只返回姓名、出生日期、过敏状态、获知渠道和本店上次已完成就诊的实际医师及完成时间，不返回电话、病历正文、证件和其他门店记录。读取留审计。
