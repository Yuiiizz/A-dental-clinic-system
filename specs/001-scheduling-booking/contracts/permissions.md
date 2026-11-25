# 权限契约

认证成功不等于拥有操作权。以下为第一阶段固定权限集；后续模块不能沿用“只要是员工”判断。

| 角色 | 允许范围 | 明确禁止 |
| --- | --- | --- |
| 患者 | 公开查询；本人档案、收藏、预约、答复、取消改约、签到和申诉 | 他人记录、员工配置、临床诊断修改 |
| 前台 | 授权门店患者核验与最小信息检索、代办、取消改约、联系、签到、履约复核、签发/重签患者绑定凭证（patients.bind_issue） | 任意跨店查档、替患者默认同意、发布排班、修改政策 |
| 牙医 | 本人出诊查询和授权门店本人待接诊预约最小信息 | 替其他医生改班；本阶段不开放病历写入 |
| 经营负责人 | 授权门店档案、人员任职、服务资源、排班、签到配置及政策批准；作为唯一 organization.responsible_staff_id 的 owner 可新建门店（branches.create）并可维护全组织员工主档（staff.manage_global）；门店负责人只维护本店已授权任职 | 因负责人身份自动获得所有病历或代患者答复；新店授权只限该店启动；门店负责人改全局姓名/在岗/资格/医生服务 |
| 系统管理员 | 员工账号、经批准的角色门店授权、会话撤销 | 自动获得预约内容、病历或财务明细；自行提权；不能因账号管理权限建店或维护员工业务主档 |
| 库管(inventory)、财务(finance) | 本阶段无预约写权限，保留角色供后续模块（财务的收费结算与开票处理属后续阶段权限，本阶段不授予） | 借门店归属取得前台权限 |

## 计算方式

授权条件为：存在某一角色同时具有本操作permission和目标branch scope。不能先合并所有角色的门店再合并权限。
患者数据依当前账号patient_id过滤，不信任请求体传入患者ID；员工按记录实际branch_id过滤，不只看页面选中的门店。
同一组织内前台对没有本店既往记录的新来患者，可按精确姓名+联系电话检索最小匹配提示，经线下核验后办理本店预约；不开放跨组织全库搜索，不返回其他门店病历和联系记录。
公开门店和医生只返回白名单字段，不返回内部资格附件、账号、患者数量或收费资料。

## 员工授权与初始化

初始管理员通过仅本地可执行的一次性引导命令建立，首次登录强制改密；不用公网注册接口和固定默认密码。
初始化时记录负责人批准依据；经营负责人档案及角色另行核验建立，不由引导命令自动授予。后续授权须保存负责人批准依据，操作者不得给自己提升权限。
停用最后一个有效系统管理员前必须建立替代管理员；避免无人可维护，同时不为管理员赋临床权限。
撤销权限、停用或重置密码递增account.auth_version并撤销会话；每次请求检查版本，不等自然过期。

## 安全与审计

员工网页Cookie为HttpOnly、Secure（生产）、SameSite=Lax，所有状态变更须CSRF令牌；跨域仅允许明确来源。
小程序使用服务端签发不透明Bearer，数据库仅存摘要；不把微信session_key当本系统令牌。
注册上下文只允许建档/绑定，不能访问业务接口。员工密码采用Spring Security适用的自适应强哈希，不使用明文或可逆加密。
登录失败、凭证尝试和敏感检索限速；验证错误不暴露某患者是否已有账号。
审计记录操作、主体、对象和结果，不记录密码、code、session_key、绑定凭证明文或完整身份证件。


## 本轮实现边界（2026-10-22）

系统管理员的accounts.manage是安装实例级身份维护权限，角色的branch_ids为空；它不能绕过任何临床业务门店权限。当前不是跨不相关组织的多租户管理员方案。其他角色的门店范围严格与role_id绑定，且要求有效staff_branch。后续临床服务还须检查患者本人或牙医本人接诊范围。

角色变更接口禁止修改自己的角色列表；同值替换无副作用。dentist资格代码为dentist_practice，须已核验且有效；其他资格不能替代。角色范围变更、会话撤销和审计同事务，停用与撤权共用序列化锁保护最后管理员。首次初始化仅授予sysadmin，不自动授予经营负责人；负责人角色和任职档案须另经批准建立。员工账号创建尚未开放，待安全凭证交付及幂等实现。

患者绑定凭证签发权限 patients.bind_issue 仅默认授予 frontdesk，owner/sysadmin 不隐式获得；签发/重签走同一 role_id 门店权限链（账号角色×门店范围×有效 staff_branch 任职），并复核当前会话与强制改密。姓名+电话只用于匹配档案、不能证明本人；前台须在请求中记录结构化 verification_method 与本人到场确认，服务端仅留痕核验人/时间/门店/方法，不得在审计里放姓名电话/证件号或任意自由文本。

T024-A 起 `branches.create` 仅授 owner；实际创建还须是唯一 `organization.responsible_staff_id`、在岗、已改密。新建只给该店建立创建者 owner 角色 scope 和已核实 staff_branch，不新授 owner、不改其他门店授权；该授权变更递增 auth_version 并撤销创建者会话。列表/详情仍使用 `branch.configure` + 同一 role 的 branch scope + 有效已核实 staff_branch，sysadmin/frontdesk 不隐式可读。T024-B 的 PATCH/submit/verify/disable/reopen 均复用同一本店权限链；停用前须检查未结束排班/活动预约，状态和版本冲突零写入。T024-C 的营业区间 GET/PUT 复用同一权限链；PUT 以 branch.version 为聚合版本，范围最多31个连续自然日，今天已开始区间只读，覆盖不足按完整覆盖判断并零写入。

T025-A 起 `staff.manage_global` 仅授 owner；实际全局员工列表/建档/详情还须唯一 `organization.responsible_staff_id`、当前在岗、有效 staff_web 会话及完成强制改密。门店负责人只使用本店 `branch.configure` + 同一 role 的 branch scope + 操作者有效已核验任职，读取本店已有任职（含停用）员工最小档案；不能凭 staff_id 为无本店关系员工建立首次任职。建档不创建账号/角色/任职，资格只以 pending 录入，verified_by/at 为空。`staff.version` 用于后续全局修改；本批不开放 PATCH、资格审核、任职或医生服务写入。

T025-B1 起 `POST /staff/staff-members/{id}/credentials`、`POST .../credentials/{credential_id}/review` 与 `GET .../credentials/{credential_id}/reviews` 同样只允许唯一组织负责人（owner + `staff.manage_global`，有效会话、已改密、在岗）。资格新增只能写 pending；审核只能 pending→verified/rejected，不开放撤销、重核或证件事实修改。历史读取仅全局负责人；普通门店负责人只可读 T025-A 的受控资格摘要，不能读完整审核依据或证书原文。credential 必须属于路径员工；无权/不存在统一404，入口无业务权限403。


## 本批权限拆分（2026-10-28，T033）

- `clinical.record`：完成就诊、记录过敏核对与临床摘要；仅默认授予牙医（dentist），前台和 owner 不自动获得。
- `clinical.plan`：创建/提交/执行治疗计划；仅默认授予牙医。
- `billing.manage`：收费、收款登记、退款申请、票据处理；授予 frontdesk 和 finance，owner 不因 `staff.manage_global` 自动获得。
- `billing.refund_process`：登记线下退款完成或拒绝及处理依据；仅授予 finance。前台可申请，不可登记完成。
- 预约写入继续使用 `appointments.assist`，仅 frontdesk；不能用于临床确认。
- 业务列表/工作台读取也必须通过对应业务权限检查，不能只看角色门店范围。
- owner 的 `branch.configure`、`staff.manage_global`、`branches.create` 只适用于其管理职责，不是临床、收费或患者答复的通行证。

2026-11-20 补充：`GET /staff/staff-members/{id}/credentials/{credential_id}/facts` 使用既有 `staff.manage_global` 与唯一组织负责人校验，仅用于显式单条证件事实核对。门店负责人、前台、系统管理员和患者不因其他权限取得证书编号；成功读取留不含编号的访问审计，响应禁止缓存。原 T025-B1 段落记录当批实现范围，后续撤销及本次事实读取以现行接口为准。


## 前台新患者权限（2026-10-30 已实现后端）

- 新增 `patients.lookup`：授权门店前台对单一机构内患者做精确姓名+规范化电话检索，并执行受控选择；只返回最小候选，不返回病历/完整电话/过敏/账号。
- 新增 `patients.create`：授权门店前台建立 patient-only 档案；不自动创建账号、微信身份或绑定凭证。
- 两者默认仅授予 frontdesk；owner、sysadmin 不因管理权限自动获得。患者预约写权限仍为 `appointments.assist`，不能以 `patients.lookup/create` 替代。
- 选择既有患者必须记录实际线下核验方式和结果；姓名+电话只用于找候选，不能当作已核实本人。共用电话、同名/资料冲突必须人工核验并留结构化结果，不保存证件号码原文。
- 生成短时 `patient_selection` 凭证并绑定操作者、患者、目标预约门店；预约提交强制携带，成功同事务核销，失败不核销。同键成功重放先核验权限和预约可见性，返回原预约，不要求旧 token 有效。
- V015 已新增权限迁移、精确检索、patient-only 建档和选择凭证后端；管理端页面尚未实现。
# 店内沟通权限（2026-11-09）

`messages.exchange` 默认仅授予 `frontdesk` 与 `dentist`。本人须有同一角色的目标门店 scope 和有效已核实任职，对方也须拥有相反角色的同店授权、有效任职及已激活员工账号。owner/sysadmin 无业务旁路。消息只能读取、发送；不能替代任何正式预约或诊疗操作。

## 供应商档案权限（2026-11-09）

`suppliers.manage` 默认授予 `inventory` 和 `owner`，但实际请求还需已改密的在岗员工身份、该角色的至少一个营业且已核验门店 scope、本人在该门店的有效已核实任职，并要求安装实例恰有一个营业中的经营主体。`frontdesk`、`dentist`、`finance`、`sysadmin` 不因其他业务权限获得供应商维护权。此权限只允许查看及人工核验后建档，不授予采购、入库或财务记账。

## 普通耗材库存权限（2026-11-10）

`inventory.view` 和 `inventory.configure` 默认授予 `inventory`、`owner`；`inventory.receive` 仅默认授予 `inventory`。每个门店操作要求**同一角色**同时有对应 permission 与目标门店 scope，且本人有该店启用、已核实的任职；门店及经营主体必须营业、门店已核验。全局耗材目录也至少需要一条符合条件的门店授权链。owner 可配置和读取，不可验收入库；frontdesk、dentist、finance、sysadmin 无自动旁路。库存读取只含授权门店已过账事实，草稿及他店单据不对外暴露。
