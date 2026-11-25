# 预约接口差距（2026-10-29）
> 历史快照：本文件以下“仍须实现”描述的是 2026-10-29 当时的差距，不代表当前完成状态。当前预约协调实现与限制以 `docs/development/appointment-change.md`、实际源码和最新测试为准。
本轮合并同一路径的重复GET/POST定义，保留原operationId。当前运行契约按BookingController对齐，不代表批准删减设计。
仍须实现：创建预约Idempotency-Key、人工渠道consent_basis核验与留存、列表分页及按授权branch_id过滤；政策来源须进一步对照既定规则。分页缺失不是最终设计。
原规划块保留如下，供后续补齐，不能以删除重复YAML代替完成需求。
```yaml
  /staff/appointments:
    get:
      tags: [公开查询与患者预约]
      operationId: staffListAppointments
      summary: 员工查询预约
      description: 前台/本人医生按范围过滤。
      security:
        - {StaffSessionCookie: [], StaffCsrf: []}
      parameters:
        - name: branch_id
          in: query
          schema:
            type: string
        - name: date
          in: query
          schema:
            type: string
            format: date
        - $ref: '#/components/parameters/Cursor'
        - $ref: '#/components/parameters/Limit'
      responses:
        '200':
          description: 预约分页
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AppointmentPage'
    post:
      tags: [协调]
      operationId: staffCreateAppointment
      summary: 前台代办预约
      description: 前台代办，同一校验，不绕过资源规则；人工渠道（电话/微信登记/现场）consent_basis 必填。幂等键必填。
      security:
        - {StaffSessionCookie: [], StaffCsrf: []}
      parameters:
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [patient_id, schedule_id, item_id, starts_at, visit_type, policy_id, channel, consent_basis]
              properties:
                patient_id:
                  type: string
                schedule_id:
                  type: string
                item_id:
                  type: string
                starts_at:
                  type: string
                  format: date-time
                visit_type:
                  $ref: '#/components/schemas/VisitType'
                policy_id:
                  type: string
                channel:
                  $ref: '#/components/schemas/Channel'
                consent_basis:
                  type: string
                  maxLength: 300
                special_needs:
                  type: string
                  maxLength: 300
      responses:
        '200':
          description: 预约结果
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AppointmentResult'

```


## 前台代约幂等补齐（2026-10-29）
POST /staff/appointments 必须传 Idempotency-Key 和 consent_basis。范围为账号+操作+键，同键同内容不重复预约，同键不同内容409；重放仍检查当前授权及预约可见性，返回当前预约视图。前端保留结果未知请求键，不自动重发；患者确认依据显式填写，复用 appointment.consent_basis。
仅本批前台代约接通，患者端预约/改约幂等不能据此宣布完成；分页、完整复诊关联仍待补齐。Word尚未同步。


## 2026-10-29 患者改约历史与防重复
改约请求增加必填 expected_version、reason 和 Idempotency-Key。旧预约转为已改约，原时间、医生、项目及原资源历史保留；新预约 previous_id 指向旧单，继承复诊计划、目的、就诊类型及特殊需求。旧单退出活动、释放资源、新单创建、资源竞争和审计在同一事务中，失败整体回滚。同一幂等键重放返回当前 original/replacement，两者从一条SQL取得；新请求旧版本返回409。复诊计划不因改约完成。

本批改约先按ID顺序锁涉及门店、排班，再锁旧预约；仍需统一前台创建、签到与其他写入路径的整体锁协议，不能声称已消除全部跨模块死锁。政策版本校验（原契约policy_id）、完整资格与营业区间等业务校验尚未补齐，不因本次契约对齐而取消需求。小程序未做开发者工具/真机验证；Word另行同步。
