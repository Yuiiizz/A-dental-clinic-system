# 微信登录（T016，2026-10-22）

POST /api/v1/auth/wechat 只接受契约中的 `code`，服务端用自身配置的 AppID/Secret 调用微信官方 code2session 换取 openid；绝不接受客户端自报 openid/app_id/account_id。公开入口（permitAll），小程序按 auth:false 调用，不因请求携带真实员工 Cookie 而跳过微信校验。

## 换身份（数据库事务外）

固定官方 HTTPS 地址 `https://api.weixin.qq.com/sns/jscode2session`，不信任客户端指定 URL。经 `WechatClientConfig` 提供的 `HttpClient` 保证证书校验、连接超时 5 秒、请求超时 5 秒、`Redirect.NEVER` 不跟随任意重定向。换身份在数据库事务外完成：`WechatLoginService`（编排层，非事务）先调用 `WechatCodeResolver.resolve`，再跨代理调用独立的 `@Transactional WechatBindingService` 落地，避免同类自调用使事务失效，也绝不持数据库行锁等待微信网络。

真实凭证仅经环境变量 `WECHAT_APP_ID`/`WECHAT_APP_SECRET` 注入，无默认值。两者任一缺失时返回 503 `WECHAT_NOT_CONFIGURED`，明确报配置缺失，**不悄悄降级 mock**；不向用户索取密钥粘贴到聊天，不写密钥到源码/测试/日志。微信业务错误、超时、非 JSON、缺字段统一返回 502 `WECHAT_EXCHANGE_FAILED`（可识别安全错误），不回传原始正文、URL 查询参数、code 或 session_key；敏感 DTO 的 toString 均为 `[redacted]`，不记录异常链。

code 是一次性凭证，不盲目自动重试外部交换；结果未知时提示重新取得 code（微信 code 有效期约 5 分钟，errcode 40029/40163 等同样返回安全失败）。

## 已绑定 / 未绑定二选一

- 已绑定 `(app_id, open_id)`：核验患者身份与账号启用，复用 `SessionService.issue` 签发 `patient_miniapp` 不透明 Bearer（患者会话，2 小时绝对有效期）；**绝不签发员工 Cookie 或员工会话**。停用/异常绑定（账号不存在、patient_id 为空、未启用）返回 403 `FORBIDDEN`，不当作未绑定另发注册凭证。
- 未绑定：复用 `identity_challenge` 表，`purpose=wechat_registration`，10 分钟到期，32 随机字节（256 位熵），数据库只存 SHA256 摘要（`token_hash` 不等于返回的原令牌）。`app_id`/`open_id` 必须来自服务端交换结果；不创建患者/账号。

响应严格二选一：session 分支仅 `session_token`/`expires_at`，registration 分支仅 `registration_token`/`registration_expires_at`；`Cache-Control: no-store`，不返回 openid、session_key 或 secret。

## 隔离与限流

- 注册上下文不能作为业务 Bearer 访问 `/auth/me` 或员工接口；患者 Bearer 不能充当员工 Cookie；员工 Cookie 不能替代微信身份。此接口不放开其他业务路由（其余路由仍 `denyAll`）。
- 独立 `WechatLoginThrottle`：按直接连接 IP 固定窗口限流 30 次/15 分钟，与员工登录限流独立，不共享 IP 预算（反向代理下需按可信代理配置，与员工限流同款取舍）。

## local mock

mock 仅 **`local` profile 且 `app.mock.wechat-enabled=true`** 时启用：`LocalWechatMock` Bean 同时挂 `@Profile("local")` 与开关条件；`EnvironmentSafetyInitializer` 在非 production 环境也会拒绝「开启 mock 但无 local profile」，绝不静默切回真实适配器（production 与 local/test 混用、production 开 mock 仍拒绝）。`application-local.yml` 默认开启、`application-test.yml` 默认关闭（测试替身须显式开启）。`LocalWechatMock` 只接受服务端预定义的合成 code，不接受任意客户端身份：

| code | 结果 |
| --- | --- |
| `mock-code-bound` | 已绑定合成 openid（对应集成测试 fixture 的 wechat_identity）→ 会话分支 |
| `mock-code-unbound` | 未绑定合成 openid → 注册分支 |
| `mock-code-expired` / `mock-code-reused` / 其他任意 code | 与真实适配器同款 502 `WECHAT_EXCHANGE_FAILED`（固定失败分支，仅用于展示错误响应形态，不披露具体细节） |

这是本地演示替身，**不是真实微信已接通**。**两个成功 code 是可重复使用的固定测试夹具，不是一次性消费**；`mock-code-reused`/`mock-code-expired` 只是固定失败分支，**不模拟「先成功后再次消费失败」的生命周期**。因此不能据此声称已验证真实 code 的一次性消费/过期。真实微信联调继续标记为未验证，T017 仍须真正实现注册上下文的一次性消费。

## 未验证事项

真实微信联调尚未验证（未配置真实 AppID/Secret，也未连接真实微信）；微信 code 的一次性消费/过期未经验证（local mock 的成功 code 可重复使用，仅以固定失败分支展示错误形态）。未实现 T017 患者建档绑定、前端与预约。

## 测试证据

- 真实适配器 + 可控 transport 替身单测 `WechatIdentityClientTest`：成功换取、微信业务错误、超时、畸形响应、缺 openid、缺配置不回退 mock；严格解析（畸形 errcode 类型、非对象顶层、错误 openid 类型/超长、超长配置 appId、errcode=0 正常放行）。
- 身份脱敏单测 `WechatResolvedIdentityTest`：toString 不含 appId/openid。
- Bean 选择单测 `WechatCodeResolverBeanSelectionTest`：local+true 选 mock、local/test+false 选真实适配器。
- 启动守卫单测 `EnvironmentSafetyInitializerTest`：非 local 开 mock 拒绝、默认 profile=local 语义、production+local 开 mock 拒绝。
- 编排层单测 `WechatLoginServiceTest`：网络失败不产生半条注册上下文或会话。
- 真实 PostgreSQL + HTTP `WechatLoginIntegrationTest`：已绑定患者会话、未绑定注册上下文且不建账号、token_hash 不等于原令牌、到期正确、两分支互斥、停用绑定拒绝、注册凭证当 Bearer 被拒、跨端隔离、未知字段拒绝、限流有效。
