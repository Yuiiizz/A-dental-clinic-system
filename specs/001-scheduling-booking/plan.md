# Implementation Plan: 门店排班、预约与到店签到

**Branch**: 当前Git分支main，未新建分支 | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

**Input**: `specs/001-scheduling-booking/spec.md`；项目规则1.0.0。
Spec Kit返回的逻辑功能名001-scheduling-booking不等于实际Git分支。

## Summary

以Java/Spring Boot模块化单体实现统一预约服务，患者原生小程序和Vue管理端分别接入。
第一阶段覆盖基础档案授权、排班、预约改约、前台协调、签到及履约复核；不交付诊疗和收费模块。
数据库沿用报告的关系模型并明确补充运行配置、确认医生、凭证、幂等和政策审计结构。
本次仅生成设计文档，没有创建代码框架、迁移或安装开发依赖。

## Technical Context

**Language/Version**: Java21；TypeScript；SQL。后端采用Spring Boot4.1.1，依赖由BOM和锁文件固定。

**Primary Dependencies**: Spring MVC/Security/Validation/JDBC，Flyway及PostgreSQL驱动；Vue3/Vite/Router/Pinia/Element Plus；原生微信小程序。

**Storage**: PostgreSQL18，timestamptz保存时刻，Asia/Shanghai展示及计算营业日；金额后续用numeric/BigDecimal。

**Testing**: JUnit、Spring测试、真实PostgreSQL集成测试；Vitest与Playwright；微信开发者工具及真机验收。禁止用H2通过代替数据库并发验证。

**Target Platform**: Windows本地开发，Linux容器部署；员工桌面浏览器与微信患者端。

**Project Type**: 两个前端与一个模块化单体服务，统一版本仓库。

**Performance Goals**: spec SC-001至SC-007，20并发用户演示下95%排班查询3秒内有结果或明确错误；100%越权和竞争案例符合约束。

**Constraints**: 前台保留；患者本人业务；无自动罚款；政策默认不启用；无需Redis或微服务。真实微信能力与模拟明确分离。

**Scale/Scope**: 首期两门店合成验收；数据模型按业务依赖子集迁移，不按页面数量猜表数。生产容量须另压测。

## Constitution Check

| 规则 | 研究前检查 | 设计后检查 |
| --- | --- | --- |
| 事实与规格 | 使用最新Word及已审规格 | 新参数和模型修正明确标注，没有改写调研事实 |
| 双端与权限 | 独立入口、统一服务 | 每接口明确角色门店及本人范围，会话可撤销 |
| 前台与连续性 | 正常自助不审批 | 人工代办、停诊同意、失败保留和联系记录完整 |
| 一致性与历史 | 采用事务及关系约束 | 确认医生和意向分开；状态、幂等、政策计次有明确设计 |
| 验证交付 | 不将审阅当测试 | quickstart区分未来命令与已执行检查，真实接入另设门槛 |

结论：本阶段设计符合规则，无需要豁免的违反项。审核通过不表示实现已通过测试。

## Project Structure

### Documentation (this feature)

```text
specs/001-scheduling-booking/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
├── task-data-constraints.md
├── contracts/
│   ├── api.md
│   └── permissions.md
└── checklists/requirements.md
```

### Source Code (repository root)

以下为未来代码布局，当前没有创建这些目录。

```text
backend/
  src/main/java/cn/dental/
    identity/ organization/ scheduling/ booking/ coordination/ checkin/ policy/
    shared/
  src/main/resources/db/migration/
  src/test/
admin-web/src/
  pages/ components/ services/ stores/
patient-miniapp/
  miniprogram/pages/ miniprogram/services/ miniprogram/components/
deploy/
```

**Structure Decision**: 每个后端业务模块包含controller、application、domain和repository职责，不创建不必要的独立微服务。Controller只做协议及输入转换，Application负责权限、事务与用例，Domain表达规则，Repository执行SQL。
同一BookingService供患者端与前台调用，不能写两套冲突校验。DTO不直接返回数据库整行或密码、身份凭证。

## 实施顺序与验收边界

1. 固定构建环境与数据库迁移，建立合成数据和身份授权。
2. 完成最小门店、人员任职、服务、资源及政策配置页面；默认政策无自动限制。
3. 发布排班并提供患者查询、收藏和本人预约。
4. 实现取消改约、前台代办、停诊协调及通知恢复。
5. 打通扫码和人工签到；最后加入履约复核、申诉与限制重算。
6. 执行契约、越权、并发、失败恢复和双端验收；再准备部署演练。

依赖不能因故事优先级被省略。第一阶段不新建临床复诊建议，不提供收款或诊疗写入口。
技术设计及验收路径见[data-model.md](data-model.md)、[接口](contracts/api.md)、[权限](contracts/permissions.md)与[quickstart](quickstart.md)。

## 迁移与部署约束

Flyway负责版本迁移；独立迁移账号可执行结构变更，应用账号无DDL权限。
先空库迁移，再测试上一版本升级；重要变更先备份，以前向修复或备份恢复退回，不能假设Flyway自动提供业务安全降级。
真实患者数据不入测试库。应用环境、日志和容器密钥不提交仓库。
生产仅开放HTTPS代理；数据库内部可达。备份加密并演练恢复后才可称具备部署准备条件。
资源配置和身份权限不存在“仅前端校验”的例外。

## Complexity Tracking

无规则豁免。额外凭证、幂等和配置结构均服务于明确验收条件；新增关系详见数据模型，未以更多表作为复杂度目标。
