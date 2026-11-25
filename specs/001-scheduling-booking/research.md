# 技术决策记录

日期：2026-10-21。结论是本项目的选型判断，不代表依赖安装和应用测试已经通过。

## 01 后端采用 Java 21 与 Spring Boot

**Decision**：采用 Java 21、Spring Boot 4.1.1、Maven Wrapper；以模块化单体提供共同业务服务。采用 Spring MVC、Validation、Security、JDBC 和 Flyway。
**Rationale**：用户反馈老师提到 Java/Spring Boot；Java 类、服务和对象更容易对应课堂设计。现有机器已有 Java21，业务需要明确事务，规模不需要微服务。
**Alternatives considered**：FastAPI可以完成且Python环境现成，但课堂指导适配更重要；NestJS可统一TypeScript，但没有足够理由再引入一套后端框架。
**Evidence**：[Spring Boot系统要求](https://docs.spring.io/spring-boot/system-requirements.html)列明4.1.1支持Java17至26，Java21在范围内；第三方依赖仍须开发时解析验证。依赖由Boot BOM管理，Maven Wrapper锁版本，禁止动态latest。

## 02 两个独立前端

**Decision**：患者原生微信小程序（TypeScript、WXML、WXSS）；管理端Vue3、TypeScript、Vite、Vue Router、Pinia，表单组件采用Element Plus并在首次构建锁定版本。
**Rationale**：患者入口需要微信扫码及登录，管理端需要桌面表格和排班工作台；独立页面，共享接口契约，不复制预约规则。
**Alternatives considered**：uni-app/Taro适合跨平台需求，本项目仅明确微信，无须额外编译层；只做H5不能声称已交付小程序。
**Evidence**：[Vue快速开始](https://vuejs.org/guide/quick-start.html)支持TypeScript，当前Node要求为^22.18.0或>=24.12.0。计划使用Node24.12+的24.x版本；现机Node25.6.1不作为锁定基线。首次开发安装适用版本到D盘，不修改现有全局环境。

## 03 数据库及持久层

**Decision**：PostgreSQL18，Spring JDBC显式仓储和事务服务，Flyway是唯一结构迁移入口。
**Rationale**：已有报告使用范围、排他约束、联合外键；预约竞争适合明确SQL锁顺序。领域类与持久化记录分离，不要求分析类逐一变为ORM实体。
**Alternatives considered**：JPA能处理普通映射，但排他约束和复杂锁仍需原生SQL，首期统一JDBC减少两种持久层语义；MySQL不是能力不足，但迁移现有PostgreSQL规则没有收益；H2/SQLite不能替代真实并发验证。
**Evidence**：[PostgreSQL范围](https://www.postgresql.org/docs/18/rangetypes.html)支持范围重叠和排他约束；[约束说明](https://www.postgresql.org/docs/18/ddl-constraints.html)指出跨行跨表规则不能依赖普通CHECK。
[Spring事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)用于应用服务边界，避免同类自调用绕过代理；业务异常明确配置回滚。用[单一初始化机制](https://docs.spring.io/spring-boot/how-to/data-initialization.html)管理迁移，不混用自动建表。

## 04 身份与会话

**Decision**：员工密码登录使用强哈希和服务端可撤销会话；管理网页使用HttpOnly Cookie及CSRF防护。小程序使用不透明Bearer会话，服务端仅存token摘要。每次请求核验enabled、auth_version和角色门店范围。
**Rationale**：权限撤销即生效，比长期无状态JWT易解释；不引入Redis，复用auth_session。
**Alternatives considered**：JWT不是必须，不能只检查签名而忽略停用和权限版本。
微信客户端提交code，后端换取AppID/OpenID身份；新用户先进入短期注册上下文，绑定或建档后才创建完整账号，避免违反账号必须有患者或员工身份的约束。
**External gate**：真实AppID、密钥、HTTPS合法域名、隐私配置及平台审核在真实接入前核对。此次微信官方页面未能抓取，不宣称已核验当前全部平台准入条件。
官方核对入口：[登录](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)、[网络](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)。开发模式模拟身份明确标识，生产启动禁止mock。

## 05 业务一致性

**Decision**：PostgreSQL事务、按患者→医生→排班→资源加锁，加排他约束兜底；消息在提交后发送。关键状态记录和待通知事项持久保存。
**Rationale**：查询可约不等于占号；停诊、取消、改约和签到存在竞争。
**Alternatives considered**：先查后插、仅前端按钮防重复、仅进程内锁均不能保证多请求和重启后的正确性。首期不需消息中间件或Kubernetes。
当前报告SQL把意向医生当作防重医生，技术模型新增确认医生快照用于排他约束；与排班医生一致性在事务中验证，意向医生保留原语义。

## 06 开发与部署

**Decision**：本地Windows开发、独立测试数据库；未来Linux服务器以Docker Compose运行API、PostgreSQL和反向代理，网页静态构建由代理提供。
**Rationale**：单台即可展示，不需先购买服务器；数据库不暴露公网。微信真实真机联调另需HTTPS环境。
**Alternatives considered**：纯云函数会增加现有关系数据库及长事务适配工作，暂不选。实际购买或发布仍需用户后续授权。

## 仍待实际接入的条件

技术选型已确定；小程序账号、真实域名、生产服务器不是编写规格的前置条件，但未具备时不能声称生产可用。
第一轮依赖解析、构建、真实PostgreSQL迁移与微信开发工具导入必须实测，失败再记录兼容性调整，不能把网页资料当测试。
