# 患者小程序本地真实预约闭环（local mock + 隔离库）

> 批次日期：2026-11-15。范围仅是**本机开发者工具 + local profile 微信 mock + 一次性隔离 PostgreSQL + 教学虚拟数据**；不是真实微信、真机或云端验收。

## 前置

- Node.js `v24.22.1`（小程序依赖已安装）。
- JDK 21、Maven Wrapper；PostgreSQL 18 工具目录（本机 `D:\Postgres\bin`）。
- 后端 jar 先构建：

```powershell
cd D:\GitHub\A-dental-clinic-system\backend
.\mvnw.cmd -q -DskipTests package
```

## 1. 启动隔离后端和数据库

```powershell
cd D:\GitHub\A-dental-clinic-system
python backend\scripts\run_local_miniapp_e2e.py `
  --pg-bin D:\Postgres\bin `
  --jar backend\target\a-dental-clinic-backend-0.1.0-SNAPSHOT.jar
```

脚本会用随机空闲端口创建 PostgreSQL，只在 `127.0.0.1` 监听，执行 Flyway 迁移并导入 `deploy/teacher-demo-seed.sql`。每次输出类似：

```text
Local classroom API is ready: http://127.0.0.1:<随机端口>/api/v1
Isolated run directory: ...\backend\target\local-miniapp-e2e-<随机 id>
```

**不要把随机端口写入源码或固定配置。** 关闭终端前先记下本次输出的 API 地址。

## 2. 烟测（可选，但推荐）

在另一个终端：

```powershell
python backend\scripts\smoke_local_miniapp_e2e.py --url http://127.0.0.1:<本次端口>/api/v1
```

烟测只接受 `http://127.0.0.1:<port>/api/v1`，会完成：

- mock 微信交换；
- 教学虚拟患者建档；
- 预约写入和 `/me/booking` 读回；
- 个人资料、记录、费用、协调方案读取；
- 重复 mock 登录。

不要把它说成真实微信登录。

## 3. 开发者工具临时设置

1. 用微信开发者工具打开 `patient-miniapp`，不要上传、预览到真机或发布。
2. 在模拟器 DevTools 的 Storage 中设置本次一次性地址；也可在开发者工具控制台执行：
   ```js
   wx.setStorageSync('a-clinic.apiBaseUrl', 'http://127.0.0.1:<本次端口>/api/v1')
   wx.setStorageSync('a-clinic.mockWxCode', 'mock-code-unbound')
   wx.setStorageSync('a-clinic.patient.demo-enabled', '0')
   ```
3. `wx-login.ts` 只在 **platform=devtools 且 API 为 loopback** 时读取 mock code；真机、体验、正式和远程 API 都会忽略 Storage 覆盖，走真实 `wx.login`。
4. 点击“普通编译”（必要时重新编译，使 `API_BASE_URL` 重新读取 Storage）。模拟器应显示后台门店/预约；本地演示关闭。

## 4. 页面闭环

1. 首页点“登录/建档” → 点“微信登录”。
2. 个人信息页应显示 `教学虚拟小程序联调`，不是浏览器/本地伪造姓名。
3. 进入“真实预约”。已有 2026-11-16 09:00 预约时，患者候选应**不再显示 09:00**；默认应变成 09:30 或之后。
4. 选择无冲突候选，点“确认真实预约”；页面显示预约编号，并在“我的真实预约”中读回同一条记录。
5. 冲突路径：页面加载候选后，用同一患者通过 HTTP 先占用该候选，再在页面提交同样时段；页面应显示 `该时段已不可预约`，并刷新候选，不把冲突显示成成功。
6. 任意新请求发生前、`await` 后都复核身份和页面操作代次；旧身份不能在页面写回消息、候诊号或刷新结果。

本次实际观察到（端口为当次随机值，不是固定配置）：

- 登录姓名：`教学虚拟小程序联调`。
- 已有预约：`2026-11-16 09:00`，云栖门店。
- 修复后默认候选：`09:30`（滨江门店，示例·叶澄）。
- 页面首次成功：预约 `950`，09:30；页面和 `/me/booking` 均读回。
- 之后页面仍显示 10:00 候选时，先用同一患者创建 10:00 冲突 `951`，再点提交：HTTP 409，业务码 **`SLOT_UNAVAILABLE`**，页面提示 `该时段已不可预约`，候选刷新到 10:30；未新增页面预约。
- 本轮未出现 WXML/TS 编译错误；只有工具自身 warning/一次重载 `ERR_ABORTED`。

## 5. 结束后清理

先删除本次临时 Storage 键（不要让下一次误连已关闭端口）：

```js
wx.removeStorageSync('a-clinic.apiBaseUrl')
wx.removeStorageSync('a-clinic.mockWxCode')
wx.removeStorageSync('a-clinic.patient.demo-enabled')
```

然后在启动脚本终端按 `Ctrl+C`。预期：

- Java 后端停止；
- 独立 PostgreSQL `pg_ctl stop -m fast` 停止；
- 本次随机 API 端口和 PG 端口都不再监听；
- `data/postmaster.pid` 不残留；若残留，检查进程和 run 目录日志，不要直接删除目录掩盖问题。

本次实际停止后确认 `54019/54020` 均无监听，`postmaster.pid` 不存在。

> 自动化后台作业无法可靠向子进程投递 Windows 控制台 Ctrl+C；本轮尝试通过终止后端进程触发 `finally`，API 和独立 PG 端口均关闭、pid 文件不存在。交互式终端按 Ctrl+C 的路径仍待人工复验。启动脚本现在会先检查 `pg_ctl status` 再停止，并在停止后再次检查；psql 种子导入不再静默吞掉输出。本轮未复现此前偶发的 Windows `'cat' 不是内部或外部命令`，因此未改动无关命令；若再次出现，应单独定位其调用者，不能忽略。

## 验证结果

- 后端隔离 PostgreSQL 全套：**31 个测试类、405 项通过**；其中 `NewBusinessIntegrationTest` 95 项、`SchemaMigrationTest` 13 项。
- 小程序 Node 24：`typecheck` 通过，**16 个测试文件、119 项通过**。
- 脚本：`python -m py_compile` 通过；`smoke_local_miniapp_e2e.py` 实际通过（mock 登录、建档、预约写入/读回、资料/记录/费用/协调读取）。
- 页面：开发者工具普通编译成功；实际点按登录、候选 09:30、提交预约 950 并读回；stale 候选冲突返回 409 `SLOT_UNAVAILABLE`，页面提示并刷新到 10:30。
- OpenAPI/接口字段未变更，未改契约；旧验收文档已更新对应测试数和旧口径，具体见 `docs/development/patient-miniapp-local-acceptance.md`。

## 边界

- `app.mock.wechat-enabled=true` 只适用于 local profile；生产守卫关闭 mock。
- 隔离库包含教学虚拟数据，不是真实患者库；没有真实微信 AppSecret、真实支付、发票开具或云端部署。
- `previewSlots` 仍是只读候选，不预占；提交时保留最终事务校验，因此过期候选仍可能返回 409。
- 同一患者跨医生/跨门店的重叠时段不作为患者候选；已取消、已改约不占号，待确认按契约不占号。改约的源预约在未完成前仍是活动预约，因此其原时段也可能被患者候选排除，属于保守行为。
- 管理端和其他页面不在本批次闭环范围。

## 截图

- `docs/screenshots/wechat-local-booking-before.png`
- `docs/screenshots/wechat-local-booking-success.png`
- `docs/screenshots/wechat-local-booking-conflict.png`
