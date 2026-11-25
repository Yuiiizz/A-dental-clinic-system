# 会话校验基础（T012，2026-10-22）

SessionService由服务端验证身份后内部调用issue，不能接受客户端账号ID直接登录。令牌及CSRF令牌均由SecureRandom产生32字节随机值，数据库只保存SHA256摘要；敏感返回对象和内部主体的toString不输出凭证。当前开发默认绝对有效期员工8小时、患者2小时，为安全设计参数，不是调研结论；后续配置化不能放宽身份校验。

每次请求关联账号校验enabled、auth_version、会话有效期及revoked_at。staff_web只能使用A_CLINIC_SESSION Cookie，patient_miniapp只能使用Bearer；互换、重复或混用凭证拒绝。员工非GET/HEAD/OPTIONS操作必须提供与会话匹配的X-CSRF-Token，摘要采用恒定时间比较。默认框架CSRF已由上述会话级实现替代，不能理解为取消CSRF保护。安全过滤器层401/403采用统一JSON和request_id，不依赖ControllerAdvice。

关闭框架默认密码登录、HTTP Basic、默认注销和服务端HTTP Session。实际业务会话只存在auth_session；logout撤销当前行，员工端同时清除Cookie。密码强制修改标记仅允许身份查看、改密和退出。数据库停用或变更授权版本后旧凭证下一次请求失效。

小程序换身份（T016）复用 issue 签发 patient_miniapp 不透明 Bearer：换身份在事务外、落地在独立事务代理，已绑定核验患者身份与启用后签发患者会话，绝不签发员工 Cookie；未绑定只写 identity_challenge 注册上下文，不发会话。注册上下文不能作业务 Bearer，患者 Bearer 不能作员工 Cookie，员工 Cookie 不能替代微信身份。

角色与门店授权尚未实现，所有尚未接线的业务路由默认拒绝，不能把CLIENT_staff_web当作业务权限。当前只实现退出接口及会话服务；员工密码登录、初始管理员、小程序换身份、auth/me内容、权限修改仍属后续任务，不可宣传为完整登录系统。

新增6项真实HTTP/PostgreSQL测试：摘要存储、跨端隔离、账号停用/授权版本失效、过期/撤销、CSRF退出、混用凭证、默认拒绝和安全错误。合计41项通过，0失败/错误/跳过；临时实例及本地测试HTTP服务均已停止。
