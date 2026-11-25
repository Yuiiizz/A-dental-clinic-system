# 管理端登录闭环（T021-A 定向修复，2026-10-23）

本批只实现管理端（admin-web）的登录、强制改密、会话恢复、退出和最小登录后首页；账号/角色管理页面、小程序、排班预约均未实现。**T021 整项保持未勾选，等待 Codex 复核。**

前端沿用现有 Vue 3 + Element Plus + Pinia + Vue Router + `src/services/http.ts`，未新装框架、未另造 HTTP 层。项目既有目录为 `views/`（非 `pages/`），页面实际位置见下表。

## 实际文件位置

| 职责 | 文件 |
| --- | --- |
| 会话 store（登录/改密/恢复/退出、统一认证互斥、epoch 护栏） | `admin-web/src/stores/session.ts` |
| 会话 DTO 类型、角色/门店展示助手、CSRF storage 键 | `admin-web/src/services/session.ts` |
| 新密码策略（12 码点 + 72 UTF-8 字节） | `admin-web/src/services/password-policy.ts` |
| 中文安全错误文案映射 | `admin-web/src/services/auth-error.ts` |
| 路由与守卫 | `admin-web/src/router/index.ts` |
| 登录页（含身份核验失败重试） | `admin-web/src/views/LoginView.vue` |
| 强制改密页（含身份核验失败重试） | `admin-web/src/views/ChangePasswordView.vue` |
| 登录后首页（替换原占位页） | `admin-web/src/views/HomeView.vue` |
| 本地开发代理（同源 `/api` → 后端 8080） | `admin-web/vite.config.ts` |

现有 `src/services/http.ts` 与其 20 项测试**未改动**。

## 对接的真实接口（未猜字段）

先读后端 DTO 后对接，字段与后端一致：

- `POST /api/v1/auth/staff-login`，请求 `{ login_name, password }`；响应 `{ data: { must_change_password, csrf_token } }` + `Set-Cookie: A_CLINIC_SESSION`。前端 `csrf: false`。
- `GET /api/v1/auth/me`；响应 `{ data: { account_id, name, identity_type, roles:[{ role_code, branch_ids }], must_change_password } }`。
- `POST /api/v1/auth/password-change`，请求 `{ current_password, new_password }`；响应 `{ data: { must_change_password, csrf_token } }` + 新 Cookie。必须注入 `X-CSRF-Token`。
- `POST /api/v1/auth/logout`；`204` 无正文 + 清除 Cookie。必须注入 `X-CSRF-Token`。

身份（姓名/角色/门店）一律以 `/auth/me` 为准；登录响应不返回姓名角色，前端不自行拼装。

## 认证变更互斥与代次

- 登录、改密、退出、身份重试、`bootstrap` 共用 **同一个认证变更闸门**（`authBusy`）。同一标签页内禁止并行认证变更；改密提交期间退出按钮禁用，退出在途时改密请求返回 `executed=false`，不会并发发出两组 `Set-Cookie` 响应。
- 页面不再只看各自 `loginSubmitting` / `changeSubmitting` / `logoutSubmitting`，统一以 `authSubmitting` 判断互斥与禁用。
- 所有认证操作返回显式结果：
  - `executed=false`：因互斥/前置条件未执行，页面不得宣称成功、不得跳转；
  - `superseded=true`：执行期间认证代次已变，结果作废，不得写状态、清会话或跳页。
  不再出现 `return undefined` 被页面误判为成功的情况。
- **epoch 代次护栏**覆盖登录、改密、退出的成功/失败分支：只有代次未变时才写 CSRF、更新身份状态或 `clearSession`；旧请求迟到的成功、401、网络失败都不能覆盖新会话。
- 页面跳转同样受代次结果约束：只有 `executed=true && superseded=false` 且当前 store 状态已确认时，才导航到首页/改密页/登录页。

## 登录与身份确认

- 用户名 `login_name` 去首尾空白后提交；密码不持久化、不写日志、展示层不 `toString`。
- 登录请求成功、拿到 CSRF 后，**必须**再 `GET /auth/me` 成功核验：
  - 只有 `identity_type === 'staff'` 且 `Me` 结构完整有效，才进入 `ready` / `mustChangePassword`；
  - `/auth/me` 网络失败/5xx/403/响应结构不完整：进入 `unverified`，保留已取得的 CSRF，**不进入 ready**；
  - `/auth/me` 返回 `patient`：不进入管理首页，提示“当前登录身份不是员工账号”，且不提供会误导的重试入口；
  - `/auth/me` 返回 401：本端清理并回登录。
- 登录页对 `unverified` 显示“身份确认未完成，请检查网络后重试”，并提供“重试身份确认”按钮；重试只重新 `GET /auth/me`，**不会重新提交密码或盲目重新发登录**。重试成功后再按状态跳转。
- 失败清空密码并显示中文提示；空输入不发请求；提交期间按钮禁用，store 与页面各有一层防重复。
- 登录页卸载时清空密码字段。

## 强制改密

- 路由守卫将 `mustChangePassword` 账号限制在改密页，直接访问 `/` 也会被重定向，不能靠 URL 进入首页。
- 新密码即时校验：**至少 12 个 Unicode 码点、最多 72 个 UTF-8 字节、两次一致**。字节数用 `new TextEncoder().encode(value).length` 计算，与后端口径一致；不再用 `maxlength=72`（UTF-16 单元）冒充字节校验。服务端仍为最终依据。
- 改密成功后用响应中的新 `csrf_token` 替换旧值（内存 + sessionStorage），再 `GET /auth/me` 确认身份。
- **改密成功但 `/auth/me` 失败**：密码已更新，状态为 `unverified`，页面显示“密码已更新，但身份确认未完成”，提供重试；确认成功后才进入首页。不会自动重发改密请求。
- **改密 401 语义区分**：401 后用受代次保护的 `GET /auth/me` 复核——
  - `me` 有效 → `password-rejected`，提示“当前密码不正确”，保留会话与 CSRF；
  - `me` 401 → `session-invalid`，清理本端并回登录；
  - `me` 网络/5xx → `unconfirmed`，保留 CSRF，显示“身份确认未完成”，不擅自登出。
- 提交失败/完成、`executed=false`、`superseded=true` 以及离开页面都会清空当前密码和新密码字段。
- 改密页同时提供“退出登录”，满足“强制改密账号只显示改密/退出”。

## 会话恢复（刷新）

- 启动/刷新路由守卫先 `bootstrap()`：从 `sessionStorage` 恢复 CSRF 后**必须** `GET /auth/me` 核实；sessionStorage 不被当作授权。
- 共享进行中的 `bootstrap` Promise，多个路由守卫不会并行重复恢复。
- 只有 `/auth/me` 的 **401** 清理本端身份与 CSRF 并回登录页；**403 不当作登出**。
- 非 401（网络/5xx/结构异常）不再静默落到 `guest`，而是进入可见的 `unverified`，保留 CSRF 并回登录页显示“身份确认未完成”与重试按钮；登录页是 public，重定向不会形成循环。
- `unverified` 访问受保护页会被送回登录页；访问登录页放行。

## 退出

- 只有服务端返回成功（204 或 401=会话已失效）才清空本端并宣称退出；退出结果仍受代次保护，旧退出晚到不得清理新会话。
- 网络失败显示“退出未确认，请重试”，**不自动重试**、不清空会话；CSRF 缺失或 403 提示“登录状态异常，请重新登录”。
- `executed=false` / `superseded=true` 时不跳转、不宣称退出。
- 退出按钮提交期间禁用并防重复提交。

## 首页

- 仅展示 `/auth/me` 返回的姓名、账号编号、角色与门店范围，以及退出入口。
- 无业务统计、无伪造数据、无前端自助角色切换；角色与门店范围由服务器返回，前端只做展示映射（未知角色代码原样显示）。
- 无门店资料查询依赖。
- 首页退出同样使用显式结果：未执行或迟到结果不跳转。

## 安全约束落地

- HttpOnly Cookie `A_CLINIC_SESSION` 只由浏览器携带；前端**不读 `document.cookie`、不发患者 `Authorization: Bearer`、不保存员工会话令牌**。
- CSRF 只存内存 + `sessionStorage`（键 `a-clinic.session.csrf`，仅用于刷新恢复）；缺失时要求重新登录，不发明“获取 CSRF”接口。
- 登录 `csrf:false`；改密/退出注入 `X-CSRF-Token`。
- 密码不持久化、不进入 store 之外的持久层、不输出日志；错误提示不回显原始 HTML、令牌或密码；`ApiError` 文案固定为安全中文。
- 不以 403 自动登出；只有 `/auth/me` 的 401 与退出/改密经 `/auth/me` 确认的会话失效才清理本端。

## 路由守卫

`resolveRedirect(status, to)` 为纯函数，便于单测：

| 状态 | 访问 | 结果 |
| --- | --- | --- |
| checking | 受保护页 / 登录页 | → 登录页 / 放行 |
| guest | 受保护页 | → 登录页 |
| guest | 登录页 | 放行 |
| unverified | 受保护页 | → 登录页 |
| unverified | 登录页 | 放行（展示“身份确认未完成”与重试） |
| ready | 受保护页 | 放行 |
| ready | 改密页 / 登录页 | → 首页 |
| mustChangePassword | 除改密页外 | → 改密页 |
| mustChangePassword | 改密页 | 放行 |

守卫仅在 `checking` 时执行一次 `bootstrap()`；`bootstrap()` 后状态为 guest/unverified/ready/mustChangePassword，登录页为 public，上述决策收敛，无重定向循环。

## 本地运行

Node 使用 `D:\DevTools\node-v24.22.1-win-x64`（临时 PATH，不改全局）：

```bash
export PATH="/d/DevTools/node-v24.22.1-win-x64:$PATH"
cd D:/GitHub/A-dental-clinic-system/admin-web
npm run dev        # 开发服务器，默认 5173
npm run typecheck  # vue-tsc --noEmit
npm run test:unit  # vitest run
npm run build      # vue-tsc --noEmit && vite build
```

- 后端默认 `SERVER_PORT=8080`。`vite.config.ts` 已加仅 dev 生效的代理 `/api → http://localhost:8080`，保持同源，浏览器才会携带 HttpOnly 会话 Cookie；后端未配置 CORS，前端不跨源直连。
- 代理目标可用 `VITE_DEV_PROXY_TARGET` 覆盖；API 前缀可用 `VITE_API_BASE_URL` 覆盖（默认 `/api/v1`）。
- local profile Cookie 为 `Secure`；现代浏览器允许 `http://localhost` 上的 Secure Cookie。若改用非 localhost 的主机名/局域网 IP，需要 HTTPS 或调整本地后端配置（本批未改后端）。

## 测试证据（mock HTTP，真实执行）

本轮使用 Node `v24.22.1`、npm `11.19.1`，在 `admin-web` 实际执行：

- `npm run typecheck`：**通过，0 错误**。
- `npm run test:unit`：**7 个文件、94 项通过，0 失败**。
  - `src/services/http.test.ts`（20）：原有 HTTP 客户端测试，未改动。
  - `src/services/password-policy` 对应 `src/__tests__/password-policy.test.ts`（5）：12 码点下限；ASCII 72/73 字节；中文 24/25 字（72/75 字节）；emoji 18/19 个（72/76 字节）；两次一致。
  - `src/stores/__tests__/session-store.test.ts`（26）：原 12 项保留并加强，新增改密/退出互斥、退出在途改密未执行、旧退出成功/401 不覆盖新代次、旧改密成功/401 不覆盖新代次、登录后 `/auth/me` 网络失败不 ready 且可重试、bootstrap 并行只一次且失败可见可恢复、旧 bootstrap 失败作废、patient 不进管理端、改密 401 的密码错/会话失效/未确认三条路径、改密成功但核验失败不 ready。
  - `src/__tests__/LoginView.test.ts`（11）：登录成功/强制改密跳转、失败提示与清空密码、网络失败、空输入、提交禁用、身份核验失败可见且可重试、刷新恢复失败可重试、非员工身份不冒充成功、busy 未执行不跳页、superseded 旧结果不跳页。
  - `src/__tests__/ChangePasswordView.test.ts`（14）：码点不足、两次不一致、成功清空并跳首页、密码错误、会话失效重登、`unconfirmed` 只重试核验不冒充改密、改密成功但核验失败重试后进首页、busy/superseded 不误跳、改密与退出互斥、退出成功/网络失败/busy、CSRF 缺失重登。
  - `src/__tests__/HomeView.test.ts`（7）：身份/角色/门店展示、无角色与无伪造统计、退出成功才跳转、退出网络失败、busy/superseded 不跳转、身份信息缺失重登入口。
  - `src/__tests__/router-guard.test.ts`（11）：上表全部分支，含 unverified 与 checking。
- `npm run build`：**通过**。Vite 提示 Element Plus 全量注册导致单包 >500 kB，与本批功能无关，未做代码分割。

测试使用可控 deferred Promise 构造在途请求，不靠 `setTimeout` 延时碰运气；既有断言未削弱。

## 未验证事项 / 已知限制

- **未做真实浏览器联调、未跑 Playwright E2E**：仓库无 Playwright 配置/用例，禁止创建真实凭证；未擅自安装浏览器。页面行为由 jsdom 单测与构建验证，真实浏览器 + 真实后端的联调未验证。
- **未连接真实后端/真实账号**，未验证真实 Cookie 下发与跨刷新行为；所有 HTTP 均 mock。
- 后端未改动，因此未重复跑后端全量测试。
- 未验证多标签页并发；本批只保证同一标签页内禁止并行认证变更。Set-Cookie 覆盖风险在多标签页下仍需后续真实联调评估。
- 未来业务接口若需统一的 401 登出处理，应在 store 层接入 `onUnauthorized`（本批仅覆盖认证接口的 401 语义，未擅自扩大）。
- 未实现账号/角色管理、小程序、排班预约。
- 未改 Word/图片/constitution；未提交或推送 Git；未部署；未连接用户原数据库；未使用真实密钥。

## 任务状态

**T021 保持未勾选**，等待 Codex 复核。
