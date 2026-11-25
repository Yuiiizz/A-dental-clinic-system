# 患者小程序登录、本人建档与旧档绑定（T021-D，2026-10-23）

本批只实现已有身份接口的小程序页面与内存编排；未实现预约、`/me/profile` 编辑、真实微信联调，未改后端。**T021 整项保持未勾选**。

## 页面与文件

| 职责 | 文件 |
| --- | --- |
| 身份 API 严格解析与公开/Bearer 请求 | `miniprogram/services/auth-api.ts` |
| 内存会话（无 storage） | `miniprogram/services/session.ts` |
| `wx.login` 薄封装 | `miniprogram/services/wx-login.ts` |
| 登录/建档/绑定/重新核对编排 | `miniprogram/services/auth-flow.ts` |
| 页面生命周期上下文 | `miniprogram/services/page-context.ts` |
| 首页登录入口 | `miniprogram/pages/index/*` |
| 本人建档表单 | `miniprogram/pages/register/*` |
| 旧档绑定表单 | `miniprogram/pages/bind/*` |
| 个人身份页 | `miniprogram/pages/profile/*` |
| 测试 | `__tests__/http.test.ts`、`auth-api.test.ts`、`auth-flow.test.ts`、`pages.test.ts` |

## 接口对接

- `POST /auth/wechat`（`auth:false`，仅 `{code}`）：严格二选一。
  - `kind=session` → 保存内存患者 Bearer，再请求 `/auth/me` 核验患者身份。
  - `kind=registration` → 仅在内存保存短期 `registration_token`/到期时间。
  - 混合字段/缺字段/未知 kind 按 `INVALID_RESPONSE` 拒绝；不信任客户端 openid，不发送员工 Cookie/CSRF。
- `POST /auth/patient-register`（`auth:false`）：提交 `registration_token`、姓名、电话、可选出生日期、`allergy_status`、`allergy_notes`。`allergy_status` 为「有/不确定」时说明必填；成功后保存患者会话并再次 `/auth/me` 核验。
- `POST /auth/patient-bind`（`auth:false`）：只提交 `registration_token` + 前台交付的 `binding_token`。**不提供手机号认领/自动合档**；页面明确说明同手机号不等于本人、需前台核验。
- `GET /auth/me`：患者 Bearer；`identity_type=staff` 明确拒绝患者端；401 只清理由当前请求上下文确认的会话，网络/5xx/畸形响应显示“身份未确认”并允许重试，不误登出、不展示假成功。
- 未实现/未猜测 `/me/profile`、预约、退出登录；患者 Bearer 的 logout 语义未在本批验证，故不提供退出按钮。

## 会话与注册上下文

- 令牌只存内存，不写 `wx.setStorage*`；小程序重启后重新走微信登录。
- 注册上下文只在 `AuthFlow` 内存保存，不写日志、不持久化、不缓存明文。
- 新身份核对开始即清除旧会话/旧注册上下文，session 与 registration 分支互斥；员工身份拒绝后不保留注册入口或旧身份缓存。
- HTTP 客户端不再在 401 回调里直接清会话；由 `AuthFlow` 按请求/会话代次处理，迟到 401 不会清掉后来登录的会话，公开 `auth:false` 凭证的 401 不会被当作另一 Bearer 失效。
- 同一时刻只允许一次认证变更；每个操作记录所属页面/生命周期代次。页面 `onHide`/`onUnload` 只能取消自己的当前操作，重复取消/旧页面卸载不影响新页面请求；`onShow` 新代次激活并恢复 busy。
- 页面所有 async 回调使用捕获的 context，不只看 active 布尔；`wx.login` 返回后、发 `/auth/wechat` 前再次检查操作仍有效，取消期间返回的旧 code 不继续交换。
- 注册/绑定成功后清空注册上下文；发出请求后即进入一次性凭据在途状态，页面不能再提交同一凭证。超时、5xx、代理失败或畸形成功响应按“结果不明”处理并废弃旧上下文，不宣称失败、不自动重发。
- 一次性凭据的在途/取消状态绑定所属 operation；已被替代的旧 register/bind 迟到成功或失败只能返回 stale，不能清除新登录获得的 registration，也不能影响新操作的在途互斥/恢复。
- 结果不明时自动重新 `wx.login` 获取新 code，再走 `/auth/wechat` 核对。已绑定则进入个人页；仍未绑定则回到本人建档/前台凭证核验；核对失败保留中性提示和可用重试入口。
- 已有 Bearer 但 `/auth/me` 未确认时优先只重试 me，不重新提交注册/绑定；无 Bearer 时才重新微信登录。403 保留 Bearer、显示访问被拒/联系前台，不伪装登录失效；401 才按当前上下文清理。401/403/409/422/429/5xx 只映射安全中文，不回显原始响应、code、token、手机号或身份标识。
- 个人页记录已展示身份对应的会话 revision；隐藏期间会话变化后再次显示立即清除旧姓名并重新核验，相同会话不重复请求。

## 页面与视觉

- 原生 WXML/WXSS，白底浅蓝绿色；中文提示。
- 首页：微信登录、身份状态、本人建档/前台凭证入口、说明“手机号不能认领旧档”。
- 建档页：姓名、电话、出生日期选择、过敏申报（未申报/无已知/有/不确定）、说明；条件必填。
- 绑定页：只输入绑定凭证；解释前台核验。
- 个人页：只展示 `/auth/me` 返回的姓名、患者身份和确认状态；不展示预约/诊疗/收费数据，不给医疗建议。
- 无硬编码 mock 成功 code；`project.config.json` 仍为 `touristappid`，真实 AppID 由使用方本地配置。无 AppID 时测试使用注入替身，不宣称真实微信联调。

## 配置

`miniprogram/services/config.ts` 的 `API_BASE_URL` 仍为空。真实运行前必须填 HTTPS 合法域名（开发者工具本地调试可显式填开发地址并关闭域名校验，生产必须开启校验）。本批没有新增依赖、没有密钥/AppSecret、没有真实数据库连接。

## 实际验证

Node `v24.22.1`，工作目录 `patient-miniapp`：

- `npm run typecheck` → **通过，0 错误**。
- `npm run test:unit` → **4 个文件、59 项通过，0 失败**：
  - 既有 `http.test.ts` 17 项全部保留/通过（Bearer、auth:false、401/403、超时、分页、畸形响应等）。
  - `auth-api.test.ts` 5 项：两分支严格解析、混合字段拒绝、注册/绑定只发契约字段、`/auth/me` 患者/员工解析。
  - `auth-flow.test.ts` 21 项：两分支互斥、注册条件校验、绑定仅双凭证、超时/401/5xx/畸形响应、网络未确认不登出、员工拒绝、重复提交 busy、页面卸载/取消 stale、迟到401不清新会话、真实 createHttpClient 401 接线、操作归属取消、旧 register/bind 迟到不清新注册、wxLogin 后取消不发交换、403 保留 Bearer、重新登录恢复/未绑定。
  - `pages.test.ts` 16 项：页面登录/注册/绑定/个人页、hide→show 旧结果失效、连续核对失败保留重试、超时自动重新核对、无 Bearer 重试走登录、有 Bearer 只重试 me、403 提示、隐藏期间会话变化清旧姓名、无注册上下文禁用表单。

## 微信开发者工具导入 / 真实联调未做

- 未在微信开发者工具导入运行；未配置真实 AppID、AppSecret、合法 HTTPS 域名。
- 未做真实 `wx.login`/code2session、真机、扫码、真实微信账号联调；测试均为 mock `wx.login`/`wx.request`/页面生命周期。
- 未验证真实微信 code 一次性/过期、真实网络超时、真实开发者工具编译。
- 后端未改、未重跑后端全量；本批不宣称真实微信已接通。

## 未完成范围

预约、`/me/profile` 编辑、退出登录、真实微信联调、真实设备测试未完成。**T021 保持未勾选**，等待 Codex 复核本批页面与测试。
