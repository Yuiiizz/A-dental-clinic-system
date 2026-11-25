# 教师访问部署清单（2026-11-16）

目标：Linux 服务器运行 PostgreSQL、Java 后端和管理端网页三个容器；只公开 80/443。数据库和后端端口仅在容器网络中。所有教学记录均为虚构，生产环境不开 mock 微信或自动 seed。

## 访问方式

- 管理端可以通过公网 IP 访问，但员工登录依赖 Secure Cookie，必须使用浏览器信任的 HTTPS 证书。`http://<IP>` 只能作公开页面检查。`Caddyfile.ip` 尝试用 Let's Encrypt 的短期 IP 证书；在实际服务器验证签发、续期、老师浏览器信任之前不可交付。
- 小程序真机需要在微信后台配置 HTTPS request 合法域名。公网 IP 网页可打开，不表示小程序可使用同一个 IP。真机登录和预约必须另验；开发者工具“不校验域名”仅用于本地。
- 地域必须先选定。中国内地服务器通过公网 IP 对外提供网站服务也可能需要 ICP 备案；腾讯云 FAQ 的“IP 直接访问测试可暂不备案”不能当作长期公开给老师访问的批准。若截止前尚无备案，优先评估中国香港等非内地地域的 Linux 服务器，先实测老师所在网络的访问速度。不要先购买内地实例后假定可免备案公开运行。

## Linux 上准备

1. 选择有固定公网 IPv4 的 Linux 云服务器（Ubuntu 24.04 可用）。按本项目同时运行 PostgreSQL、Java 和网页容器、并在服务器构建镜像的负载估算，至少 2 vCPU/4 GB 内存、40 GB 磁盘；8 GB 内存构建更稳妥。先核对套餐的公网流量、地域、计费和到期日覆盖老师验收时间。安装 Docker Engine 和 Compose 插件。云防火墙公开 80/443，SSH 22 尽量仅允许本人 IP。将仓库复制到服务器。
2. 复制 `deploy/production.env.example` 为 `deploy/production.env`。公网 IP 方案填写 `SITE_ADDRESS=<公网IPv4>`、`CADDYFILE=deploy/Caddyfile.ip`；有域名时填域名并使用默认 Caddyfile。数据库名须包含 `teacher_demo`。
3. 在服务器仓库根目录生成密钥文件，不把内容发送到聊天或提交 Git：

```sh
mkdir -p deploy/secrets
umask 077
openssl rand -base64 48 > deploy/secrets/db_password.txt
openssl rand -base64 32 > deploy/secrets/checkin_signing_key.txt
touch deploy/secrets/wechat_app_secret.txt
chmod 600 deploy/production.env deploy/secrets/*.txt
```

Compose 的文件型 secret 是只读绑定挂载，不会替文件重新映射所有者。PostgreSQL 启动脚本会以 `postgres` 用户再次读取密码文件。先查该镜像中 `postgres` 的数值 UID，再将三份 secret 的所有者设为这个 UID；API 入口以 root 读取 secret 后立即降权到 UID 10001 运行 Java。保持文件仅所有者可读：

```sh
docker pull postgres:18.6
PG_UID=$(docker run --rm --entrypoint id postgres:18.6 -u postgres)
sudo chown "$PG_UID:$PG_UID" deploy/secrets/*.txt
chmod 600 deploy/production.env
sudo chmod 400 deploy/secrets/*.txt
```

真实微信 AppSecret 获得后只在服务器本地安全写入 `wechat_app_secret.txt`，并恢复上述所有者和权限。空文件允许先部署网页，真实微信登录会报未配置。AppID 填在 `production.env`。不要输出 secret 内容。

4. 检查、构建和启动：

```sh
docker compose --env-file deploy/production.env -f deploy/compose.production.yaml config --quiet
docker compose --env-file deploy/production.env -f deploy/compose.production.yaml build
docker compose --env-file deploy/production.env -f deploy/compose.production.yaml run --rm --no-deps --entrypoint /bin/sh api -c 'test -r /run/secrets/db_password && test -r /run/secrets/checkin_signing_key'
docker compose --env-file deploy/production.env -f deploy/compose.production.yaml up -d
docker compose --env-file deploy/production.env -f deploy/compose.production.yaml ps
```

为后续命令定义缩写：

```sh
dc() { docker compose --env-file deploy/production.env -f deploy/compose.production.yaml "$@"; }
```

后端第一次启动由 Flyway 创建 V001–V020。用 `dc logs --tail=100 api web` 查看错误。IP HTTPS 配置下，`curl -I http://127.0.0.1/api/v1/public/branches` 只用于检查 HTTP 到 HTTPS 的跳转；拿到受信任证书后，再用 `curl -i https://<公网IP>/api/v1/public/branches` 检查接口。公网链接还需另一台设备检查证书和登录。

## 教学账号和数据

1. 确认 `dc ps` 的 db healthy、api 已完成迁移。空库先在交互式终端运行 `dc run --rm api --spring.main.web-application-type=none --app.bootstrap.enabled=true`，依提示创建唯一系统管理员。Compose `run` 会在交互式终端自动分配 TTY；密码不进入命令行、脚本或聊天。若提示没有 Java console，应停止排查 TTY，不绕过守卫。
2. 导入虚构数据：`dc exec -T db psql -X -v ON_ERROR_STOP=1 -U a_dental_app -d a_dental_teacher_demo < deploy/teacher-demo-seed.sql`。若改了数据库名/用户，同步修改命令。脚本拒绝普通库名和重复导入。
3. 用 `dc run --rm api --spring.main.web-application-type=none --app.activation.enabled=true` 逐个激活需要展示的 `frontdesk` 等待激活教学账号，首次网页登录完成强制改密。不要向老师共享 sysadmin 密码。
4. 教学排班仅覆盖导入日起约 14 天；检查前核对未来营业区间和已发布排班。

## 交付前逐项验收

- 老师可能使用的网络上，HTTPS 证书受信任；登录、刷新、退出和 CSRF 正常。仅有 HTTP 页面不算通过。
- IP 短期证书有效期约六天；首次签发成功后观察自动续期，并在老师检查前一天再次从外部设备核验证书有效期与登录。不要只凭首次打开成功交付。
- 前台教学账号完成“精确检索或建档→现场核验→选择凭证→代预约”，并用另一角色验证越权拒绝。只用虚构身份。
- 若要扫码小程序，先在微信平台配置合法 request 域名，再真机测试真实微信 code、建档与预约。本地 mock/模拟器不能替代此项。
- 记录公网 URL、验收日期、账号角色、截图或录屏及未通过项。老师检查期间保持实例与教学账号可用。
- 升级前备份：`dc exec -T db pg_dump -U a_dental_app -d a_dental_teacher_demo -Fc > <服务器私有备份路径>`，检查文件非空且恢复可用；不要提交备份。`docker compose down` 不加 `-v`。

## 当前状态

2026-11-16 已在腾讯云轻量服务器 Ubuntu 24.04 上启动 `db`、`api`、`web` 三个容器；数据库只在容器网络开放。公网 HTTPS `https://1.15.110.181/` 从本地外部网络访问返回 200，公开门店接口返回两家明确标注“教学虚拟”的门店；未登录访问 `/api/v1/auth/me` 返回预期的 401。Caddy 使用短期 IP 证书；外部读取的证书 SAN 包含该 IP，当前有效期截至 2026-11-23 04:36 UTC。交付前仍须复查续期和老师设备的信任状态。

唯一系统管理员已由用户在服务器交互终端初始化。导入前只读核对为 1 个员工账号、1 名员工、1 条角色、1 条审计、0 个机构/门店/用户。导入前备份保存在服务器 `/home/ubuntu/a-dental-backups/preseed-20261116.dump`，非空、权限 600。`teacher-demo-seed.sql` SHA-256 经本机与服务器核对一致，事务性导入以 `COMMIT` 结束；导入后有 1 个机构、2 家门店、12 名员工、12 个账号、8 个教学虚拟用户、112 条排班、8 条预约、11 个服务项目、3 家供应商。教学前台已由用户在服务器交互终端激活；随后按用户要求将登录名 `teacher_frontdesk` 改为 `frontdesk`，账号编号 902 不变。只读核对显示账号启用、已完成首次改密、1 个前台角色及 2 家门店范围。改名事务递增授权版本、撤销旧会话并写审计；其余教学员工账号仍待逐个激活，无默认密码。

导入后另存 `/home/ubuntu/a-dental-backups/postseed-20261116.dump`，已用 `pg_restore -l` 检查归档可读取；没有在实际数据库执行恢复。
前台激活后另存 `/home/ubuntu/a-dental-backups/post-frontdesk-activation-20261116.dump`，已检查归档可读取；备份都留在服务器私有目录，未提交仓库。
改名前另存 `/home/ubuntu/a-dental-backups/pre-frontdesk-rename-20261116.dump`；一次性受控 SQL 保存在服务器同一私有目录，限定教学库、账号 902、原登录名及未占用的新登录名。没有修改其他账号、角色、门店或业务记录。仓库中的教学种子已同步为新登录名；不要对已导入数据库重跑种子。

数据库状态显示教学前台已完成首次改密，但尚未由 Codex 独立观察公网浏览器的登录与前台业务流程；患者小程序真机与真实微信登录尚未联调。云端未配置微信 AppSecret，不能将网页部署成功视为小程序可远程使用。不要将本次虚拟教学数据当作真实患者数据，也不要删除 `deploy_clinic_db` 卷。

2026-11-16 后续核查：三个容器持续运行、数据库 healthy，根分区约 30 GB 可用，公开医生接口返回本店教学医生；排班和结构化营业区间覆盖 11 月 17–30 日。发现 OpenAPI 已声明但后端未注册 `/api/v1/public/slots`，公网请求返回 500。已补接口并通过本机隔离 PostgreSQL 全量 414 项测试（0 失败/错误/跳过）。发布前备份 `/home/ubuntu/a-dental-backups/pre-public-slots-release-20261116.dump` 已检查归档可读取；仅重建并重启 API，数据库与网页容器未重建。公网复测：11 月 18 日教学门店/项目时段返回 200 和候选、超 31 日返回 422、医生过滤返回 200、公开门店仍为 200；数据库 1 个机构、2 家门店、12 名员工、8 名虚构用户、112 条排班、8 条预约，与发布前一致。前台改名后备份另有 `/home/ubuntu/a-dental-backups/post-frontdesk-rename-20261116.dump`。

用户在已登录 `frontdesk` 的公网浏览器选择 2026-11-18 后提供的[预约工作台截图](../docs/screenshots/cloud-frontdesk-appointments-20261118.png)显示 8 笔教学虚拟预约，医生、门店、项目和上海时间与数据库种子一致。该证据覆盖登录后的读取展示；创建、签到、取消等写入操作仍需单独验收，不因页面出现按钮就视为通过。

用户要求简化负责人登录名。改名前备份为 `/home/ubuntu/a-dental-backups/pre-owner-rename-20261116.dump`，已检查归档可读取。受控事务将待激活账号 901 的 `teacher_owner` 改为 `owner` 并写入审计；角色仍为 owner、两家门店 scope 不变、密码仍为空，需再运行首次激活命令。教学种子脚本已同步新登录名，不得在当前库重新导入。

2026-11-17 核对：用户已通过服务器交互命令激活 `owner`。数据库显示账号启用、密码摘要存在、需首次改密、owner 角色及两家门店范围；当前尚无该账号有效网页会话。激活后备份 `/home/ubuntu/a-dental-backups/post-owner-activation-20261117.dump` 已检查归档可读取。公网首页与公开门店接口仍返回 200。下一步由账号持有人网页登录并改密，再验收负责人页面。

随后用户提供云端页面截图：`owner` 可见两家营业门店；排班配置页显示 8 位可排班医生、11 个服务项目和 112 条已发布排班。数据库只读核对账号 901 已完成首次改密。这是页面读取证据，不能替代新建或发布排班的写入验收。截图暴露排班表单仍以 2026-10-30 为固定默认日期。已将默认日期改为上海当天，并在表单及提交前限制过去日期；本地和服务器网页构建通过，只重建 `web` 容器，`api`/`db` 未重启。公网首页和公开门店接口复核均为 200。用户浏览器刷新页面后才会加载新版前端资源。

用户要求后续登录名不带 `teacher_` 前缀。改名前备份 `/home/ubuntu/a-dental-backups/pre-pending-login-rename-20261117.dump` 已验证归档目录可读。受控事务只将尚未激活的账号 903、911–918 分别改为 `inventory`、`dentist_911`–`dentist_918`，递增各自授权版本并逐项写审计。只读核对 9 个目标账号仍停用、仍需首次改密；`owner` 和 `frontdesk` 保持原状。改名后备份 `/home/ubuntu/a-dental-backups/post-pending-login-rename-20261117.dump` 也已验证归档目录可读，机构/门店/员工/用户/排班/预约数量分别保持 1/2/12/8/112/8。新种子脚本同步名称，但**不得在当前数据库重新导入**。此次不改业务表或已发布排班。

用户随后指定第一位医生使用 `dentist_001`。只读确认账号 911 仍待激活后，先留备份 `/home/ubuntu/a-dental-backups/pre-dentist-001-rename-20261117.dump`，再在受控事务内只更名该账号并写审计；其他医生仍为 `dentist_912`–`dentist_918`。新种子脚本及演示说明同步名称，不重导当前库。

用户已在服务器交互终端激活 `dentist_001`。数据库只读核对账号 911 启用、密码摘要存在、仍需首次网页登录改密，具有 dentist 角色及 1 家门店 scope。激活后备份 `/home/ubuntu/a-dental-backups/post-dentist-activation-20261117.dump` 已验证归档目录可读。尚未进行该账号的公网网页登录与临床写入验收。

随后用户提供 `dentist_001` 公网临床工作台截图，页面可读取，候诊/接诊中、就诊记录、复诊计划均为 0。数据库只读核对账号 911 已完成首次改密；该医生一笔教学预约 941 为 2026-11-18 10:00（上海时间）、状态“已确认”。临床工作台只列出“候诊中/接诊中”，而签到要求预约与当天为同一上海业务日期；故 2026-11-17 看到空下拉符合当前数据，不代表临床写入已通过。下一步在预约日由前台签到，再由医生开始接诊并完成记录；该流程会改变教学样例状态，执行前应留备份。

用户指出空预约下拉框难以理解。已在临床页增加“已确认预约须先由前台在预约当天签到”的空状态提示和文字选项；没有可选预约时禁用开始/完成接诊，有来源就诊记录前禁用复诊计划创建。复诊计划日期默认值由固定旧日期改为上海当天至 14 天后。本地和服务器网页构建通过，仅重建 `web` 容器，`api`/`db` 未重启；公网首页与公开门店接口均返回 200。需浏览器刷新后查看新页面，尚未进行临床写入验收。

用户反馈“已签到但医生未更新”。数据库只读核对预约 941–948 全部仍为“已确认”，`queue_ticket` 无对应候诊号；已在用户打开的 Edge 中刷新 `/clinical`，仍显示 0 和空状态，说明这次签到未落库，并非医生页缓存。预约 941 在 2026-11-18，当前为 2026-11-17；后端只允许预约当天签到。前台页面原先提前显示可点击“签到”，已改为非预约当天禁用并显示“预约当天可签到”，提交前也再次校验日期。本地与服务器网页构建通过，仅重建 `web` 容器；`api`/`db` 未重启，公网首页 200。仍需预约当天实测签到与医生接诊写入。

用户发现鼠标悬停在禁用签到按钮上会显示等待转圈。原因是前台页面把所有禁用按钮的 CSS 光标设为 `wait`；已改为 `not-allowed`，本地与服务器网页构建通过，仍只重建 `web` 容器，公网首页 200。业务规则与数据未改变。

用户要求暂停签到及接诊测试，改为完善其他内容，并取消逐条名称里的“教学虚拟”“示例”前缀。此轮未修改签到窗口。名称优化前备份 `/home/ubuntu/a-dental-backups/pre-demo-names-20261117.dump`，受控事务按 45 条精确旧值映射更新机构、门店、员工、用户、供应商、物料、库房、服务及资源名称，并逐条写审计；不修改账号、预约、排班或历史沟通/采购凭据。优化后备份 `/home/ubuntu/a-dental-backups/post-demo-names-20261117.dump`，两份均验证归档目录可读。核对仍为 1 机构、2 门店、12 员工、8 用户、112 排班、8 预约、0 候诊号。未来新库的种子脚本已同步名称，当前库不得重导。云端网页通过构建参数 `CLASSROOM_DEMO=true` 在页面顶部统一说明虚构数据，本地普通构建不显示；只重建 `web` 容器，`api`/`db` 未重启。公网首页及公开门店均返回 200，公开门店名称不再带逐条前缀。

同批又将预约、收费、临床、排班、治疗计划页的“真实数据”小标签改为“实时接口”，以免与顶部“人员及业务记录均为虚构”说明冲突。云端前端再次构建与发布成功，公网首页及公开门店返回 200；未修改签到配置、预约、候诊或临床记录。

进一步检查生产导航：旧的 2026-10 月日期仅用于本地开发演示模块，云端正式路由不显示该模块。医生临床工作台新增手动“刷新接诊列表”按钮，以便前台签到后在同一页面重新读取；管理端侧栏副标题由“完整界面”改为“业务工作台”。云端前端构建、单独网页容器发布及公网首页/公开门店 200 检查通过；仍未进行预约日的签到/接诊写入测试。

2026-11-18 10:05（上海时间）只读确认预约 941 仍为“已确认”、版本 1，尚无候诊号；门店 901 的 `early_minutes`/`late_minutes` 原为 NULL，正常签到会要求人工办理理由。配置前已备份 `/home/ubuntu/a-dental-backups/pre-checkin-window-20261118.dump`，并用 `pg_restore -l` 验证归档可读。随后仅为门店 901 设置签到提前 30 分钟、迟到宽限 30 分钟，保持自助签到关闭、时段步长 30 分钟；版本由 1 增至 2，事务内新增系统审计。受控脚本见 `deploy/operations/2026-11-18-checkin-window.sql`，含原值守卫，不可在已配置的库中重复执行。未来新库的教学种子已同步两家门店的签到窗口；**当前库不得重新导入种子**。截至配置核验时未执行前台签到或临床记录写入。

随后用户通过 `frontdesk` 页面现场签到预约 941；数据库只读复核为“候诊中”、版本 2、候诊号 1、签到渠道“前台”。这是现有云端教学虚构预约的真实 API/数据库写入，尚未执行医生开始接诊和完成临床记录。用户指出签到码在页面底部不便向身边就诊用户展示，网页现改为生成后自动弹出居中二维码，支持关闭、过期提示、重新生成和 Esc 关闭；原底部二维码及“放大给用户看”入口保留。管理端 Node 24 类型检查、250 项单测、生产构建通过；仅重建云端 `web` 容器，`api`/`db` 保持运行。公网首页返回 200；弹窗的真实浏览器交互仍待页面刷新后点按确认。

用户随后以 `dentist_001` 刷新临床工作台，确认候诊数 1、下拉包含顾安宁，并点击“开始接诊”。数据库只读复核预约 941 为“接诊中”、版本 3；尚未完成就诊记录。用户要求医生端下拉不重复显示医生姓名。源码检查发现原工作台候诊查询按授权门店返回所有医生的候诊预约，而开始接诊仅允许预约确认医生；已将 `appointments` 服务端查询收紧为当前登录医生，并将选择项改为“就诊用户姓名 · 预约时间 · 状态”。隔离 PostgreSQL 全量 415 项通过、0 失败/0 错误；管理端类型检查及生产构建通过。发布前备份 `/home/ubuntu/a-dental-backups/pre-clinical-filter-20261118.dump` 并验证归档可读；仅重建 `api`/`web` 容器，`db` 保持运行。公网公开门店 API 返回 200，预约 941 仍为“接诊中”、版本 3。医生页面刷新后的实际视觉效果待用户点按确认。

2026-11-18 晚新增两组**虚构**已完成就诊案例，供复诊与收费页面展示：宋予宁的初诊检查对应未收款账单；方若汐的种植术后复查对应待协调复诊计划和一笔明确标注未发生真实资金收取的线下登记案例。执行前备份 `/home/ubuntu/a-dental-backups/pre-teaching-cases-20261118.dump` 并验证可读；相同脚本先在事务内完整执行后回滚，确认约束通过，再执行正式增量脚本 `deploy/operations/2026-11-18-teaching-cases.sql`。数据库当时的患者/预约/就诊/复诊计划/账单/收款数量分别为 10/10/2/1/2/1，预约 941 保持“接诊中”。显式案例 ID 的序列已前移，正常页面新增不会撞号。导入案例时 `followup_plan.service_item_id` 尚未迁移，因此当时未伪造此关联；后续 V021 及样例补充见下文。

本批还将多个有现成真实工作台的旧演示入口接入正式导航，详见 `docs/development/interaction-and-data-coverage-20261118.md`。管理端 250 项单测、类型检查和构建通过，云端只重建 `web`，公网首页与公开门店接口均为 200。导入后备份 `/home/ubuntu/a-dental-backups/post-teaching-cases-20261118.dump` 已验证可读；本批四份备份均设为仅 ubuntu 可读（600）。

2026-11-18 晚修复 Word/数据字典与运行库之间的复诊项目缺口：V021 为 `followup_plan` 增加可空 `service_item_id` 外键，不修改旧记录。发布前备份 `/home/ubuntu/a-dental-backups/pre-v021-followup-20261118.dump` 并验证 `pg_restore -l` 可读；仅重建 `api` 和 `web`，`db` 容器未重建。Flyway 最新版本为 021，原有计划仍 1 条、项目字段为空，预约 941 仍“接诊中”版本 3；公网网页和公开门店 API 均返回 200。此时另存 `/home/ubuntu/a-dental-backups/post-v021-pre-sample-20261118.dump` 并验证可读。随后只对虚构计划 961 使用 `deploy/operations/2026-11-18-followup-sample-item.sql`：先将 COMMIT 替换为 ROLLBACK 完整试跑，再正式执行，守卫源就诊、医生、状态及项目开放事实，补关联项目 904 并写系统审计。最后备份 `/home/ubuntu/a-dental-backups/post-v021-sample-20261118.dump` 并验证可读。最终患者/预约/就诊/复诊计划仍为 10/10/2/1；预约 941 不变。三个新备份均为 600 权限。**不要重跑原教学种子或这份一次性样例脚本。**

2026-11-19 补一个供已激活医生创建复诊计划的历史来源：执行前备份 `/home/ubuntu/a-dental-backups/pre-doctor-plan-source-20261119.dump`，后备份 `/home/ubuntu/a-dental-backups/post-doctor-plan-source-20261119.dump`，均为权限 600 的非空归档并已在容器内用 `pg_restore -l` 读取。`deploy/operations/2026-11-19-doctor-plan-source.sql` 与上传文件 SHA-256 相同，先把 COMMIT 换成 ROLLBACK 完整执行并确认零留存，再正式提交：虚构用户 951、已完成预约 962、医生 911 的就诊 962；没有创建复诊计划、账单或收款。患者/预约/就诊/计划总数 11/11/3/1，预约 941 仍“接诊中”版本 3。该一次性脚本有库名、ID、排班、账号、服务守卫，**不得重跑**。仅重建并重启 `api`/`web`，`db` 容器未重建；公网首页/公开门店接口 200，未登录临床接口 401。仓库 Word 已同步 V021 可空字段、项目匹配约束及图 50、图 70，图 64 添加总览说明；PDF 排版核验为 128 页。

2026-11-20 锁序修订前备份 `/home/ubuntu/a-dental-backups/pre-scope-lock-order-20261120.dump`，权限 600、非空，并用 `pg_restore -l` 验证可读取。范围保存与新任职统一按机构、门店、员工加锁；隔离 PostgreSQL 全量 420 项通过。证书只读检查：当前公网 IP 证书的 `notAfter` 为 **2026-11-23 04:36:16 UTC**，早于课堂交付日。Caddy 日志显示已启用自动证书管理；**11 月 22 日及交付当天须再次从外部检查 HTTPS 访问、证书有效期与老师设备的信任状态**，不能仅以当前 200 代替交付时结果。

2026-11-23 普通耗材样例增量：`deploy/operations/2026-11-23-inventory-samples.sql` 经本地隔离 PostgreSQL 测试，新增 14 种耗材、14 个批次、6 张已过账验收单和 28 条匹配流水；重复执行被拒绝。用户通过腾讯云文件管理上传到云服务器，同本机核对 SHA-256 为 `35b7346dac4ec0e80d78d3eff82b0e3f3beeb276d1caa377d7159146c6b67b6f`。用户提供的终端截图显示导入前备份 `/home/ubuntu/a-dental-backups/pre-inventory-20261123-102645.dump` 非空、权限 600、大小 388 KB；`psql` 随后输出 `COMMIT`，云栖与滨江门店各增加 14 种库存、3 张验收单、14 条明细。未重跑原种子，未重建容器；云端管理端刷新后的余额/目录页面仍待核对，不能仅以 SQL 输出代替界面验收。
