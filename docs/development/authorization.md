# 角色与门店授权

## 已实现接口

- GET /auth/me：本人姓名、字符串账号编号、身份类型、强制改密标记、按角色分别列出的门店编号；不返回密码摘要或会话令牌。
- GET /staff/accounts：系统管理员查看员工账号最小分页（account_id、login_name、enabled、state）；state按是否已设密码区分 pending_activation/active/disabled；不包含患者账号、密码摘要或密码哈希。
- POST /staff/accounts：系统管理员（已完成强制改密）幂等创建待激活账号；目标须在岗且有有效已核验任职；不自动授予角色，无默认密码。响应state在创建时=pending_activation，同键重放按当前行重算（active/disabled/pending_activation）。
- GET /staff/accounts/{id}/role-scopes：账号管理员读取目标员工账号的当前 state、按 role 分组的 branch_ids/approval_basis 与并发版本 version（account.auth_version，不是登录凭证）；患者账号/不可见账号统一 404。REPEATABLE READ 单事务读取，保证 version 与角色来自同一快照；不返回密码摘要、会话或患者信息。
- PUT /staff/accounts/{id}/role-scopes：全量设置角色与范围，逐角色保存负责人批准依据；请求必须携带 expected_version，且等于读取到的 account.auth_version。版本不匹配 409 VERSION_CONFLICT，锁内先检查，不写角色/会话/审计。相同规范化内容且版本匹配时不递增 auth_version、不重复撤销会话或写审计；内容变化才递增 auth_version、撤销旧会话并写审计。
- POST /staff/accounts/{id}/disable：停用员工账号、递增授权版本、撤销全部会话；重复停用无额外副作用。待激活账号（尚未设密码）拒绝停用（422 NOT_ACTIVATED），不把停用当取消首次激活资格。
- POST /auth/wechat：微信换身份公开入口（详见 wechat-login.md）。已绑定返回 patient_miniapp 会话，未绑定返回短期注册上下文，绝不签发员工 Cookie；注册上下文不能作业务 Bearer，患者 Bearer 不能作员工 Cookie，员工 Cookie 不能替代微信身份。

首次激活由服务器本地非Web交互命令完成（见 staff-login.md），不在HTTP接口内。

## 权限边界

accounts.manage是本安装实例的身份维护权限，仅sysadmin持有；不赋予预约、诊疗、收费或门店业务读取权。sysadmin的branch_ids必须为空，不将此空列表解释为业务全门店。此实现不是多个互不相关诊所共享的SaaS租户管理员模型；如以后要按组织隔离系统管理员，必须补充账号组织边界及迁移。

门店权限通过account_role→role_permission与同一个role_id的role_branch_scope联查，同时检查员工在该店任职有效。不能合并角色后拼接权限和门店。公开查询、患者本人范围和牙医仅本人接诊限制需由相应业务服务再按实际记录校验；通过门店校验不代表能读取全店病历。

V003迁移仅建立当前固定操作权限：accounts.manage、branch.configure、appointments.assist、appointments.own.read。目录中的业务权限不会自动开放HTTP接口；后续模块逐接口接入并测试。库管、财务本阶段没有预约权限。

## 修改保护

管理员禁止修改自己的角色列表（包括自提权）；可在有其他有效管理员时停用自己。角色授予要求在岗员工；门店必须存在有效且已核验的staff_branch。临床dentist角色另要求staff_type为牙医、有效的已核验dentist_practice资格。该资格代码明确指执业资格，不接受任意培训证书替代；资格表单模块须使用同一代码。

角色更新与停用共用事务级锁734192014，随后按账号ID顺序锁定操作者/目标并重新确认操作者会话与权限，防止撤权后的在途请求仍执行。角色更新在锁内先核对 expected_version=account.auth_version；不匹配返回409且不写角色、撤销会话或审计。auth_version也会因改密/停用递增，因此这些变化后旧版本需要重新读取，属于保守冲突语义；不新增冗余版本列。检查其他启用、在岗、已设密码的管理员，避免并发停用或撤权导致无人维护。普通账号无角色修改入口；患者账号不由员工管理接口停用或授予员工角色。

角色范围删除/重建、auth_version递增、旧会话撤销、前后授权信息审计同事务提交；同值更新且版本匹配时不递增版本、不撤销会话、不写审计。空roles仅撤销所有角色，不授予权限；审计保留原批准依据。批准依据是经线下确认的记录，不等同于系统自动验证负责人确实批准，管理端需明确填写真实依据。

## 尚待完成

管理端账号/角色门店页面已接读取、版本冲突和全量保存闭环（见 admin-accounts-ui.md）；任职/资格变更与未完预约联动、正式权限矩阵的其余操作、云端验证仍未完成。T013/T014/T015暂不整项验收。
