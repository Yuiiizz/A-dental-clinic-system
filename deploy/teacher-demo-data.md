# 课堂云端演示数据

老师访问的正式网址可以连接**独立的教学演示数据库**。数据库里保存真实表结构中的虚拟记录，因此门店、医生、排班、预约经正常接口读取，页面操作按现有权限、并发和状态规则处理。`/demo` 本地页面仍只是开发期演示，不作为云端功能验收依据。

云端演示的页面顶部统一说明人员和业务记录均为虚构；各门店、医生、用户等名称使用自然的虚构名称，不再逐条加“教学虚拟”“示例”前缀。内部 `DEMO` 编号、虚构地址及审计依据仍保留演示来源。当前数据库已导入过旧版种子，名称通过单独受控事务更新；**不要重新导入种子到当前数据库**。

## 内容与边界

`teacher-demo-seed.sql` 在全新、已完成 V001–V020 迁移且数据库名包含 `teacher_demo` 的库中一次性导入：2 家示例门店、8 位示例牙医、11 个参考项目、8 位示例用户、8 笔预约、4 条店内沟通样例、3 家虚拟供应商、4 个普通耗材、3 处库位、3 张已过账的虚拟验收单与 5 条等量入库流水，以及牙椅、资格、服务范围、政策和未来 14 天的营业区间与已发布排班。采购单据和取得成本也是虚构案例，不表示已付款、应付款或真实税额。价格、证号、姓名、地址、电话均为虚构案例，不代表实际诊所或可对外履行的医疗服务。

脚本不属于 Flyway 迁移，不随应用启动执行，也不修改 `app.seed.enabled=false`。它拒绝普通库名以及已有经营主体、门店或用户数据的数据库；第二次执行会拒绝。数据库应单独创建，不复用任何真实患者库。

账号以**待激活**状态导入，没有默认密码。脚本也不写微信 AppID、OpenID 或模拟登录。需先用[首次管理员命令](../docs/development/staff-login.md)在空库创建系统管理员，再运行脚本；之后由服务器管理员用现有本地激活命令为 `owner`、`frontdesk`、`inventory`、`dentist_001` 等教学账号设置独立密码，首次登录完成强制改密。公开给老师的账号权限应按演示内容选择；不要共享系统管理员密码。小程序端使用真实微信登录和本人建档，虚拟档案不会自动归属到老师的微信身份。

## 导入与验证

在独立教学库完成迁移、创建系统管理员后，在服务器本地使用具有该库写权限的数据库账号运行：

```text
psql -X -v ON_ERROR_STOP=1 -d a_dental_teacher_demo -f deploy/teacher-demo-seed.sql
```

导入前核对 `current_database()`、目标库中业务数据为空及备份。导入后核对 `branch` 2 行、示例牙医 8 行、`schedule` 112 行、示例预约 8 行、普通耗材 4 行、已过账验收单 3 行、匹配入库流水 5 行；用不同角色登录检查前台预约、医生排班、库管验收和患者权限。排班以导入时的上海日期生成，**14 天后不再提供未来时段**；临近老师检查时应在新建的空演示库重新初始化，或通过系统维护未来排班与营业区间。不要在现有库重复导入。

自动化验证：

```text
python backend/scripts/test_teacher_demo_seed.py --pg-bin D:/Postgres/bin
```

该命令会建立并停止一次性 PostgreSQL 集群，按顺序执行所有迁移，实际导入脚本，检查行数、重复导入拒绝、无预设密码和无微信绑定。它只验证样例数据及数据库约束；网页和小程序的真实登录、网络与手机端展示仍需在发布前走查。

## 库存样例增量（2026-11-23）

在已经导入上述种子的专用演示库中，可**单独**执行 [`operations/2026-11-23-inventory-samples.sql`](operations/2026-11-23-inventory-samples.sql)。它新增 14 种普通耗材与 14 个虚构批次，在两家门店各记入 14 条账面库存，共 6 张已过账的虚构验收单和 28 条等量入库流水。连同原种子，耗材目录共 18 项，云栖门店有 17 项账面库存，滨江门店有 15 项；没有库存出库、领用、付款或税务记录。界面和审计应继续明确这些记录为虚构资料，不代表实物、合格采购或临床使用资格。

脚本仅接受 `a_dental_teacher_demo` 库（隔离自动化测试库除外），核对原种子的组织、门店、供应商、库位及耗材标识；有同批编号或验收单据时整体拒绝，不会修补或覆盖已有单据。它不是 Flyway 迁移，不随应用启动执行。**不要重跑原始种子。**

先将该 SQL 文件传到服务器仓库的同一路径，确认 `deploy/production.env` 的数据库仍是专用演示库，再在服务器终端运行。备份使用服务器私有目录，不能提交到仓库：

```bash
cd /home/ubuntu/a-dental-clinic-system
umask 077
mkdir -p /home/ubuntu/a-dental-backups
sudo docker compose --env-file deploy/production.env -f deploy/compose.production.yaml exec -T db pg_dump -U a_dental_app -d a_dental_teacher_demo -Fc > /home/ubuntu/a-dental-backups/pre-inventory-20261123.dump
test -s /home/ubuntu/a-dental-backups/pre-inventory-20261123.dump
sudo docker compose --env-file deploy/production.env -f deploy/compose.production.yaml exec -T db psql -X -v ON_ERROR_STOP=1 -U a_dental_app -d a_dental_teacher_demo < deploy/operations/2026-11-23-inventory-samples.sql
```

`psql` 应输出每家门店 14 个新增库存品种、3 张新增验收单、14 条新增明细。然后用 `inventory` 登录管理端 `/inventory`，切换两家门店查看“批次余额”和“耗材目录”。如执行失败，**不要再次执行或重跑种子**，先保留错误输出并核查事务状态；脚本在 `COMMIT` 前遇错会回滚。

2026-11-23 云端执行记录：用户通过腾讯云文件管理上传脚本，本机与服务器 SHA-256 均为 `35b7346dac4ec0e80d78d3eff82b0e3f3beeb276d1caa377d7159146c6b67b6f`。导入前备份为服务器私有目录中的 `pre-inventory-20261123-102645.dump`，截图显示非空、权限 `600`、大小 388 KB；尚未执行恢复演练。用户提供的终端截图显示增量脚本 `COMMIT`，两家门店各新增 14 个库存品种、3 张验收单、14 条明细，无 `ERROR`。管理端实际刷新后的呈现仍需单独核对；当前库不得重复执行此脚本。

运行应用的演示库应先让应用的 Flyway 在空库创建迁移历史和表，再导入本脚本。上面的静态验证工具直接用 `psql` 执行 V001–V020，只用于验证数据；若仅照此手工建表而没有 Flyway 历史表，应用会拒绝启动。另已用一次性库实测库管网页登录、真实验收入库、余额与单据回查；这不代替老师实际访问的公网环境或小程序验收。
