# HTTP基础实现（T011，2026-10-22）

新增统一业务异常、固定错误码及安全响应；拒绝JSON未知字段、基础类型错误和不合要求的输入。未知异常只返回固定500提示，不泄露SQL、异常内容或提交值。完整代码定义见specs/001-scheduling-booking/contracts/error-codes.md。

RequestTraceFilter在安全过滤链之前生成新的UUID，响应头与响应体共享请求编号，结束后恢复MDC防止线程复用串号。既有日志白名单处理追踪字段，不信任客户端X-Request-Id。ClockConfig提供UTC Clock，后续业务注入此Clock以支持边界测试；营业日期仍按Asia/Shanghai解释，不以服务器系统时区推断。

ApiId只对编号输出十进制字符串，避免前端丢失BIGINT精度；数量和金额不受全局转换影响。ApiResponse和PageResult是供后续控制器使用的白名单响应骨架，并未自动把数据库实体暴露给前端。分页默认20、最大100。

ApiException继承RuntimeException；真实PostgreSQL测试通过Spring事务代理执行写入后抛业务异常，确认写入回滚。后续跨服务调用应走代理，不使用同类内部调用绕过@Transactional。

本轮6项MockMvc契约测试与1项真实事务回滚测试通过；连同既有28项，共35项通过，0失败/错误/跳过。测试控制器只存在于测试源码，不是可公开调用的业务接口。登录、权限、限速、业务冲突映射尚需后续实现，尤其Security过滤链产生的401/403必须另接统一错误处理，不能依赖ControllerAdvice。

未修改Word、未部署、未推送GitHub。
