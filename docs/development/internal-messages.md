# 店内沟通（2026-11-09）

管理端 `/internal-messages` 是前台与同门店医生的真实内部文本消息页面。V017 新建 `internal_staff_message`，每条消息固定门店、前台、医生、发送账号、正文和时间；不支持编辑、删除、附件、在线状态或已读回执。页面每 12 秒检查新消息，也可手动刷新；网络结果不明时保留原文并使用同一幂等键手动重试。

仅 `frontdesk` 与 `dentist` 默认获得 `messages.exchange`。服务端逐次检查当前会话、角色与本店范围、双方有效已核实任职、营业门店及对方已激活账号。owner 和 sysadmin 不旁路。接口返回最小联系人与所选会话消息；跨店、撤权或非联系人均拒绝。发送通过 `operation_request` 幂等处理，同键同内容不重复写，同键异内容 409。

消息是工作沟通记录，**不能审批或改变预约、替诊、停诊、诊疗、收费等业务状态**。这些事项仍须进入对应业务页面按原权限和审计流程执行。界面建议用预约编号定位事项，不粘贴证件号或完整病历。教学虚拟数据中，员工账号在本地激活前不会出现在可沟通联系人中。

接口：`GET /staff/internal-messages/contacts`、`GET /staff/internal-messages?branch_id=&peer_staff_id=&cursor=&limit=`、`POST /staff/internal-messages`。后者需 `Idempotency-Key`、员工 Cookie 与 CSRF。
