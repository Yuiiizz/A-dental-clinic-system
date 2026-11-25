# 治疗计划（真实）

日期：2026-10-28。治疗计划是临床建议，不是已实施治疗，也不会自动生成账单。

## 已实现

- `treatment_plan`：患者、门店、主治医生、来源就诊、诊断、计划名称、状态、预估总额、版本、患者接受时间/依据。
- `treatment_plan_item`：目录项目、名称、牙位、阶段、数量、单价、金额、执行状态、排序。
- 计划状态：草稿 → 待患者确认 → 已接受 / 已拒绝；所有项目完成/取消后 → 已完成。
- 项目状态：计划 / 进行中 / 已完成 / 已取消。

## 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/v1/staff/treatment-plans` | 计划、已完成就诊、目录项目 |
| GET | `/api/v1/staff/treatment-plans/list` | 计划列表 |
| POST | `/api/v1/staff/treatment-plans` | 创建草稿 |
| POST | `/api/v1/staff/treatment-plans/{id}/submit` | 提交患者确认 |
| POST | `/api/v1/staff/treatment-plans/{id}/items/{itemId}/status` | 更新项目进度 |
| GET | `/api/v1/me/treatment-plans` | 患者查看本人计划 |
| POST | `/api/v1/me/treatment-plans/{id}/accept` | 患者接受 |
| POST | `/api/v1/me/treatment-plans/{id}/reject` | 患者拒绝 |

## 规则

- 只能从已完成就诊创建。
- 患者接受必须记录接受依据和时间；拒绝记录原因。
- 只有已接受/已完成的计划可以更新项目进度。
- 金额为整数分，不等于已收费；完成项目不自动写账单。
- 管理端页面：`/treatment-plans`。

## 尚未完成

- 患者端治疗计划页面已接入真实记录页“治疗计划”标签，可接受/拒绝；未在微信开发者工具/真机运行。
- 治疗计划与账单已关联：`bill.plan_id`，一个计划最多一张账单；患者接受后才能生成。
- 修改计划、重新版本、终止计划、审计事件。
- OpenAPI 和治疗计划后端隔离集成测试。
- 税务/正式发票仍只有人工票据占位，尚未扩展税号、税率、红冲作废。


## 计划生成账单

`POST /api/v1/staff/treatment-plans/{id}/bill` 仅允许已接受/已完成计划，复制未取消项目生成账单，并写 `bill.plan_id`。重复生成由唯一索引阻止。
