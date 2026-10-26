# Tasks: 门店排班、预约与到店签到

**Input**: 本目录[spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[接口](contracts/api.md)、[权限](contracts/permissions.md)、[验收指南](quickstart.md)。
**Prerequisites**: 项目规则1.0.0，设计文件已存在；这是待执行任务，不表示已有应用代码。
**Tests**: spec明确要求并发、权限、边界和恢复验收，因此列出有意义的测试任务；先写关键测试并确认因目标行为缺失而失败，再实现并运行通过。
**Organization**: 共同基础后按US1～US6分组，最后整体验收。标准文件名tasks.md供Spec Kit识别，不另建重复task.md。

## Format 与路径

`- [ ] T编号 [P可选] [US编号可选] 动作与文件路径`。所有路径相对项目根目录。
[P]仅表示该阶段前置任务完成后，这对任务不修改相同文件，可并行；不是授权跳过依赖，也不自动启动多个代理。
未勾选表示待完成。仅生成本清单不创建其中的代码文件，不运行安装、迁移、部署或Git提交。
用户删除的图片保持现状。任务默认本地实施，不得自行推送GitHub。

## Phase 1: Setup — 项目与环境

**Goal**: 建立可重复构建的三个应用工程；当前仅列任务，执行时才创建。

**Independent Test / Checkpoint**: 后端、管理端、小程序各自完成最小构建，依赖版本固定。

- [x] T001 核对Java21 JDK、Node24.x且>=24.12、PostgreSQL18；新增工具优先D盘，记录实际版本和安装路径，不覆盖现有全局工具；文件：`docs/development/environment.md`。
- [x] T002 建立Java21/Spring Boot4.1.1与Maven Wrapper，选择MVC、Security、Validation、JDBC、Flyway和PostgreSQL驱动并锁版本；文件：`backend/pom.xml`、`backend/.mvn/wrapper/maven-wrapper.properties`。
- [x] T003 [P] 建立Vue3/TypeScript/Vite/Router/Pinia/Element Plus管理端及typecheck、test:unit、build、test:e2e命令，生成锁文件；文件：`admin-web/package.json`、`admin-web/package-lock.json`。
- [x] T004 [P] 建立原生TypeScript小程序工程及微信工具导入配置；不填写真实密钥，区分本地模拟与真实接入；文件：`patient-miniapp/project.config.json`、`patient-miniapp/miniprogram/app.ts`。
- [ ] T005 建立local/test/production配置及忽略规则；dev与test库不同，生产拒绝模拟身份与合成seed，日志屏蔽密钥；文件：`backend/src/main/resources/application.yml`、`.gitignore`、`deploy/env.example`。

## Phase 2: Foundational — 所有故事的前置能力

**Goal**: 先落实迁移、权限、身份和一致性基础，再执行任何业务故事。

**Independent Test / Checkpoint**: 真实PostgreSQL可迁移；身份、授权、审计、幂等和时间测试通过，两个前端能调用受保护接口。

- [ ] T006 将contracts/api.md具体化为OpenAPI，展开全部DTO、枚举、必填/可空/长度和错误响应；ID为字符串、limit默认20最大100，记录逐接口故事归属；文件：`specs/001-scheduling-booking/contracts/openapi.yaml`；对应：FR-001～FR-024。按以下子步骤拆分评审与产出：
  1. 身份与会话：/auth/*、/me/profile、/staff/accounts、/staff/accounts/{id}/role-scopes、disable（FR-003/021）。
  2. 基础配置与排班：/staff/branches、staff-members、service-items、resources、booking-config、opening-intervals、schedules、dentist-unavailability、resource-unavailability（含 resolve）（FR-001/002/013）。
  3. 预约：/public/*、/me/doctor-preferences、/me/followup-plans、/me/appointments、/staff/appointments（FR-004～009/024）。
  4. 协调：/me/changes、/staff/appointments/{id}/changes、/staff/changes、/staff/contacts（FR-012/014/023）。
  5. 签到：/staff/branches/{id}/checkin-code、/me/appointments/{id}/checkin、/staff/appointments/{id}/checkin、/staff/queue（FR-015/016/017）。
  6. 政策：/me/restrictions、/me/restrictions/{id}/appeal、/staff/booking-events、/staff/restrictions、/staff/booking-policies（FR-018/019/020）。
- [x] T007 建立真实PostgreSQL测试隔离、可控Clock和并发起跑工具；测试不得指向其他项目数据库，验证错误数据库配置会中止；文件：`backend/src/test/java/cn/dental/support/PostgresTestSupport.java`、`backend/src/test/java/cn/dental/support/ConcurrentRunner.java`。
- [x] T008 建立Flyway基础迁移：先建42张基线表后补FK解决循环依赖；采用data-model设计增补优先，保留“patient_id与staff_id恰好一个非空”和两项联合外键；完整逐字段原文见本文末迁移约束附件；文件：`backend/src/main/resources/db/migration/V001__baseline.sql`。
- [x] T009 编写增补迁移：按[字段约束原文](task-data-constraints.md)（已合并增补）落地六张辅助表及全部增补字段；“confirmed_dentist_id”用于医生排他约束，意向字段不再承担防重；新增排他约束（btree_gist + tstzrange(starts_at,ends_at,'[)')）、复诊活动预约与改约后继两个部分唯一索引、资源禁用 state/recorded_by/recorded_at/resolved_by/resolve 字段、七角色代码及政策草稿可空批准字段；文件：`backend/src/main/resources/db/migration/V002__runtime_constraints.sql`。
- [x] T010 验证空库及V001到V002升级；断言PK/FK/UQ/CHECK、btree_gist、排他约束以及复诊活动预约/改约后继两个部分唯一索引实际存在，旧意向医生排他已替换，辅助表结构和每字段约束符合附件；已有预约的确认医生按排班核实回填，无法核实时中止迁移并列异常，不能猜填；文件：`backend/src/test/java/cn/dental/migration/SchemaMigrationTest.java`。
- [x] T011 实现统一错误、分页、时区、字符串ID、请求追踪及敏感字段白名单；业务异常明确回滚，避免同类调用绕过事务代理；文件：`backend/src/main/java/cn/dental/shared/web/ApiExceptionHandler.java`、`backend/src/main/java/cn/dental/shared/config/ClockConfig.java`；对应：FR-021/022。
- [x] T012 实现账号仓储及可撤销会话：客户端staff_web/patient_miniapp隔离；每请求验证enabled及auth_version；员工Cookie+CSRF，小程序Bearer只存摘要；文件：`backend/src/main/java/cn/dental/identity/application/SessionService.java`、`backend/src/main/java/cn/dental/identity/config/SecurityConfig.java`；对应：FR-021。
- [ ] T013 实现员工登录、强制改密、登出、管理员一次性本地引导；无公网管理员注册或固定默认密码，禁止自提权及停用最后管理员；文件：`backend/src/main/java/cn/dental/identity/controller/StaffAuthController.java`、`backend/src/main/java/cn/dental/identity/application/BootstrapService.java`；对应：FR-021。
- [x] T014 实现角色与门店范围、账号管理、批准依据、撤权撤销会话；必须“存在某一角色同时具有本操作permission和目标branch scope”，不交叉拼接；文件：`backend/src/main/java/cn/dental/identity/application/AuthorizationService.java`、`backend/src/main/java/cn/dental/identity/controller/AccountAdminController.java`；对应：FR-021。
- [ ] T015 验证Cookie的CSRF、Bearer与注册上下文隔离、他人记录、跨门店、前台+库管组合、撤权即时生效和最后管理员保护；文件：`backend/src/test/java/cn/dental/identity/AuthorizationIntegrationTest.java`；对应：FR-021; SC-005。
- [x] T016 实现微信登录真实适配器和显式local mock；code仅由后端换身份；未绑定时产生注册上下文，正式环境禁止mock；文件：`backend/src/main/java/cn/dental/identity/application/WechatLoginService.java`、`backend/src/main/java/cn/dental/identity/integration/WechatIdentityClient.java`；对应：FR-003。
- [x] T017 实现本人建档、旧档绑定及前台凭证签发：上下文和绑定凭证“10分钟且最多5次失败”“至少128位随机熵”；成功原子消费，失败不消费但失败次数独立可靠记录；建档时 allergy_status 为有/不确定则 allergy_notes 必填；文件：`backend/src/main/java/cn/dental/identity/application/PatientBindingService.java`、`backend/src/main/java/cn/dental/identity/controller/PatientAuthController.java`；对应：FR-003。
- [ ] T018 验证共享电话不认领旧档、凭证过期/暴力尝试/重复消费/并发绑定；限速失败计数不能随业务回滚丢失；文件：`backend/src/test/java/cn/dental/identity/PatientBindingIntegrationTest.java`；对应：FR-003; SC-005。
- [x] T019 实现审计与最小必要摘要、统一幂等及固定业务锁顺序；“UNIQUE(actor_account_id,operation,request_key)”；同键不同摘要409，先授权再读重放结果；文件：`backend/src/main/java/cn/dental/shared/application/AuditService.java`、`backend/src/main/java/cn/dental/shared/application/IdempotencyService.java`、`backend/src/main/java/cn/dental/shared/repository/BusinessLockRepository.java`；对应：FR-008/022。
- [x] T020 实现前端HTTP客户端、登录失效处理和通用错误提示；管理端CSRF、小程序Bearer分别处理，不把敏感令牌写日志；文件：`admin-web/src/services/http.ts`、`patient-miniapp/miniprogram/services/http.ts`；对应：FR-021。
- [ ] T021 实现管理端登录和角色门店授权页面；切换门店重取权限，患者端建档/核验绑定页面明确本人业务；文件：`admin-web/src/pages/LoginPage.vue`、`admin-web/src/pages/AccountPermissionsPage.vue`、`patient-miniapp/miniprogram/pages/profile/index.ts`；对应：FR-003/021。
- [ ] T022 提供独立local合成数据初始化与最小默认政策：两门店、员工角色、20患者；临床fixture只读，不初始化真实病历；生产不可seed；文件：`backend/src/main/java/cn/dental/shared/bootstrap/LocalFixtureLoader.java`、`backend/src/test/resources/fixtures/booking-fixtures.json`。

## Phase 3: US1 — 发布可信排班（P1）

**Goal**: 负责人配置基础资料并发布可执行排班。

**Independent Test / Checkpoint**: 两门店同医生场景，合法班次能发布，重叠/资格/缓冲/营业冲突拒绝；公开只读页面可展示。

- [ ] T023 [US1] 先写排班发布契约和真实数据库集成用例：资格、任职、初复诊、服务时长、资源、跨店缓冲、营业区间、已发布改动影响；确认缺失功能时测试失败；文件：`backend/src/test/java/cn/dental/scheduling/SchedulePublicationTest.java`；对应：FR-001/002/007; SC-001。
- [ ] T024 [US1] 实现门店档案、核验及结构化营业区间；“起止有序、同店区间不得重叠”“跨午夜拆为两日”，有未来安排不能直接停用；文件：`backend/src/main/java/cn/dental/organization/application/BranchService.java`、`backend/src/main/java/cn/dental/organization/controller/BranchController.java`；对应：FR-001/002。
- [ ] T025 [US1] 实现员工资格、跨店任职及医生服务范围；verification_state仅pending/verified/rejected，no_expiry_confirmed默认false，空有效期不自动代表有效；文件：`backend/src/main/java/cn/dental/organization/application/StaffService.java`、`backend/src/main/java/cn/dental/organization/controller/StaffController.java`；对应：FR-002。
- [ ] T026 [US1] 实现项目类别、门店服务、资源类别及具体资源配置；价格快照与历史同时维护，轮转分钟非负；资产依赖只保留FK，不造假资产；文件：`backend/src/main/java/cn/dental/organization/application/CatalogService.java`、`backend/src/main/java/cn/dental/organization/controller/CatalogController.java`；对应：FR-002/005/007。
- [ ] T027 [US1] 实现班次模型、仓储和发布服务；采用“[开始,结束)”及两班较大交通缓冲；排班服务时长>0，医生/门店有效且资源数量足够；不直接换已约班次医生；文件：`backend/src/main/java/cn/dental/scheduling/domain/Schedule.java`、`backend/src/main/java/cn/dental/scheduling/application/SchedulePublicationService.java`、`backend/src/main/java/cn/dental/scheduling/repository/ScheduleRepository.java`；对应：FR-002/007。
- [ ] T028 [US1] 实现公开门店、医生名片、可约时段查询及排班发布接口；一次最多31日、无患者隐私，缓存结果不能替代提交时核验；文件：`backend/src/main/java/cn/dental/scheduling/controller/PublicAvailabilityController.java`、`backend/src/main/java/cn/dental/scheduling/controller/ScheduleController.java`；对应：FR-001/002。
- [ ] T029 [US1] 实现门店、营业区间、人员任职、资格、服务资源和排班配置工作台，错误定位到冲突安排而非只显示失败；文件：`admin-web/src/pages/BranchSettingsPage.vue`、`admin-web/src/pages/StaffSettingsPage.vue`、`admin-web/src/pages/CatalogSettingsPage.vue`、`admin-web/src/pages/SchedulePage.vue`；对应：FR-001/002。
- [ ] T030 [US1] 实现患者门店与医生排班浏览，显示地点、真实已发布班次与空号提示；仅展示，未登录也可浏览；文件：`patient-miniapp/miniprogram/pages/branches/index.ts`、`patient-miniapp/miniprogram/pages/doctors/index.ts`、`patient-miniapp/miniprogram/pages/slots/index.ts`；对应：FR-001。
- [ ] T031 [US1] 运行发布与公开查询独立验收，记录跨店冲突、无资格和不可用状态案例结果；不得仅验证页面渲染；文件：`admin-web/tests/e2e/schedule.spec.ts`、`docs/verification/us1.md`；对应：FR-001/002/007; SC-001。

## Phase 4: US2 — 找到医生并自主约成（P1）

**Goal**: 已认证患者自助约成，并保留熟悉医生和复诊关系。

**Independent Test / Checkpoint**: 使用预置已发布班次完成本人预约；20人争同一安排最多1人成功，相同请求10次只有1单。

- [ ] T032 [US2] 先写本人预约、同键改内容、复诊重复落实、资源与患者重叠、20并发抢号的契约和集成测试，失败后再实现；文件：`backend/src/test/java/cn/dental/booking/BookingConcurrencyTest.java`；对应：FR-003～FR-009/024; SC-002。
- [ ] T033 [US2] 实现患者资料修改、医生收藏和公开名片分享，source限定“本人选择、熟人推荐或既往就诊”；收藏不能变更主治，未申报过敏不能当无过敏；文件：`backend/src/main/java/cn/dental/booking/application/PatientPreferenceService.java`、`backend/src/main/java/cn/dental/booking/controller/PatientProfileController.java`；对应：FR-003/004。
- [ ] T034 [US2] 实现预约模型及占用仓储；“已确认、候诊中、接诊中、已完成必须非空”的确认医生等于schedule医生；一个复诊计划最多一笔有效预约（followup_plan_id 在 已确认/候诊中/接诊中 上的部分唯一索引兜底），按实际资源ID分配；本阶段每预约单一 item_id；文件：`backend/src/main/java/cn/dental/booking/domain/Appointment.java`、`backend/src/main/java/cn/dental/booking/repository/BookingRepository.java`；对应：FR-005/006/007/024。
- [ ] T035 [US2] 实现共享BookingService及只读PolicyEligibility查询：固定锁顺序后复核资质、门店、资源、限制与policy_id；正常直接确认，特殊事项待确认不占号；时长由服务端计算；文件：`backend/src/main/java/cn/dental/booking/application/BookingService.java`、`backend/src/main/java/cn/dental/policy/application/PolicyEligibilityService.java`；对应：FR-005～FR-009/018/020/024。
- [ ] T036 [US2] 实现本人预约创建/列表/凭证及复诊建议只读接口；白名单DTO、不接受客户端结束时间和确认医生；政策变更返回POLICY_CHANGED；文件：`backend/src/main/java/cn/dental/booking/controller/PatientBookingController.java`；对应：FR-006/008/009/024。
- [ ] T037 [US2] 实现患者预约确认、凭证、本人预约列表和收藏页面；超时以同键查询/重试，显示待确认而非伪成功；文件：`patient-miniapp/miniprogram/pages/booking/index.ts`、`patient-miniapp/miniprogram/pages/appointments/index.ts`、`patient-miniapp/miniprogram/pages/preferences/index.ts`；对应：FR-004/006/008/009。
- [ ] T038 [US2] 实现管理端只读预约列表，验证与患者看到同一记录；牙医仅自己接诊范围，前台仅授权门店；文件：`admin-web/src/pages/AppointmentsPage.vue`、`backend/src/main/java/cn/dental/booking/controller/StaffAppointmentQueryController.java`；对应：FR-009/021。
- [ ] T039 [US2] 运行US2独立验收与权限反例，记录重复请求、并发冲突、待确认无占用及收藏隐私；记录真实执行环境；文件：`docs/verification/us2.md`、`patient-miniapp/tests/booking.test.ts`；对应：FR-003～FR-009/024; SC-002/005。

## Phase 5: US3 — 取消和改约（P1）

**Goal**: 释放取消名额，改约成功占新放旧，失败不丢旧号。

**Independent Test / Checkpoint**: 预置预约与候诊fixture即可测试，无须等签到页面；20次改约失败原安排保留。

- [ ] T040 [US3] 先写取消/改约竞争测试：取消先完成、签到先完成、接诊先完成及新旧时段重叠；改约失败全回滚，已候诊退出而非未到；文件：`backend/src/test/java/cn/dental/booking/RescheduleIntegrationTest.java`；对应：FR-010/011/017/024; SC-003。
- [ ] T041 [US3] 实现取消事务和履约事实记录；“开始接诊后不可取消预约”，关闭队列及占用、复诊回待协调；同一原预约只记一个待核实事件；文件：`backend/src/main/java/cn/dental/booking/application/CancellationService.java`；对应：FR-010/017/019/024。
- [ ] T042 [US3] 实现改约事务：旧单已改约、新单已确认、previous_id不成环且最多一个成功后继（previous_id 加 confirmed_at 非空的部分唯一索引兜底，未确认候选不受限）；失败保留原单；新单不继承签到；受限患者可取消但不能自助改约新时段；文件：`backend/src/main/java/cn/dental/booking/application/RescheduleService.java`；对应：FR-011/020/024。
- [ ] T043 [US3] 补患者取消/改约接口及前端流程，必填原因与expected_version；先展示规则，改约冲突给原凭证与替代查询入口；文件：`backend/src/main/java/cn/dental/booking/controller/PatientBookingController.java`、`patient-miniapp/miniprogram/pages/appointment-change/index.ts`；对应：FR-010/011/020。
- [ ] T044 [US3] 运行20次失败保留演练、资源释放、前后关联和陈旧版本检查，记录已停诊原单仍待协调的结果；文件：`docs/verification/us3.md`、`patient-miniapp/tests/reschedule.test.ts`；对应：FR-010/011; SC-003。

## Phase 6: US4 — 前台代办与停诊协调（P1）

**Goal**: 保留人工接待与真实患者答复，复用同一预约服务。

**Independent Test / Checkpoint**: 使用现有患者和班次演练电话预约、停诊、拒绝/同意替诊、落实冲突和通知故障。

- [ ] T045 [US4] 先写代办、停诊与并发提交、候选无占位、答复非送达、落实冲突和发送后宕机的集成测试；文件：`backend/src/test/java/cn/dental/coordination/CoordinationIntegrationTest.java`；对应：FR-012/013/014/023; SC-006。
- [ ] T046 [US4] 实现前台最小患者检索、核验建档、代办及待确认落实；检索不跨组织，代办标电话/微信登记/现场，仍调用BookingService；人工渠道（电话/微信登记/现场）代办与确认时 consent_basis 必填；文件：`backend/src/main/java/cn/dental/coordination/application/ReceptionService.java`、`backend/src/main/java/cn/dental/coordination/controller/ReceptionController.java`；对应：FR-012/021。
- [ ] T047 [US4] 实现医生停诊和资源禁用：先短事务关闭入口，再逐患者协调；动态读取不可用状态，不等后台标记才阻止新预约；不批量取消或覆盖历史；资源恢复须人工核实（resolve_type=误登记撤销/已修复），成组记录 resolved_by/resolved_at/resolve_type/resolve_basis，resolved_by 取当前会话且不覆盖原登记人；解除一条后仍检查其他有效禁用记录，不因预计结束时间已过自动恢复；文件：`backend/src/main/java/cn/dental/coordination/application/UnavailabilityService.java`、`backend/src/main/java/cn/dental/coordination/controller/UnavailabilityController.java`；对应：FR-013。
- [ ] T048 [US4] 实现方案拟定、患者/前台记录答复和落实：同意与落实分开，状态流转 待答复→已同意/已拒绝/已撤回→已落实，“已拒绝、已撤回不可再落实”，新资源冲突保留原记录和协调事实；文件：`backend/src/main/java/cn/dental/coordination/application/ChangeProposalService.java`、`backend/src/main/java/cn/dental/coordination/controller/ChangeProposalController.java`；对应：FR-014。
- [ ] T049 [US4] 实现联系记录、提交后通知worker和渠道适配器；dispatch_key唯一、租约恢复、attempt_count非负；未知结果不伪送达，不承诺恰好一次，失败电话跟进；文件：`backend/src/main/java/cn/dental/coordination/application/ContactService.java`、`backend/src/main/java/cn/dental/coordination/jobs/NotificationWorker.java`；对应：FR-022/023。
- [ ] T050 [P] [US4] 实现前台代办和待联系工作台，区分方案、送达、答复及落实；显示操作人和理由，正常自助预约不加入审批队列；文件：`admin-web/src/pages/ReceptionPage.vue`、`admin-web/src/pages/CoordinationPage.vue`；对应：FR-012/013/014/023。
- [ ] T051 [P] [US4] 实现患者停诊提示和方案同意/拒绝页面；显示明确门店、替诊人和日期，接受后展示落实结果而非一律成功；文件：`patient-miniapp/miniprogram/pages/change-proposal/index.ts`；对应：FR-013/014。
- [ ] T052 [US4] 运行US4验收及重启恢复，证明通知失败不回滚已确认预约、未答复不改医生、失败落实不占号；验证资源恢复留痕（resolved_by/resolved_at/resolve_type/resolve_basis）且解除一条后其他有效禁用记录仍阻止可约；文件：`docs/verification/us4.md`、`admin-web/tests/e2e/coordination.spec.ts`；对应：FR-012/013/014/023; SC-006。

## Phase 7: US5 — 双入口签到候诊（P1）

**Goal**: 自助减少重复办理，前台仍能协助，两个入口共用一个候诊号。

**Independent Test / Checkpoint**: 预置确认预约，扫码和人工同时签到10次仅一号；边界和错误门店均可验证。

- [ ] T053 [US5] 先写签到边界和20并发/重复测试：正确/错误门店、过期码、取消旧单、待协调、时窗外以及签到后取消；控制Clock不改系统时间；文件：`backend/src/test/java/cn/dental/checkin/CheckinConcurrencyTest.java`；对应：FR-015/016/017; SC-004。
- [ ] T054 [US5] 实现门店签到设置和60秒轮换签名码；“self_checkin_enabled bool默认false”，配置不足禁自助；窗口为“[start-early,min(start+late,end))”；文件：`backend/src/main/java/cn/dental/checkin/application/CheckinConfigurationService.java`、`backend/src/main/java/cn/dental/checkin/application/CheckinTokenService.java`；对应：FR-015。
- [ ] T055 [US5] 实现共享签到事务：patient等业务锁后才锁queue_counter，原子递增；“预约唯一”和“(branch_id,queue_date,queue_no)唯一”；重复返回原号，门店归属匹配；文件：`backend/src/main/java/cn/dental/checkin/application/CheckinService.java`、`backend/src/main/java/cn/dental/checkin/repository/QueueRepository.java`；对应：FR-015/016/017。
- [ ] T056 [US5] 实现小程序扫码、人工签到、当日候诊最小查询及签到码接口；人工超窗须核验理由，不能强签已取消或停诊单；文件：`backend/src/main/java/cn/dental/checkin/controller/CheckinController.java`；对应：FR-015/016/017。
- [ ] T057 [P] [US5] 实现小程序扫码和候诊凭证页面，网络失败转前台并保留查询入口，明确扫码不代表已治疗；文件：`patient-miniapp/miniprogram/pages/checkin/index.ts`；对应：FR-015/016。
- [ ] T058 [P] [US5] 实现前台签到、候诊列表、门店签到码展示和负责人时窗设置页面，避免公开屏泄露患者身份信息；文件：`admin-web/src/pages/CheckinPage.vue`、`admin-web/src/pages/CheckinSettingsPage.vue`；对应：FR-015/016/021。
- [ ] T059 [US5] 运行双入口重复、号码不回收及取消退出验收；验证10次重试同号，合成过号状态/已签到不能记未到；文件：`docs/verification/us5.md`、`admin-web/tests/e2e/checkin.spec.ts`；对应：FR-015/016/017; SC-004。

## Phase 8: US6 — 履约复核与申诉（P2）

**Goal**: 经批准才启用限制，完整记录理由、豁免和到期。

**Independent Test / Checkpoint**: 合成事件覆盖24小时整点、7天窗口、政策混合、已用事件、申诉和默认不启用。

- [ ] T060 [US6] 先写受控时钟测试：24小时整点不计临近、窗口左开右闭、未到须end已过且未签到、同店同政策、到期/豁免重算与旧事件不再用；文件：`backend/src/test/java/cn/dental/policy/RestrictionPolicyTest.java`；对应：FR-017/018/019/020; SC-006。
- [ ] T061 [US6] 实现政策草稿与批准发布：status限draft/published/retired，restriction_enabled默认false；approved_by/approved_at/effective_at 草稿可空、发布必填；“UNIQUE(branch_id,version_no)”，已发布参数不可原地改变；文件：`backend/src/main/java/cn/dental/policy/application/BookingPolicyService.java`、`backend/src/main/java/cn/dental/policy/controller/BookingPolicyController.java`；对应：FR-018。
- [ ] T062 [US6] 实现未到核实、事件复核、申诉及豁免；记录reviewed_by与reviewed_at，事件“appointment_id唯一”；取消或改约同原单不重复计数，诊所/已签到/紧急豁免；文件：`backend/src/main/java/cn/dental/policy/application/EventReviewService.java`、`backend/src/main/java/cn/dental/policy/controller/EventReviewController.java`；对应：FR-017/019/020。
- [ ] T063 [US6] 实现限制计算与到期：患者锁内按复核T取(T-window,T]，restriction_event.event_id唯一保存全部触发证据；同患者同店最多一条生效；不叠加延长，豁免撤销后旧事件不重复使用；文件：`backend/src/main/java/cn/dental/policy/application/RestrictionService.java`、`backend/src/main/java/cn/dental/policy/jobs/RestrictionExpiryJob.java`；对应：FR-018/019/020。
- [ ] T064 [US6] 实现本人限制与申诉接口和联系待办；同限制未结申诉不重复建；取消/查询不受限，必要就医保留人工入口，和BookingService资格查询联调；文件：`backend/src/main/java/cn/dental/policy/controller/PatientRestrictionController.java`、`backend/src/main/java/cn/dental/policy/application/AppealService.java`；对应：FR-019/020。
- [ ] T065 [P] [US6] 实现负责人政策配置及前台复核申诉页面，默认不开限制，展示证据和待办；启用前明确批准和公开内容；文件：`admin-web/src/pages/BookingPolicyPage.vue`、`admin-web/src/pages/RestrictionReviewPage.vue`；对应：FR-018/019。
- [ ] T066 [P] [US6] 实现患者限制说明和申诉页面，展示依据、到期及人工联系，不出现自动罚款或付费解禁按钮；文件：`patient-miniapp/miniprogram/pages/restrictions/index.ts`；对应：FR-020。
- [ ] T067 [US6] 运行US6完整验收并回归US2、US3：启用前不限制，启用后限新预约但可取消，受限改约转人工，到期不重复触发；文件：`docs/verification/us6.md`、`admin-web/tests/e2e/policy.spec.ts`；对应：FR-017/018/019/020; SC-006。

## Phase 9: Polish & Cross-Cutting — 集成、性能和部署准备

**Goal**: 证明整条第一阶段流程正确，记录真实外部条件和限制。

**Independent Test / Checkpoint**: 验收与报告逐项对应；无法进行的真机/生产检查标为未完成，不假装通过。

- [ ] T068 验证OpenAPI与实现所有路由/DTO/错误一致，逐项覆盖24条FR；测试并发、回滚和安全失败不得被mock代替；文件：`backend/src/test/java/cn/dental/contract/ApiContractTest.java`、`docs/verification/requirements-matrix.md`；对应：FR-001～FR-024。
- [ ] T069 执行查询20并发性能场景，记录p95、错误率及数据量；95%在3秒内且正常查询不以错误凑响应，调整索引后复测；文件：`backend/src/test/java/cn/dental/performance/AvailabilityLoadTest.java`、`docs/verification/performance.md`；对应：SC-007。
- [ ] T070 执行从配置、发布、本人预约、前台协调、改约到签到的双端联调；按quickstart每条步骤记录结果，截图仅合成数据，不恢复旧图片合集；文件：`docs/verification/end-to-end.md`；对应：SC-001～SC-006。
- [ ] T071 准备Docker Compose、API镜像与HTTPS反向代理配置；仅内部数据库、独立迁移账号、生产禁mock；不购服务器或实际对外发布；文件：`deploy/compose.yaml`、`deploy/backend.Dockerfile`、`deploy/reverse-proxy.conf`。
- [ ] T072 在隔离环境演练空库/版本升级、备份恢复、日志脱敏和密钥注入，记录步骤；不能声称Flyway自动安全降级；文件：`docs/verification/migration-restore.md`、`deploy/README.md`。
- [ ] T073 取得可用AppID和HTTPS条件后才做真实小程序登录/域名/扫码/授权验收；缺外部条件则此任务保持未完成并记录具体原因；文件：`docs/verification/wechat-real-device.md`。
- [ ] T074 完成管理端typecheck、单测、构建、浏览器验收及后端verify，更新真实运行命令和未完成项；任务只有证据存在才勾选；文件：`specs/001-scheduling-booking/quickstart.md`、`docs/verification/final-report.md`。

## Dependencies & Execution Order

Phase1 → Phase2 → US1 → US2 → US3 → US4 → US5 → US6 → 整体验收。
阶段内按任务顺序，只有标[P]的相邻前端任务可以在本组服务与契约完成后同时进行。
US2依赖US1发布服务；US3依赖US2共同确认服务；US4复用US2/US3，不能另造人工冲突逻辑。
US5依赖US3退出队列逻辑和US4不可用状态；US6依赖US3的履约事件及US4联系待办。
同一份PatientBookingController、数据库迁移及共享服务由前一阶段先完成，后续顺序扩展，不能并行覆盖。
US3可用候诊fixture独立验证，US6可用已核实事件fixture；独立可测试不等于不需要共同基础。

## Parallel Examples

- US1：本组任务顺序执行，基础资料与班次发布有依赖，不标虚假的并行项。
- US2：核心确认、资源分配和政策查询有依赖，先顺序打通再验收。
- US3：取消与改约共享旧单、队列和资源，顺序实现。
- US4：方案服务完成后，前台协调页与患者答复页可并行。
- US5：签到接口完成后，小程序扫码页与管理端签到页可并行。
- US6：限制服务完成后，政策复核页与患者限制页可并行。

## Implementation Strategy

第一可演示增量是共同基础+US1：配置真实可执行排班并公开查看。
第一个预约业务MVP是再加入US2；预约—取消改约—前台协调—签到的完整第一阶段还必须完成US3～US5。
US6依规格完成，但自动限制默认关闭。全部故事完成及集成验收后才称第一阶段完成，不把US1演示说成完整系统。
每一阶段验证通过后才推进；代码失败、外部条件不足和真实测试结果写入对应verification文件。
本轮仅生成任务；下一步先做Spec Kit一致性分析，不能直接跳过关键遗漏开始批量实现。

## 数据约束附件（迁移任务的组成部分）

迁移与DTO任务必须逐项实施下列data-model原文字段定义和约束；新增定义优先于基线。
为避免在每个故事中重复数百个字段，迁移任务共用[字段约束原文](task-data-constraints.md)。
该附件包含基线表的每个字段类型、可空性、键及原约束，还有增补全文；不授权开发者自行替换枚举或放宽长度。
若原文尚未明确必须落地的长度或并发版本，先在OpenAPI/迁移设计记录具体选值及理由，再同步模型；不隐藏更改。

## 需求到任务对应

| 需求 | 实现/验证任务 |
| --- | --- |
| FR-001 | T006, T023, T024, T028, T029, T030, T031, T068 |
| FR-002 | T006, T023, T024, T025, T026, T027, T028, T029, T031, T068 |
| FR-003 | T006, T016, T017, T018, T021, T032, T033, T039, T068 |
| FR-004 | T006, T032, T033, T037, T039, T068 |
| FR-005 | T006, T026, T032, T034, T035, T039, T068 |
| FR-006 | T006, T032, T034, T035, T036, T037, T039, T068 |
| FR-007 | T006, T023, T026, T027, T031, T032, T034, T035, T039, T068 |
| FR-008 | T006, T019, T032, T035, T036, T037, T039, T068 |
| FR-009 | T006, T032, T035, T036, T037, T038, T039, T068 |
| FR-010 | T006, T040, T041, T043, T044, T068 |
| FR-011 | T006, T040, T042, T043, T044, T068 |
| FR-012 | T006, T045, T046, T050, T052, T068 |
| FR-013 | T006, T045, T047, T050, T051, T052, T068 |
| FR-014 | T006, T045, T048, T050, T051, T052, T068 |
| FR-015 | T006, T053, T054, T055, T056, T057, T058, T059, T068 |
| FR-016 | T006, T053, T055, T056, T057, T058, T059, T068 |
| FR-017 | T006, T040, T041, T053, T055, T056, T059, T060, T062, T067, T068 |
| FR-018 | T006, T035, T060, T061, T063, T065, T067, T068 |
| FR-019 | T006, T041, T060, T062, T063, T064, T065, T067, T068 |
| FR-020 | T006, T035, T042, T043, T060, T062, T063, T064, T066, T067, T068 |
| FR-021 | T006, T011, T012, T013, T014, T015, T020, T021, T038, T046, T058, T068 |
| FR-022 | T006, T011, T019, T049, T068 |
| FR-023 | T006, T045, T049, T050, T052, T068 |
| FR-024 | T006, T032, T034, T035, T036, T039, T040, T041, T042, T068 |

## 任务统计

- Phase 1: Setup — 项目与环境: 5项。
- Phase 2: Foundational — 所有故事的前置能力: 17项。
- Phase 3: US1 — 发布可信排班（P1）: 9项。
- Phase 4: US2 — 找到医生并自主约成（P1）: 8项。
- Phase 5: US3 — 取消和改约（P1）: 5项。
- Phase 6: US4 — 前台代办与停诊协调（P1）: 8项。
- Phase 7: US5 — 双入口签到候诊（P1）: 7项。
- Phase 8: US6 — 履约复核与申诉（P2）: 8项。
- Phase 9: Polish & Cross-Cutting — 集成、性能和部署准备: 7项。

合计74项，全部待执行。
