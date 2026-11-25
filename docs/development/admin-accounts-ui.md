# 管理端账号与角色门店页面（T021-B/T021-C，2026-10-23）

本批实现管理端账号列表、创建待激活账号、停用账号，并在 T021-C 补齐**目标账号授权读取、并发版本保护和角色门店编辑闭环**；T021-C 定向修复补齐**失败重读保留草稿与详情请求/编辑代次隔离**。未实现小程序、排班预约、账号编辑/重新启用。**T021 整项保持未勾选**，待小程序与完整验收。

## 实际文件位置

| 职责 | 文件 |
| --- | --- |
| 账号 API 封装、字段校验、安全错误文案、幂等键 | `admin-web/src/services/account-admin.ts` |
| 账号页面状态（列表分页、缓存代次、防重、提交中标志） | `admin-web/src/stores/account-admin.ts` |
| 账号与角色门店页面 | `admin-web/src/views/AdminAccountsView.vue` |
| 路由（`/admin/accounts`） | `admin-web/src/router/index.ts` |
| 首页管理员入口（仅 `isSysadmin` 展示） | `admin-web/src/views/HomeView.vue`、`admin-web/src/stores/session.ts` |
| 服务/页面/store 测试 | `admin-web/src/__tests__/account-admin.test.ts`、`admin-web/src/__tests__/AdminAccountsView.test.ts`、`admin-web/src/stores/__tests__/account-admin-store.test.ts` |

新增文件没有引入依赖；`src/services/http.ts` 及原有 20 项测试未改。

## 对接的真实接口（未猜字段）

- `GET /api/v1/staff/accounts`：顶层 `{ items, next_cursor }`；列表项 `account_id`、`login_name`、`enabled`、`state`（`pending_activation` / `active` / `disabled`）。
- `POST /api/v1/staff/accounts`：请求 `{ staff_id, login_name }`，`Idempotency-Key` 必填；响应 `data` 含 `account_id`、`login_name`、`staff_id`、`enabled`、`must_change_password`、`state`；创建为待激活账号，无密码。
- `GET /api/v1/staff/accounts/{id}/role-scopes`：管理员读取目标员工账号的 `state`、`version`（account.auth_version，并发令牌，不是登录凭证）与按 role 分组的 `roles[{role_code, branch_ids, approval_basis}]`；患者账号/不可见账号 404；单事务一致读取。
- `PUT /api/v1/staff/accounts/{id}/role-scopes`：请求 `{ expected_version, roles: [{ role_code, branch_ids, approval_basis }] }`；`expected_version` 必填且必须等于读取到的版本；全量替换，空列表仅撤权；`sysadmin` 的 `branch_ids` 必须为空；服务端逐门店核验有效任职；版本不匹配 409 且不写角色/会话/审计；成功返回权威当前 version。
- `POST /api/v1/staff/accounts/{id}/disable`：请求 `{ reason }`（1-300 字符）；服务端撤销会话、写审计；待激活账号返回 422 `NOT_ACTIVATED`。

## 页面流程与约束

### 账号列表

- 进入页面先 `GET /auth/me` 重新取本人身份，再取账号列表；刷新按钮重新取身份和列表；返回页面重新挂载时同样重取，不以本地角色作为授权依据。
- 列表状态直接展示服务端 `state`：`pending_activation` 显示“待激活”，绝不显示为“启用”；`disabled` 显示“停用”。
- 分页使用 `next_cursor`；加载中禁用重复请求。

### 创建待激活账号

- 因缺少员工选择接口，使用明确标注的员工编号输入，由服务端核验在岗与有效任职；不伪造员工列表。
- 前端校验员工编号为正整数、登录名符合后端 `[a-z0-9][a-z0-9._-]{2,99}` 规则；服务端仍是最终依据。
- 成功后明确提示“待激活；无默认密码、无网页激活，按现有本地激活流程处理”，并按服务端返回状态展示（重放可能是 active/disabled）。
- 幂等键在页面内按“员工编号+规范化登录名”稳定生成：超时/网络失败/409 时保留输入和原键，用户手动重试复用；内容变化才生成新键；不自动重发。
- `ACCOUNT_EXISTS`、`IDEMPOTENCY_CONFLICT`、`VALIDATION_FAILED` 等错误保留输入并显示安全中文提示。

### 角色与门店范围

- 选中账号后先 `GET /role-scopes`；读取成功前禁用编辑。读取失败显示错误和“重试读取当前授权”，不开放空白覆盖。
- 草稿存在性、所属账号和会话代次独立维护，不从角色详情缓存是否存在推断。409/超时后的重读即使连续失败，草稿也保留；只有显式“放弃草稿并重新读取”、切换账号/会话或保存成功才清除。
- 详情读取返回显式 `executed/stale/requestId`；页面另用编辑代次校验，目标 ID 相同也不够。A→B→A、同目标并行重读的旧结果不会回填、不会显示旧错误或清掉新确认。
- 读取成功后回填最新原始值，保留原草稿并展示逐角色差异；重新确认后才能保存。`sysadmin` 门店输入禁用并强制 `branch_ids: []`；撤销全部角色需单独确认。
- 保存携带读取到的 `expected_version`。409 时保留草稿、标记“授权已被其他操作更新”，保存禁用；用户必须重新读取并比较，不能自动覆盖新版本。
- 超时/网络失败不自动重发旧全量覆盖：先 GET 当前授权核对，草稿保留；若核对成功，提示比较后再决定是否保存。保存成功后的重读也绑定同一编辑/会话上下文，切换目标或会话不显示旧成功提示。
- 保存成功后重新 GET 当前授权，以服务端返回为准；旧会话撤销和最后管理员/自提权由服务端判定，页面显示真实失败。

### 缓存隔离与身份代次

- 会话 store 以只读 `sessionRevision` 暴露登录/改密/清理代次；账号 store 的每个列表/详情/变更请求都捕获该 revision、当前 `account_id`、状态和管理员标志。
- 退出、切换账号、同账号重新登录、失去管理员权限或状态离开 `ready` 时，账号 store 立即清空列表、`next_cursor`、选中账号、角色详情和缓存代次；页面监听缓存代次清空角色草稿、停用面板和创建表单。
- 列表请求带 requestId/queryId：并行 reset 只有最新结果落地；旧 reset 的成功/401 不会影响新会话；分页结果在 reset 后作废，不能串入新查询。角色详情也带 requestId：切换目标后旧详情丢弃。
- 身份未确认（`unverified`）时页面隐藏账号列表、创建表单、角色详情和操作入口，显示“身份确认未完成”与重试按钮；不会把网络核验失败显示成“登录已失效”。

### 停用

- 仅对 `active` 账号显示停用入口；待激活账号明确提示“不支持停用，请按本地激活流程处理”，后端 422 `NOT_ACTIVATED` 也有对应安全文案。
- 停用必须填写原因并勾选确认；取消不提交。提交前固定目标账号 id/name 和操作上下文；等待期间切换账号或关闭面板，旧结果只提示原目标，不关闭/清空新选择的面板与草稿。
- 成功后提示已停用并撤销会话，重新取列表；身份代次变化时丢弃 UI 结果，不声称能撤销服务端可能已提交的操作。
- 最后管理员保护、自提权、404、403 等由服务端判定；页面只显示安全文案，不隐藏失败。

## 认证、CSRF 与错误处理

- 账号 store 使用会话 store 的 CSRF 提供器与同源 Cookie；不读取 `document.cookie`、不发 `Authorization: Bearer`、不保存第二份会话或令牌。
- `create`/`setRoles`/`disable` 在 store 层防重：重复调用返回 `executed=false`（busy），不以置 busy 后静默执行；变更前核验 `ready`、已确认员工态、管理员角色及不在认证变更中。
- 401 时调用会话 store 的 `fetchMe()`（受代次保护）复核；`unverified` 显示身份确认未完成和重试，不显示“登录已失效”；仅身份确认失效才回登录页，不直接清空会话。
- 403 不调用 `fetchMe`、不清空会话；列表 403 会清空账号缓存并停止展示旧数据，页面显示无权限/最后管理员/自提权等明确提示。
- 错误文案按 `ApiError.code/status` 映射，不回显服务端原始 `message`、请求体或账号敏感信息。

## 缺失接口与已知限制

- 无员工选择接口：使用员工编号输入，后续可替换为选择器。
- 无门店选择接口：使用逗号分隔的门店编号输入，后续可替换为选择器。
- 无账号重新启用、登录名修改、角色筛选/搜索；本批未实现。
- 真实浏览器、真实后端 Cookie/CSRF、分页、缓存隔离与角色撤销联调未做；前端 HTTP 仍为 mock。
- 角色编辑已开放，但仍需真实浏览器/真实后端联调确认；T021 整体不因本批勾选。

## 实际验证

前端 Node `v24.22.1` / npm `11.19.1`，工作目录 `admin-web`：

- `npm run typecheck` → **通过，0 错误**。
- `npm run test:unit` → **10 个文件、160 项通过，0 失败**：
  - 原有 `http.test.ts` 20 项、`password-policy` 5 项、会话 store 27 项、LoginView 11 项、ChangePasswordView 14 项、HomeView 9 项、router-guard 13 项均保留/通过。
  - `account-admin.test.ts` 16 项：列表字段、三状态创建、角色详情严格解析、角色分组序列化、sysadmin 空范围、空列表撤权、expected_version、非法角色/门店、停用、幂等键、安全错误文案。
  - `account-admin-store.test.ts` 12 项：create/setRoles/disable 防重、未确认/非管理员、旧列表/旧 401、并行 reset、分页交叉、身份变 unverified、角色详情迟到 stale/requestId、同目标并行最新结果、未选目标 not-selected、退出清缓存/详情。
  - `AdminAccountsView.test.ts` 33 项：详情回填、差异展示、expected_version 保存、409 草稿保留与重读、409 重读失败再成功草稿仍在、超时核对连续失败保留草稿、A→B→A 旧详情丢弃、同目标重选/并行重读、撤销全部确认、读取失败禁用/重试、保存后重读切换目标/会话不显示旧成功、停用上下文、403/401、未确认隐藏、刷新分页等。
- `npm run build` → **通过**（既有 Element Plus 大包提示）。

后端本轮未改，未重复后端全量；上一轮隔离 PostgreSQL **214 项通过、0 失败/错误/跳过**（待 Codex 独立确认）。Spectral 上一轮对变更契约 **0 错误、20 条既有警告**；OpenAPI 现有 **74 个 operationId**。

## 未验证 / 边界

- **未做真实浏览器 + 真实后端 Cookie/CSRF 联调**；前端请求为 mock，未创建真实账号或会话。
- 后端只改账号授权读取/版本校验及安全路由，未扩展其他业务；未安装新依赖。
- 未改 Word/图片/constitution；未提交或推送 Git；未部署；未连接用户原数据库。
- **T021 保持未勾选**：小程序侧页面、完整双端验收和真实联调仍未完成。
