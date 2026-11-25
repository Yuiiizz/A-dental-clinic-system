# 本地 PostgreSQL 容器（准备稿）

本配置只启动 PostgreSQL 18.6，绑定本机 127.0.0.1:55432；不会公开到互联网。数据库数据保存在 Docker 命名卷，不复用现有 Windows 数据目录。PostgreSQL 18 官方镜像的数据卷挂载点是 `/var/lib/postgresql`。

## 首次使用

1. 安装 WSL2 的 Ubuntu 发行版和 Docker Desktop（启用 WSL2 后端），确认 `wsl --list --verbose` 与 `docker compose version` 正常。本文件编写时本机尚无可用 Ubuntu 发行版或 Docker 命令。
2. 将 `deploy/db.env.example` 复制为 `deploy/db.env`，为 `POSTGRES_PASSWORD` 填独立强密码。`db.env` 被 Git 忽略，勿发给他人或提交。
3. 在仓库根目录运行 `docker compose --env-file deploy/db.env -f deploy/compose.db.yaml config` 检查配置，然后运行 `docker compose --env-file deploy/db.env -f deploy/compose.db.yaml up -d`。
4. 运行 `docker compose --env-file deploy/db.env -f deploy/compose.db.yaml ps`，确认 db 为 healthy。
5. Windows 上运行后端时，显式设置 `DB_URL=jdbc:postgresql://127.0.0.1:55432/a_dental_dev`、`DB_USERNAME` 和 `DB_PASSWORD`，再启动 Spring Boot。Flyway 会执行仓库已有迁移。账号和数据库名必须与 db.env 一致。

`docker compose ... down` 停止容器并保留命名卷；不要加 `-v`，否则会删除数据库卷。首次试验请使用空的开发库；现有数据库数据若要迁移，应先备份，再在隔离环境验证恢复。命名卷不等于备份。

这不是云端发布配置。上云仍须部署后端和管理端、HTTPS 反向代理、真实微信凭证、域名及小程序合法域名，并在手机上验证完整流程。正式服务不公开数据库端口，不启用 mock 或应用自动 seed。课堂演示若需要虚拟资料，使用单独的[教学演示数据库](teacher-demo-data.md)显式导入并标明虚构性质。
