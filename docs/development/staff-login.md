# 员工登录与首次管理员

## 已实现

POST /api/v1/auth/staff-login 接收 login_name、password；员工须在岗且账号启用。登录名去首尾空白并转小写，仅允许3—100位英文字母、数字、点、横线、下划线，首位为字母或数字。不存在账号、错误密码和停用账号均返回401，不披露账号是否存在。

密码使用 BCrypt（cost=12），新密码至少12个Unicode码点、最多72个UTF-8字节，不去空白、不截断。此为本轮明确的实现取值，不是临床业务规则。待后续引入其他摘要算法时必须增加兼容验证，不能使旧账号无法登录。

登录仅通过 HttpOnly、SameSite=Lax Cookie 设置员工会话，响应只含是否强制改密和CSRF令牌，Cache-Control=no-store。默认Secure=true；本地纯HTTP调试必须显式配置SESSION_COOKIE_SECURE=false，production启动守卫禁止此设置。过期Cookie不阻止重新登录；登录拒绝患者Bearer及浏览器跨站请求。生产需HTTPS和同源代理，不开放宽泛CORS。

POST /api/v1/auth/password-change 要求旧密码、合法CSRF和有效员工会话。同事务更新密码、清除强制改密标记、递增授权版本、撤销全部旧会话、签发新会话并写不含秘密的审计记录。失败回滚。强制改密期间仅允许会话信息、改密和登出路径；角色/门店接口仍关闭，后续T014实现。

登录与改密有进程内固定15分钟限流窗口：每个账号键10次、每个直接连接IP60次；计数包括成功请求。内存上限10000个键，满时拒绝新增。重启会清空，不能代替云端网关限流；反向代理连接IP可能共用，部署时须按可信代理方案配置限流，不能盲信客户端X-Forwarded-For。

## 首次管理员：本地交互命令

仅空的员工账号集合可初始化，不因管理员停用而重新开放。通过PostgreSQL事务级锁防止同时创建；创建员工、账号、唯一sysadmin角色和系统审计在同一事务完成。不会授予牙医/负责人角色或门店临床范围。已有系统的密码恢复与账号管理属于后续管理流程，不删除账号来绕过初始化检查。

先构建backend可执行jar。在服务器交互终端配置DB_URL、DB_USERNAME、DB_PASSWORD，再执行（将占位路径换成构建出的jar）：

```text
java -jar <backend.jar> --spring.profiles.active=production --spring.main.web-application-type=none --app.bootstrap.enabled=true
```

命令交互询问负责人批准依据、员工姓名、登录名和两次密码。密码不回显、不经命令行参数或日志传递；必须有真实终端，管道/无终端执行拒绝。成功后进程关闭。正常启动时不传bootstrap开关。界面首次登录后必须改密。

## 员工账号首次激活：本地交互命令

POST /staff/accounts 只创建待激活账号（无密码、不可登录、must_change_password=true），不生成默认密码、不开放公网激活接口。首次激活由服务器管理员在交互终端执行（将占位路径换成构建出的jar，并配置好 DB_URL/DB_USERNAME/DB_PASSWORD）：

```text
java -jar <backend.jar> --spring.profiles.active=production --spring.main.web-application-type=none --app.activation.enabled=true
```

命令交互询问待激活员工登录名、批准依据，并两次隐藏输入初始密码。只能激活尚无password_hash的待激活账号；已激活后停用的账号、离岗/未核验员工、患者账号均被拒绝，不能借此重置密码或重新启用。启动前守卫在数据库/服务器初始化前校验activation=true必须为非Web模式，且bootstrap与activation不得同时启用；命令内真实终端检查是第二层保护。成功后在启用账号、设置密码摘要、保留强制改密标记与审计同事务提交；不授予任何角色。密码不回显、不经命令行参数或日志或审计传递。这是单实例/本地演示的受控流程，需管理员协助，不是完整自助激活体系。

## 验证边界

自动测试使用隔离合成数据库及真实HTTP验证登录/CSRF/改密会话撤销、密码限制和初始化服务。交互终端人工操作、微信真机、前端登录页面、云端反向代理和完整账号权限尚未验收。T014负责角色和门店范围、禁止自提权及保留最后一个管理员的账号管理操作。
