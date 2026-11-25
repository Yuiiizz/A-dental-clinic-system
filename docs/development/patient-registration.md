# 患者本人自助建档（T017-A，2026-10-23）

POST /api/v1/auth/patient-register 只接受 `PatientSelfRegisterRequest`（`registration_token` 必填、`name`/`phone`/`allergy_status` 必填、`birth_date`/`allergy_notes` 可选）。请求 `additionalProperties=false` 由全局 `FAIL_ON_UNKNOWN_PROPERTIES` 兜底，客户端传入 `openid`/`app_id`/`account_id`/`patient_id`/角色/启用状态一律 422 `UNKNOWN_FIELD` 拒绝。响应为 `PatientSessionResult`（`session_token` + `patient_id` + `expires_at`），`Cache-Control: no-store`。

微信身份只来自服务端签发的有效 `wechat_registration` 上下文，绝不接受客户端自报身份。原始注册凭证是 32 随机字节（256 位熵）的 Base64url（43 字符），数据库只存 SHA256 摘要；不入库、不入审计、不在 DTO toString/异常/响应回显。上下文快照 `Challenge` 的默认 toString 已固定脱敏，不输出 `appId`/`openId`（这是防御性收口，不声称既有日志已泄露）。

## 编排与单事务原子落地

`PatientRegistrationService` 是 `@Transactional(Propagation.NEVER)` 编排层——明确禁止加入任何外层事务，只做入口限流、摘要查找、判活、出生日期解析与纯字段校验，本身不写库。真正的原子落地在独立的 `@Transactional PatientRegistrationExecutor`（跨代理调用，避免自调用使事务失效）。NEVER 是为了避免未来出现「外层事务持锁 → 进入编排层 → recordFailure 再 REQUIRES_NEW」的自锁风险。单事务内顺序：

1. 对微信身份 `(app_id, open_id)` 取事务级 advisory 锁（键由 `wechat-identity\0app\0open` 的 SHA256 前缀派生）。
2. 对规范化手机号取事务级 advisory 锁（键由 `patient-phone\0phone` 的 SHA256 前缀派生），串行化同手机号不同微信的并发建档。
3. 持锁后对该上下文行 `FOR UPDATE` 权威复查：用途、未消费、未过期、`failed_attempts < 5`，任一不满足返回 401 `REGISTRATION_TOKEN_INVALID`。
4. 持锁后重查 `wechat_identity` 绑定：已绑定（含停用账号）返回 409 `WECHAT_IDENTITY_ALREADY_BOUND`，绝不重绑或重新启用。
5. 持手机号锁后权威重查手机号疑似重复：已存在返回 409 `PATIENT_DUPLICATE_SUSPECTED`，杜绝编排层先查后写的 TOCTOU。
6. 插入 patient → account（`login_name`/`password_hash` 为空、`enabled=true`、`must_change_password=false`）→ wechat_identity。
7. 单次消费上下文（`consumed_at` 仅当仍为 NULL 时写 1 行，否则拒绝）。
8. 复用 `SessionService.issue(accountId, PATIENT)` 签发 `patient_miniapp` 患者 Bearer（2 小时），**不签发员工 Cookie、不授员工角色**。
9. 系统审计（`audit.system`，最小摘要，不记录 openid/token/姓名/电话）。

任何一步失败（含会话签发）整体回滚：不留孤儿档案，也不出现「已消费却无账号」的上下文。数据库唯一约束 `uq_wechat_identity_business(app_id, open_id)` 作为最终兜底；本方法不捕获唯一冲突后在已失败事务内继续查询。手机号疑似重复的权威检查在事务内（第 5 步），失败计数由编排层在事务回滚后独立提交，执行器绝不调用 recordFailure，避免持锁再 REQUIRES_NEW 自锁。

## 锁序与并发

锁序固定：**先微信身份锁、后手机号锁、再 challenge 行锁**。所有并发路径按同一顺序取锁，不存在相反顺序死锁。

- 同一上下文并发：同 token 必同 openid，被微信身份 advisory 锁串行化；第二个请求等首个提交后 `FOR UPDATE` 复查发现 `consumed_at` 非空 → 401，只成功一次。
- 不同有效上下文但同一 `(app_id, open_id)` 并发：同样被同一微信身份锁串行化，后到者持锁重查 `wechat_identity` 发现已绑定 → 409，最终只有一份 patient/account/wechat_identity。
- 不同微信、不同上下文、同一手机号并发：微信身份锁不冲突，但手机号 advisory 锁串行化；后到者持手机号锁重查发现已有档案 → 409 `PATIENT_DUPLICATE_SUSPECTED` 转人工，最终同手机号只建一份、无孤儿记录。

家庭共用手机号是真实场景，因此不给 `phone` 加唯一约束，也不据手机号合并或登录。手机号锁只保证**本注册入口**并发时同手机号只建一份；未来前台建档等其他写入口需各自在事务内按同一规范化手机号取锁后重查（本实现不虚称覆盖尚未实现的前台写入口，该协调点待前台签发落地时补齐）。

## 失败计数

`failed_attempts` 只在「可识别有效上下文的身份/业务校验失败」时累加，由 `PatientRegistrationChallengeService.recordFailure` 在独立 `REQUIRES_NEW` 事务内以单条 `UPDATE` 原子累加。UPDATE 用权威条件约束，而非只检查 `consumed_at`：

```sql
UPDATE identity_challenge SET failed_attempts = failed_attempts + 1
WHERE challenge_id = ? AND purpose = 'wechat_registration' AND consumed_at IS NULL
  AND expires_at > ? AND failed_attempts < 5
```

条件保证：错用途/已消费/已过期/已达上限的上下文不再累加；并发达上限最多到 5，不会越过。由非事务编排层（Propagation.NEVER）在抛出安全 API 错误前（纯字段校验）或执行器事务回滚后（手机号重复）调用，与主注册事务不共享连接，杜绝「外层持 challenge 行锁再 REQUIRES_NEW 自锁」。

| 场景 | 结果 | 是否计数 |
| --- | --- | --- |
| 过敏状态为「有/不确定」但说明为空 | 422 `VALIDATION_FAILED` | 计数 |
| 手机号疑似重复（事务内权威检查） | 409 `PATIENT_DUPLICATE_SUSPECTED` | 计数 |
| 未知凭证 | 401 `REGISTRATION_TOKEN_INVALID` + 入口 IP 限流 | 不计数 |
| 已消费 / 已过期 / 错用途 / 已达 5 次 | 401 `REGISTRATION_TOKEN_INVALID` | 不计数 |
| 出生日期格式错误、bean 校验错误 | 422 `VALIDATION_FAILED` | 不计数 |
| 系统/数据库故障 | 500 `INTERNAL_ERROR`（整体回滚） | 不计数 |

## 入口限流

`PatientRegisterThrottle` 是注册入口的前置有界 IP 预算（30 次/IP/15 分钟）：在 `challenges.find` **之前**检查，超限即返回 429，保证超限请求不触发数据库探测/执行器。该预算独立于 T016 微信登录限流、员工登录限流，互不共享。可识别有效上下文的失败由 `identity_challenge.failed_attempts` 独立计数（见上节），与入口 IP 预算分离。反向代理下需按可信代理配置，与员工/微信限流同款取舍。

## 手机号不是身份凭据

手机号不用于自动登录或合并旧档。疑似重复按既有规范转人工：返回 409 `PATIENT_DUPLICATE_SUSPECTED`，提示「该联系方式已存在档案，请联系前台核实」，不泄漏既有档案的任何资料（姓名/编号等）。

## 重复消费与恢复

注册/绑定是单次消费幂等例外，不套用需要已登录账号的 `IdempotencyService`。重复消费返回 401（凭证已消费），不重放会话令牌；成功响应丢失时通过重新微信登录恢复，不重新创建患者。

## 未完成范围（本批不实现）

旧档绑定（`/auth/patient-bind`）、前台签发一次性绑定凭证、前端与其他业务均不实现；T017 保持未勾选。前台签发接口目前只有 `verification_basis`，缺少明确 `branch_id`/办理记录引用，且「凭证明文仅显示一次、仅存摘要」与「同键重放返回凭证」的恢复语义需在旧档绑定实施前单独厘清，留待下一批决策。

## 测试证据

- 编排层单测 `PatientRegistrationServiceTest`：系统故障不计数、业务校验失败计一次、执行器手机号重复在回滚后计数、入口超限不触发 find、Challenge toString 脱敏。
- 真实隔离 PostgreSQL + HTTP `PatientRegistrationIntegrationTest`：正常创建并访问 `/auth/me`、错用途/过期/已消费/5 次/未知令牌/过敏说明/未知字段/非法过敏状态/非法出生日期拒绝、同上下文并发仅一次成功、同微信不同上下文并发仅一份记录、**不同微信同手机号并发仅一份记录且无孤儿**、已绑定/停用不重建档、手机号一致不自动绑定不泄漏、失败计数持久化与并发累加、达 5 次拒绝、注入会话失败原子回滚且不误罚、**编排层拒绝外层事务且不留写入**、**recordFailure 权威条件（过期/已消费/并发封顶 5）**、患者会话不能访问员工接口、注册凭证不能当业务 Bearer、**注册入口限流独立于微信预算**。
