# 预约可用性统一校验

日期：2026-10-29。入口：患者创建、患者改约、前台代预约。实现：`BookingAvailabilityService`，由 `PatientBookingService` 和 `BookingService` 共用。共享方法标注 `@Transactional(MANDATORY)`，必须由已开启事务的调用方进入；无事务直接调用会被 Spring 拒绝。

## 校验顺序

1. 锁前先做一次未来时段检查；取得 branch、schedule 行锁后重新取 `Clock`，再检查 `startsAt > now`。等待锁期间时段已过期则拒绝。
2. 锁 `branch` 行（与营业区间修改共用聚合锁），复查：
   - `branch.state='营业'`；
   - `branch.verified_at IS NOT NULL`。
3. 锁 `schedule` 行，复查：
   - `state='已发布'`；
   - 属于锁定门店；
   - `schedule_service` 存在该项目的正时长；
   - 完整 `[start,end)` 在排班区间内。
4. 医生：`staff_type='牙医'`、`employment_state='在岗'`。
5. `staff_branch`：`enabled=true` 且 `verified_at IS NOT NULL`。
6. 项目：`service_item.enabled`、`appointment_enabled`、`branch_service.enabled`、`dentist_service.enabled`、`schedule_service`。
   - 患者自助还要求 `branch_service.auto_bookable=true`；
   - 前台代约只要求门店服务已开放。
7. 资格：`credential_type='dentist_practice'`、`verification_state='verified'`，至少一条资格完整覆盖预约日期区间；`valid_to` 为空时必须有 `no_expiry_confirmed=true`。
8. `dentist_unavailable`：有效停诊记录与完整区间相交即拒绝。
9. 营业区间：同店 `branch_opening_interval` 并集必须完整覆盖 `[start,end)`；允许相邻区间，午休缺口拒绝。没有结构化营业区间时拒绝，不回退到 `opening_hours` 文本。
10. 写入预约后由 `ResourceAllocationService` 在门店 advisory lock 下分配可用牙椅，并排除有效维护记录；失败整笔事务回滚。

共享校验返回后、写入前，前台再次执行 `requireBranch`，患者再次执行 `requirePatient`，以覆盖等待锁期间已提交的撤权/会话失效。这里只覆盖等待期间已提交的撤权，不声称消除其他提交并发窗口。

## 锁与并发

- 创建：`branch` → `schedule` → 预约插入 → 资源 advisory lock。
- 改约：源/目标 `branch` 按 ID 升序 → 源/目标 `schedule` 按 ID 升序 → 源预约 `FOR UPDATE` → 新预约创建；共享校验在已持有这些锁的事务内重入。失败保留原预约和资源。
- 营业区间替换：`branch` → 读排班/预约 → 写区间；不锁预约行。
- 排班发布：`branch` → `schedule FOR UPDATE`。
- 签到：只锁预约行；签到前预约已确认，不在签到路径重新做完整候选校验，避免与改约争用同一套创建锁。
- 以上是本批实际路径，不笼统承诺所有模块已统一为单一全局锁协议；资格、任职、服务范围的并发修改也没有被读一次就宣称全面防竞态。


## 幂等与错误

- 预约创建/改约的 `Idempotency-Key` 语义保留：首次执行做完整可用性校验；同键重放只检查当前身份和可见性，返回当前预约视图，不因后来门店/资格变化重复创建或删除历史。
- 可用性失败返回既有 `SLOT_UNAVAILABLE`（409）；目标不存在以查询列表为空判断，不把 `EmptyResultDataAccessException` 之外的数据库连接/SQL/锁异常改写为业务 409。
- 非预期数据库异常交既有安全异常处理并回滚；不记录敏感原文，不自动重试。
- 失败不留下预约、资源占用、幂等结果或审计半成品；改约在新预约失败时回滚旧单状态和资源释放。
- 测试覆盖：等锁期间时钟推进、等锁期间撤权、无效排班 ID、数据库连接故障、无事务调用。

## 当前限制

- 本批不处理取消惩罚、收费、退款、库存、自动通知、政策版本校验。
- 本批未新增迁移。
- 本地旧数据若没有结构化营业区间、资格、门店服务/医生服务、排班项目，会按不可约拒绝；需要先配置完整业务数据。
- 未做微信开发者工具/真机验证；未改前端。


## 双端 availability 响应

`GET /me/booking` 和 `GET /staff/booking` 返回：

```json
{"availability":[{"schedule_id":"1","item_id":"1","slots":["2030-01-02T01:00:00Z"]}]}
```

- 由后端按已发布排班、营业区间并集、`schedule_service` 时长、资格、在岗/任职、服务范围、医生停诊和可用牙椅存在性生成候选；
- 患者自助执行 `auto_bookable=true` 约束，前台代约只检查门店服务已开放；
- 候选列表排除已知活动和资源占用，但**不预占**；提交时重新锁内校验，冲突返回 409，页面重新读取 availability 和预约列表；
- 不把前端算出的时段当作权威；无候选时显示不可约，不回退演示数据。


## 2026-10-30 待确认号源口径

活动占用统一为 `已确认/候诊中/接诊中`：

- `待确认` 不写 `resource_booking`，不进入患者/医生排他约束，不隐藏候选，也不阻止已确认预约创建；
- 两个 `待确认` 可指向同一时段；确认事务重新锁内校验，最多一个成功；
- 取消 `待确认` 不释放资源；
- 门店停用影响仍统计 `待确认` 作为未处理事项，但不称为号源占用；
- 数据库排他约束、`previewSlots` 的医生占用查询、`BookingService`/`PatientBookingService` 的提交冲突查询使用同一状态集合。

## 2026-10-29 定向修复：占用过滤与授权隔离

- 管理端 `GET /staff/booking` 的 `availability` 只遍历该账号已授权门店的已发布排班，SQL 中先按 `branch_id` 过滤，不依赖前端隐藏。
- 管理端患者列表不再全库返回，只返回对授权门店已有预约业务关联的患者；字段仍为预约操作所需的最小集合。没有历史业务关联的新患者目前无法从前台工作台直接选择，精确姓名+电话检索需要单独提出最小接口建议。
- `previewSlots` 在只读查询中批量读取并排除：
  - 该医生在任意门店的 `已确认/候诊中/接诊中` 预约区间重叠；
  - 患者自助候选还排除本人在任意医生、任意门店的上述活动预约重叠；前台尚未选择患者时不做这项过滤；
  - 全部可用牙椅都被有效维护记录（`starts_at < 候选结束`，不因预计结束时间自动恢复）或有效 `resource_booking` 占用。
- 仍不加写锁、不预占；提交时原事务校验才是最终结果。改约候选暂不忽略源预约，保守显示；如需排除源单，需要受控参数和本人校验设计。
- 前端保留后端原始 ISO 值作为选项值，显示标签才做时区格式化；提交不再截取 HH:mm 再拼日期。
- 患者端明确号源/版本 409 会重新读取 availability 和预约，保留冲突提示并重置失效选择；网络超时/5xx 不宣称创建失败，也不自动重发。
