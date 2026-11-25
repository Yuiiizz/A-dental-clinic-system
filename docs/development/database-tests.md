# 独立 PostgreSQL 测试（T007）

测试脚本 backend/scripts/test_postgres.py 使用本机 PostgreSQL18 二进制，在 backend/target 下创建随机独立集群，不连接既有服务。仅监听127.0.0.1，使用随机端口、随机密码和独立数据库 dental_test_<随机ID>；凭证只传子进程环境，初始化密码文件随后删除。

PostgresTestSupport 在连接前严格核对 URL、用户名、随机数据库名和测试标识格式，拒绝默认开发库、远端地址及附加连接参数；连接后核对数据库名、用户和本次运行标识。测试用例通过该入口获取各自连接。不得绕过支持类直接使用共享 DB_URL，后续 Spring 集成测试亦必须注入并验证专用数据源。

测试集群里的 test_harness.identity 仅为归属校验，非业务表。脚本在 finally 停止集群；停止后的文件保留在已忽略的 target 内供诊断，不递归删除任何其他目录。若进程被强制终止，须核实本次日志目录与进程后再清理。测试角色只用于这一临时集群，不得用于生产。

PowerShell（项目根目录）：

```powershell
$env:JAVA_HOME='D:\Java\jdk-21'
& C:/Users/Balli/anaconda3/python.exe backend/scripts/test_postgres.py --pg-bin D:/Postgres/bin --maven D:/DevTools/apache-maven-3.9.16/bin/mvn.cmd --settings D:/DevTools/maven-settings-aliyun.xml
```

Maven路径和镜像文件可按本机调整，脚本 --settings 可省略。Python仅作为本地测试编排工具，不是系统后端运行依赖。

验证：7项环境保护、4项日志、5项测试工具及2项真实PostgreSQL测试，共18项通过，无失败或跳过。真实库测试确认 PostgreSQL18、4条独立连接的事务回滚。并发工具通过屏障验证真实并发，支持超时和失败传播；MutableClock支持无需sleep地推进时间。

直接运行普通 Maven test 未配置集群时，2项真实数据库测试会明确跳过，不能据此声称数据库验证完成。正式验收须运行上述脚本并检查跳过数量。当前尚未创建业务表、运行Flyway迁移或实现预约并发逻辑。
