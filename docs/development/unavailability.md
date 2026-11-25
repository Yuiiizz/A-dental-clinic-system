# 停诊与资源禁用：登记、受影响预约与人工解除

核对日期：2026-11-20。此页记录**已实现的真实接口和管理端页面**；自动通知、自动取消、自动替诊和自动释放占用仍未实现，不能把本页的“待人工协调”当成患者已收到通知。

## 接口和授权

### 医生停诊

- `POST /api/v1/staff/dentist-unavailability`：仅唯一经营主体负责人登记全局牙医停诊。停诊表没有 `branch_id`，门店负责人不能凭单店权限登记会影响其他门店的全局停诊。
- `GET /api/v1/staff/dentist-unavailability?state=有效|已撤销&limit=100`：仅唯一经营主体负责人读取记录和受影响预约摘要。
- `POST /api/v1/staff/dentist-unavailability/{id}/resolve`：仅唯一经营主体负责人；仅 `有效` 可解除，写 `resolved_at/resolved_by/resolve_basis`；不改预约、占用或通知状态。

### 资源维护/故障

- `POST /api/v1/staff/resource-unavailability`：要求该资源所属门店的 `branch.configure` 权限和当前员工在该店已核验、已启用的任职。
- `GET /api/v1/staff/resource-unavailability?branch_id=...&state=有效|已撤销&limit=100`：同上；只返回该门店记录。
- `POST /api/v1/staff/resource-unavailability/{id}/resolve`：同上；仅 `有效` 可解除，`resolved_by` 从当前会话取得，`resolved_at`、类型和依据成组保存，保留原 `recorded_by`。
- `GET /api/v1/staff/resources?branch_id=...`：只读资源下拉，补齐 OpenAPI 已声明但运行时缺失的列表；只返回当前账号在该店有 `branch.configure` 且任职已核验的资源，不改变资源状态。

### 幂等与冲突

四个变更接口都要求 `Idempotency-Key`。同键同内容不重复写入；不同内容 `409`；重放再次检查当前授权和目标可见性。已解除记录再次用新键解除返回 `409`。列表和资源下拉是只读接口，不使用幂等键。

## 数据与事务边界

`V023__dentist_unavailability_resolution.sql` 只给 `dentist_unavailable` 增加 `resolved_at/resolved_by/resolve_basis` 及成组完整性约束（不修改 V022 或旧迁移）；资源禁用沿用原有解除字段。约束使用 `NOT VALID`，避免已有“已撤销”记录缺少历史解除信息时阻断升级；迁移后新写入与更新仍须满足完整性约束，不为旧记录虚构解除依据。

登记事务在写入前按门店 ID 获取受影响门店行锁，资源禁用再锁目标资源行。预约创建先锁门店再查停诊/资源状态，因此登记提交后新预约读取有效禁用并拒绝。资源 `ends_at` 是预计结束，不自动解除；解除一条后，其他有效记录仍然阻止该资源新占用。医生停诊只阻止相交时段。

**既有预约的状态、版本和资源占用保持原样。** 列表中的 `affected_count/affected_appointments` 是实时只读摘要；不写 `coordination_state`，不自动取消、替诊、释放资源或发送通知。已撤销记录的受影响摘要为空。

受影响预约摘要只返回：

- 预约编号；
- 起止时间；
- 状态；
- 协调状态；
- 项目；
- 门店。

不返回患者姓名、电话或证件信息；需要患者身份时由前台在预约工作台按预约编号人工核对。

## 管理端页面

`/unavailability`「停诊与资源维护」：

- 医生停诊：唯一组织负责人可登记/解除；
- 诊椅/设备维护：本店配置负责人可登记/解除；
- 顶部显示有效停诊、有效资源禁用、受影响预约数；
- 每行展开受影响预约，明确标注“待人工协调，尚未通知”；
- 登记/解除后重新读取列表，不使用本地伪造状态；
- 请求结果不明时保留表单和同一幂等键；409 保留冲突信息并要求重新读取；
- 会话代次切换时清空旧列表、表单、弹窗和旧请求回调；
- 资源按门店读取，服务端继续校验跨店隔离。

## 验证

隔离 PostgreSQL + HTTP 测试覆盖：

- 唯一组织负责人/前台/其他门店负责人权限矩阵；
- 医生停诊登记 → 受影响预约摘要 → 解除 → 重新读取为已撤销、摘要为空；
- 资源维护登记 → 受影响预约摘要 → 资源下拉 → 解除；
- 预约状态、版本和 `resource_booking` 在登记/解除前后保持不变；
- 不产生通知/人工联系记录；
- 同幂等键重放；不同内容 `409`；重复解除 `409`；
- 跨店资源列表/资源下拉拒绝；
- 现有资源锁等待撤权回滚测试继续通过。

浏览器走查：负责人实际登记/解除医生停诊和资源维护，展开受影响预约；前台实际看到“停诊或设备影响 · 待人工协调，尚未通知”，签到按钮禁用；前台导航不显示管理入口，直接访问页面只得到服务端 403 提示。覆盖 1440、1366、760 宽视口，页面无横向溢出。

测试使用虚构账号、门店、患者和预约；未操作云端数据或预约 941，constitution/tasks 未改，未提交/推送或部署。设计 Word 已在 Codex 复核时同步第 7.8 节。

## 本批结果

- 后端当前源码：隔离 PostgreSQL 31 类 426 项通过，0 失败；含新增解除字段完整性约束回归。
- 管理端当前源码：Node 24 typecheck、29 个测试文件 269 项单测、生产构建通过。
- 浏览器：Edge 1440×900、1366×768、760×900；负责人实际登记/解除医生停诊和资源维护，受影响预约摘要可见；前台看到“停诊或设备影响 · 待人工协调，尚未通知”，受影响预约的签到按钮为 disabled；前台导航不显示管理入口，直接 URL 只得到服务端 403 提示。
- 登记/解除前后 `appointment` 状态和版本、`resource_booking` 有效状态保持不变，`contact_record` 为 0；没有自动取消、释放或通知。
- 截图：`docs/screenshots/unavailability-initial-1440.png`、`unavailability-doctor-affected-1440.png`、`unavailability-doctor-resolved-1440.png`、`unavailability-resource-affected-1440.png`、`unavailability-resource-resolved-1440.png`、`unavailability-frontdesk-alert-1440.png`、`unavailability-1366.png`、`unavailability-760.png`。
- 未操作云端数据或预约 941；旧迁移、constitution/tasks 未改；未提交、推送或部署。
