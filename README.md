# ADC 口腔诊所系统

系统分析与设计课程项目。仓库包含课程报告、Spec Kit 规格、后端、网页管理端、患者微信小程序和部署模板。演示数据为虚构数据。

## 课程材料

- [最终课程报告](系统分析与设计文档/1602240113徐乐怡.docx)
- [报告图源（PlantUML）](系统分析与设计文档/牙科系统图源_PlantUML_20261125.zip)
- [项目约束](.specify/memory/constitution.md)
- [需求规格](specs/001-scheduling-booking/spec.md)、[数据模型](specs/001-scheduling-booking/data-model.md)、[接口契约](specs/001-scheduling-booking/contracts/api.md)

## 代码目录

| 目录 | 内容 |
| --- | --- |
| `backend/` | Java 21 / Spring Boot 服务、Flyway 迁移与测试 |
| `admin-web/` | Vue 3 管理端与测试 |
| `patient-miniapp/` | 原生微信小程序与测试 |
| `deploy/` | Docker Compose、镜像配置、环境变量示例与教学数据脚本 |

`deploy/production.env`、本地数据库、构建产物、运行日志和交接记录不提交。运行前按 `deploy/*.example` 配置环境变量；部署步骤见 [教学云部署说明](deploy/teacher-cloud-runbook.md)。规格文档包含目标设计，实际实现范围以代码及课程报告中的现状说明为准。
