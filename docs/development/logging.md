# 日志白名单（2026-10-22）

所有 profile 的应用 Logback 控制台仅输出时间、级别、批准事件代码、request_id 和 http_status。事件代码由 SafeEventConverter 固定白名单控制；request_id 仅接受 UUID，http_status 仅接受100–599，其余值输出“-”。其他事件输出 UNCLASSIFIED。禁止直接记录患者数据、请求体、Cookie、Authorization、密码、SQL参数及任意MDC。

不输出原始消息、格式化参数、线程名、完整MDC或异常消息/堆栈。未知框架日志也会被压缩为 UNCLASSIFIED：这是当前安全骨架的明确取舍，会减少启动故障诊断细节；后续可增加固定、无敏感数据的诊断事件，不可直接恢复原始输出。此策略不覆盖直接 System.out/err、第三方独立输出或日志系统自身配置错误诊断，后续代码不得用这些途径输出敏感信息。

新增事件须修改固定白名单并补测试；业务审计记录由后续T019另行实现，不能以控制台日志代替。

SafeLoggingTest 使用实际 logback-spring.xml 和 appender 输出验证 JSON密码、Bearer、患者文本、参数、嵌套异常、MDC及换行注入不泄露。4项日志测试与7项环境保护测试均通过，共11项，无数据库连接。生产环境初始化器的完整应用启动集成验证和真实测试库隔离仍待后续任务。
