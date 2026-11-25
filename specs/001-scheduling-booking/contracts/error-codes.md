# 基础错误码（T011）

错误响应统一为 code、message、request_id；可选field_errors暂不返回，避免带出拒绝值或敏感字段。客户端按code分支，不依赖中文提示文本。数据库错误、SQL和异常堆栈不得作为message。

| code | HTTP | 含义 |
| --- | --- | --- |
| INVALID_REQUEST | 422 | JSON格式或通用参数不符合要求 |
| UNKNOWN_FIELD | 422 | 请求包含契约未声明字段 |
| VALIDATION_FAILED | 422 | 必填或字段格式错误 |
| NOT_ACTIVATED | 422 | 账号尚未激活，此操作不能取消首次激活资格 |
| UNAUTHENTICATED | 401 | 未登录或会话失效 |
| FORBIDDEN | 403 | 已登录但无权操作 |
| NOT_FOUND | 404 | 记录不存在或对当前用户不可见 |
| VERSION_CONFLICT | 409 | 并发版本已变化 |
| SLOT_UNAVAILABLE | 409 | 时段或资源不可用 |
| FOLLOWUP_PLAN_CONFLICT | 409 | 复诊计划当前不能用于患者自助预约或改约；需刷新计划或联系门店 |
| FOLLOWUP_PLAN_ALREADY_ACTIVE | 409 | 源就诊已有待协调或已预约复诊计划；不能重复制定 |
| IDEMPOTENCY_CONFLICT | 409 | 同幂等键内容不同 |
| ACCOUNT_EXISTS | 409 | 该员工或登录名已有账号（不披露具体身份） |
| POLICY_CHANGED | 409 | 需重新确认政策版本 |
| RATE_LIMITED | 429 | 超过频率限制 |
| WECHAT_NOT_CONFIGURED | 503 | 微信登录未配置，请联系管理员 |
| WECHAT_EXCHANGE_FAILED | 502 | 微信身份换取失败，请重新获取登录凭证 |
| REGISTRATION_TOKEN_INVALID | 401 | 注册凭证无效或已过期，请重新获取 |
| WECHAT_IDENTITY_ALREADY_BOUND | 409 | 该微信身份已绑定，请直接登录（不披露绑定详情） |
| PATIENT_DUPLICATE_SUSPECTED | 409 | 该联系方式已存在档案，请联系前台核实（不披露他人资料） |
| BINDING_TOKEN_INVALID | 401 | 绑定凭证无效或已过期，请联系前台重新签发 |
| PATIENT_ALREADY_BOUND | 409 | 该患者已绑定微信，请直接登录（不披露绑定详情） |
| PATIENT_ACCOUNT_DISABLED | 403 | 该患者账号已停用，无法绑定 |
| BUSINESS_BASIS_REQUIRED | 422 | 请提供可核验的线下办理依据 |
| BINDING_TOKEN_ALREADY_ACTIVE | 409 | 该患者已有待用绑定凭证，请重新签发 |
| STALE_BINDING_REISSUE | 409 | 重签引用已失效，请刷新后重试 |
| BRANCH_CODE_EXISTS | 409 | 门店代码已存在 |
| BRANCH_ORGANIZATION_NOT_CONFIGURED | 422 | 经营主体未配置，不能创建门店 |
| BRANCH_ORGANIZATION_AMBIGUOUS | 409 | 经营主体配置不唯一，不能创建门店 |
| BRANCH_ORGANIZATION_DISABLED | 403 | 经营主体已停用，不能创建门店 |
| BRANCH_MANAGER_INVALID | 422 | 门店负责人必须为当前创建者 |
| BRANCH_STATE_CONFLICT | 409 | 门店状态不支持此操作 |
| BRANCH_HAS_FUTURE_COMMITMENTS | 409 | 门店仍有未结束的排班或预约，暂不能变更；返回精确 impact 摘要 |
| BRANCH_FIELD_NOT_EDITABLE | 422 | 营业中的门店不能修改执业地址/许可证等核验事实 |
| BRANCH_LICENSE_INVALID | 422 | 许可证有效期无效、已过期或缺少无期限确认 |
| BRANCH_VERIFICATION_BASIS_REQUIRED | 422 | 核验必须提供明确依据 |
| OPENING_INTERVAL_RANGE_INVALID | 422 | 营业区间日期范围无效或超过31个连续自然日 |
| OPENING_INTERVAL_INVALID | 422 | 营业区间格式、归属日期、时区或起止时间无效 |
| OPENING_INTERVAL_HISTORY_LOCKED | 409 | 历史日期或今天已开始的营业区间不能修改 |
| OPENING_INTERVAL_OVERLAP | 409 | 营业区间存在重复或重叠 |
| OPENING_INTERVAL_COVERAGE_CONFLICT | 409 | 修改后覆盖不足，返回未结束排班/活动预约的精确 impact |
| STAFF_ORGANIZATION_NOT_CONFIGURED | 422 | 经营主体未配置，不能维护员工主档 |
| STAFF_ORGANIZATION_AMBIGUOUS | 409 | 经营主体配置不唯一，不能维护员工主档 |
| STAFF_ORGANIZATION_DISABLED | 403 | 经营主体已停用，不能维护员工主档 |
| STAFF_PRACTICE_NO_REQUIRED | 422 | 牙医必须填写执业标识 |
| STAFF_PRACTICE_NO_EXISTS | 409 | 该执业标识已存在 |
| STAFF_CREDENTIAL_EXISTS | 409 | 该员工的同类资格证件已存在 |
| STAFF_CREDENTIAL_INVALID | 422 | 资格日期或无期限确认不成立 |
| STAFF_CREDENTIAL_EXPIRED | 422 | 该资格已过期，不能核验为当前有效 |
| CREDENTIAL_STATE_CONFLICT | 409 | 资格状态不支持此操作 |
| SUPPLIER_CODE_EXISTS | 409 | 供应商主体标识已存在；不得自动合并档案 |
| METHOD_NOT_ALLOWED | 405 | 不支持该HTTP方法 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 不支持请求媒体类型 |
| INTERNAL_ERROR | 500 | 未预期错误，返回固定安全提示 |

以上包含已实现的通用处理和供后续业务使用的代码定义，不意味着对应登录、预约或限速功能已实现。预约限制需要本人可见的到期及联系信息，后续政策模块会单独明确安全结构，不能塞入任意异常文本。

请求追踪由服务端生成UUID，并在响应头X-Request-Id和错误/业务响应中保持一致。不信任客户端传入的追踪ID，不向日志输出原始请求。业务返回必须使用显式白名单DTO，不可直接返回JDBC行、账号实体或Map全集。ID字段用ApiId或经过验证的十进制字符串，不全局把金额等数值改成字符串。

401/403还需在T012安全过滤链中配置同格式处理；当前ControllerAdvice不能代替过滤器层的认证入口和拒绝处理器。分页limit默认20、范围1–100，游标编码、过滤条件和数据范围检查由各查询服务实现。

# 普通耗材验收入库（2026-11-10）

| 代码 | HTTP | 含义 |
| --- | ---: | --- |
| `STOCK_ITEM_CODE_EXISTS` | 409 | 耗材编码已存在 |
| `WAREHOUSE_EXISTS` | 409 | 本店库位名称已存在 |
| `RECEIPT_DOCUMENT_EXISTS` | 409 | 验收单号已存在 |
| `STOCK_BATCH_CONFLICT` | 409 | 同一品种、厂家、批号的既有事实与本次不同 |
| `STOCK_RECEIVE_UNAVAILABLE` | 409 | 供应商、耗材、库位或批次当前不允许验收入库 |

未知字段仍返回 `UNKNOWN_FIELD` 422；未过账、不存在或不可见的验收详情返回 `NOT_FOUND` 404；同键异内容返回 `IDEMPOTENCY_CONFLICT` 409。数据库异常继续回滚并由安全异常处理返回 500，不伪装为业务冲突。

# 短效签到码（2026-11-10）

| 代码 | HTTP | 含义 |
| --- | ---: | --- |
| `CHECKIN_NOT_CONFIGURED` | 503 | 门店未启用自助签到或服务端尚未配置独立签名密钥，不能发码 |
| `CHECKIN_CODE_INVALID` | 409 | 签到码签名无效、门店不符或已过期 |

前台无目标门店权限返回 403；配置版本冲突返回既有 `VERSION_CONFLICT` 409。同键同内容重放返回原签名码及其原到期时刻，过期后须使用新键重新生成。
