# 开发环境说明（T001）

记录本机实际工具版本、安装路径与构建命令。**实际版本以本文件为准**；与 plan.md 声明不一致处如实标注，未覆盖全局工具。

**记录日期**：2026-10-21（Phase 1 Setup，本轮未连接数据库、未执行迁移、未部署）。

## 一、实际工具版本与路径

| 工具 | 实际版本 | 路径 | 说明 |
| --- | --- | --- | --- |
| JDK | 21.0.6 (LTS, Oracle) | `D:\Java\jdk-21` | 符合 plan「Java21」 |
| Node.js | **v25.6.1** | 全局（未改） | 本机现有全局版本，按约束不覆盖 |
| Node.js（第七轮独立） | **v24.22.1**（npm 11.19.1） | `D:\DevTools\node-v24.22.1-win-x64` | 官方来源 + SHA256 校验；未改系统/用户 PATH |
| npm | 11.9.0（全局）/ 11.19.1（Node24） | 随各自 Node | — |
| PostgreSQL | 18.6 (`psql`) | 全局 | 符合 plan「PostgreSQL18」 |
| Maven | **3.9.16** | `D:\DevTools\apache-maven-3.9.16` | 本机原先未装 Maven，本轮下载到 D 盘，**未加入 PATH、未覆盖全局** |
| Git | 2.45.1.windows.1 | 全局 | — |
| Python | 3.13.9（PyYAML 6.0.3） | 全局 | 仅用于本轮 OpenAPI 结构校验，非项目运行时依赖 |

## 二、版本偏离与处置

1. **Node v25.6.1（非 plan 的 24.x）**：属本机现有全局环境，按约束**不覆盖**。三个工程的脚本未依赖 24 特有 API，`npm install`/`vite`/`vue-tsc`/`vitest`/`tsc` 均在本版本通过（见「构建结果」）。若后续 CI 需严格对齐 24.x，用容器/`nvm` 固定，不改本机。
2. **Maven 原先未装**：plan 只要求「Maven Wrapper」，未指定本地 Maven 版本。本轮下载 Maven 3.9.16 到 `D:\DevTools` 用于生成 Wrapper 并完成构建；工程内已生成 Maven Wrapper（`backend/mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`，wrapper 3.3.4 → Maven 3.9.16），后续无需全局 Maven。
3. **Node24 已对齐（第七轮）**：独立安装 Node v24.22.1 到 `D:\DevTools\node-v24.22.1-win-x64`，官方 nodejs.org 分发包、SHA256 与官方 `SHASUMS256.txt` 一致；不卸载 Node25、不修改系统/用户全局 PATH，经当前进程临时 PATH 前置运行，并确认 npm 子进程实际使用 Node24（npm ci 输出 `node=v24.22.1 npm=11.19.1`）。T001 的「Node24.x 且 ≥24.12」据此落实。

## 三、项目本地依赖（不污染全局）

- **Maven**：Wrapper 固定 3.9.16，首次 `./mvnw` 自动下载到 `%USERPROFILE%\.m2\wrapper\dists\`。
- **Maven 镜像**：`D:\DevTools\maven-settings-aliyun.xml`（阿里云镜像）仅用于本轮加速构建，**未提交仓库**；仓库内 `.mvn/wrapper` 仍指向官方 `repo.maven.apache.org`。
- **Node 依赖**：`admin-web/node_modules/`、`patient-miniapp/node_modules/` 为项目本地，无全局安装。
- **Spring Boot**：`backend/pom.xml` 父 POM `spring-boot-starter-parent` 4.1.1 的 BOM 锁定 MVC/Security/Validation/JDBC/Flyway/PostgreSQL 版本。
- **OpenAPI 校验器**：`D:\DevTools\openapi-validator`（项目独立目录、锁定 `@stoplight/spectral-cli@6.17.0`，未全局安装）；`.spectral.yaml` 继承 `spectral:oas` 规则集。

## 四、构建命令（本轮已执行）

```bash
# 后端：编译并打包（-DskipTests 因本轮未连数据库/未跑迁移）
cd backend
./mvnw -DskipTests package
# 产物：backend/target/a-dental-clinic-backend-0.1.0-SNAPSHOT.jar

# 管理端：类型检查 + 生产构建
cd admin-web
npm install
npm run typecheck
npm run build
npm run test:unit   # Vitest 单测（无 e2e 浏览器下载）

# 小程序：TypeScript 类型检查（未在微信开发者工具运行）
cd patient-miniapp
npm install
npm run typecheck

# OpenAPI 契约结构校验（本机 PyYAML）
python -I D:\DevTools\openapi-validate.py specs/001-scheduling-booking/contracts/openapi.yaml

# OpenAPI 官方规则集校验（spectral，锁定 6.17.0，项目独立目录）
cd D:\DevTools\openapi-validator
npx spectral lint --ruleset .spectral.yaml -f json D:\GitHub\A-dental-clinic-system\specs\001-scheduling-booking\contracts\openapi.yaml

# 前端在 Node24 独立运行时下验证（临时 PATH 前置，不改全局）
export PATH="/d/DevTools/node-v24.22.1-win-x64:$PATH"
cd admin-web && npm ci && npm run typecheck && npm run build && npm run test:unit
cd ../patient-miniapp && npm ci && npm run typecheck
```

## 五、本轮未做（如实记录）

- **未连接数据库**：`application.yml` 的 `DB_URL` 无默认连通值；未执行任何 Flyway 迁移或 SQL。
- **未运行后端测试**：`spring-boot-starter-test` 已声明，但集成测试（需真实 PostgreSQL）属 T007+，本轮用 `-DskipTests` 只做编译+打包。
- **未在微信开发者工具导入/编译**：小程序仅通过 `tsc --noEmit` 类型检查；`project.config.json` 使用 `touristappid` 占位、`libVersion: 3.8.7` 占位（导入时按开发者工具实际基础库调整）。
- **spectral 已安装并执行**（`D:\DevTools\openapi-validator`，锁定 6.17.0，未全局）：对 `openapi.yaml` 做官方规则集 `spectral:oas` 机器校验，**0 错误、21 条风格警告**（见 `contracts/openapi-review.md` 第六节）。校验通过不代表接口已实现。

## 六、密钥与环境变量

密钥一律经环境变量注入，模板见 `deploy/env.example`（无真实值）。三个 profile：`application-local.yml`（本地，允许 mock 身份/种子）、`application-test.yml`（独立测试库，允许 mock/fixture）、`application-production.yml`（默认关闭模拟和种子；EnvironmentSafetyInitializer 在初始化阶段检查最终配置，拒绝生产开启模拟/种子、混用 local/test 或关闭安全 Cookie）。日志密钥脱敏见 `backend/src/main/resources/logback-spring.xml`（本轮未运行时验证）。

## Codex 复核补充

- T001 尚未完成：当前 Node25 未满足计划指定的 Node24；后续用 D 盘独立运行时验证，不覆盖全局版本。
- 测试数据源改用 TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD，避免继承开发库的 DB_*。这不等于已验证数据库隔离，T007 仍需检查实际目标库并中止错误配置。
- T005 尚未完成：现有日志正则不能可靠遮蔽 JSON 密钥、Bearer 完整值或异常栈，不可视为通用脱敏保证。后续须采用白名单日志且不记录凭证/患者数据，并验证日志策略。
- 本轮新增生产配置保护的无数据库单元测试，独立于 T007 数据库集成测试。工程打包成功不代表后端已启动或业务可用。

## 七、Round 7：Node24 对齐与 spectral 校验（2026-10-22）

### Node24 安装（可复制 PowerShell 命令）

```powershell
$ver = "v24.22.1"
# 1) 官方分发包 + 校验和
Invoke-WebRequest "https://nodejs.org/dist/$ver/node-$ver-win-x64.zip" -OutFile "$env:TEMP\node.zip"
Invoke-WebRequest "https://nodejs.org/dist/$ver/SHASUMS256.txt" -OutFile "$env:TEMP\SHASUMS256.txt"
# 2) 校验 SHA256（应等于 d2046d28c2c3dcd211ac5145c0bf30e2047ef9dfb306693c8c6e2eccd8b95fa5）
(Get-FileHash "$env:TEMP\node.zip" -Algorithm SHA256).Hash.ToLower()
Select-String -Path "$env:TEMP\SHASUMS256.txt" -Pattern "node-$ver-win-x64.zip"
# 3) 解压到独立目录（不改系统/用户 PATH）
Expand-Archive "$env:TEMP\node.zip" -DestinationPath "D:\DevTools"
# 4) 当前进程临时 PATH 运行（新开 PowerShell 需重新设置）
$env:Path = "D:\DevTools\node-$ver-win-x64;$env:Path"
node -v  # v24.22.1
npm -v   # 11.19.1
```

### 前端 Node24 验证结果

| 工程 | npm ci | typecheck | build | test:unit |
| --- | --- | --- | --- | --- |
| admin-web | 通过（现有 lockfile，3 依赖漏洞未处置） | 通过 | 通过（vite 6.4.5，1620 模块） | 通过（1 项 HomeView） |
| patient-miniapp | 通过（0 漏洞） | 通过（tsc --noEmit） | —（小程序无 vite build） | — |

- npm 11 的 `allowScripts` 拦截了 `esbuild@0.25.12` / `vue-demi@0.14.10` 的 install script，但 esbuild 二进制来自 `@esbuild/win32-x64` 可选依赖，`vite build` 实测不受影响。
- 小程序 typecheck 不等于微信开发者工具编译/真机验证。

### OpenAPI spectral 校验

- 工具：`D:\DevTools\openapi-validator`（锁定 `@stoplight/spectral-cli@6.17.0`，`.spectral.yaml` 继承 `spectral:oas`）。
- 命令：`npx spectral lint --ruleset .spectral.yaml -f json D:\GitHub\A-dental-clinic-system\specs\001-scheduling-booking\contracts\openapi.yaml`
- 结果：**0 错误、21 警告**。16 条风格（`info-contact` 1、`operation-description` 15）+ 5 条 `oas3-unused-component`（`responses/NotFound`、`schemas/BookingEventKind`、`BookingEventState`、`ResourceUnavailableState`、`ResourceBookingState`，均经 grep 确认无引用）。5 个未引用组件是否接线或删除属业务语义，本轮只记录未改动。


## Codex 后续复核（2026-10-22）

日志正则已替换为 SafeEventConverter 白名单输出，规则与诊断取舍见 logging.md。实际执行后端 Maven test：11项通过（7环境保护+4日志输出），未连接数据库。T005继续保留待验收，尚需真实运行场景验证；历史章节中的正则方案已被替代。

管理端 npm audit 返回3个开发依赖漏洞项（vitest、@vitest/mocker、tinypool）；npm audit --omit=dev 为0项。仅说明当前报告范围，不能解释为整个项目没有安全问题。开发工具升级待下一轮，未在本轮改动前端依赖。

## 八、Round 8：修复管理端开发依赖漏洞（vitest 工具链，2026-10-22）

仅升级管理端 Vitest 测试工具链，未开发业务功能、未改后端/日志/安全配置/测试。

### 漏洞报告（升级前）

`npm audit` 报告 3 项，均为测试工具链（devDependencies），`npm audit --omit=dev` 为 0 项：

| 包 | 严重度 | 范围 | 修复 |
| --- | --- | --- | --- |
| vitest（直接依赖） | critical | 0.0.95 – 4.1.10 | 升级到 4.1.12 |
| @vitest/mocker（传递依赖） | moderate | ≤4.1.11 | 随 vitest 升至 4.1.12 |
| tinypool（传递依赖） | critical | ≤2.1.1 | vitest 4.x 已移除 tinypool |

### 版本选择（最小必要升级，未用 --force / overrides）

- 选择 **vitest ^3.0.5 → ^4.1.12**（非 npm 建议的 5.0.5）。理由：4.1.12 内嵌 `@vitest/mocker@4.1.12`（修复 path traversal + code injection 两条 advisory）且**不再依赖 tinypool**（消除两条 prototype-pollution→RCE advisory）；4.x engines 为 Node `>=24`、peer 为 vite `^6||^7||^8`，与本机 Node 24.22.1 与 vite 6.4.5 兼容，无需连带升级 vite。
- vitest 5.0.5（npm 的 `fixAvailable`）为 semver-major 更大跨越（engines `^22.12||^24||>=26`、peer vite `^6.4`），非最小必要，未采用。
- 未使用 `npm audit fix --force`、未添加 `overrides`、未升级 vue/vite/element-plus/pinia/vue-router 等无关依赖。

### 修改与结果

- `admin-web/package.json`：`"vitest": "^3.0.5"` → `"vitest": "^4.1.12"`（唯一变更）。
- `admin-web/package-lock.json`：重新解析 vitest 4.1.12 传递树；tinypool 移除，`@vitest/*` 升至 4.1.12，`@vue/test-utils` 2.4.x→2.5.1（在已声明 `^2.4.6` 范围内，非无关升级）。
- 命令与结果（Node24 独立运行时，临时 PATH 前置）：

```bash
export PATH="/d/DevTools/node-v24.22.1-win-x64:$PATH"
cd admin-web
npm install vitest@^4.1.12 --save-dev   # 0 漏洞
npm ci                                   # 0 漏洞
npm run typecheck                        # 通过
npm run build                            # 通过（vite 6.4.5，1620 模块）
npm run test:unit                        # 通过（vitest 4.1.12，1 项）
npm audit                                # 0 漏洞
npm audit --omit=dev                     # 0 漏洞
```

- 最终版本：vitest 4.1.12、@vitest/mocker 4.1.12、tinypool 已移除、vite 6.4.5、@vitejs/plugin-vue 5.2.4、@vue/test-utils 2.5.1、jsdom 25.0.1。

### 剩余漏洞

- 完整 `npm audit` 与 `npm audit --omit=dev` 均为 **0 漏洞**，无剩余依赖漏洞。
- 测试未删减（仍 1 项 HomeView），未通过削弱测试规避问题；`vite.config.ts` 无需调整（`environment: 'jsdom'` 与 `defineConfig from vitest/config` 在 v4 均为稳定 API）。
