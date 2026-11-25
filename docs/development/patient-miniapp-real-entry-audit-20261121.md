# 患者小程序真实入口盘点（2026-11-21）

本批只改患者小程序入口、读取逻辑和测试；没有改后端、OpenAPI、数据库、权限或真实预约；没有写入、删除或修改云端数据；只按真实入口读取了公开门店/医生目录。没有上传、发布小程序，也没有购买服务或输出密钥。

## 真实接口已存在的能力

| 能力 | 页面/入口 | 接口 |
| --- | --- | --- |
| 已核验营业门店 | 首页“门店与医生”、门店页 | `GET /public/branches` |
| 公开医生名录 | 首页“门店与医生”、医生页 | `GET /public/doctors` |
| 可约时段/医生排班 | 医生页“已发布排班医生”、真实预约页 | `GET /me/booking`、`GET /public/slots` |
| 本人预约、创建、改约、取消、签到、协调答复 | 真实预约页 | `/me/booking`、`/me/appointments`、`/me/changes` 及其子操作 |
| 个人信息 | 个人中心、首页“个人信息” | `GET/POST /me/profile` |
| 就诊记录、复诊建议 | 真实就诊记录页 | `GET /me/records`、`/me/followup-plans` |
| 治疗计划接受/拒绝 | 真实就诊记录页 | `/me/treatment-plans` |
| 费用、付款、退款、发票只读 | 费用页、首页“费用与发票” | `GET /me/billing` |
| 医生收藏来源 | 医生页 | `/me/doctor-preferences` |

## 页面和演示状态

| 页面/入口 | 当前处理 |
| --- | --- |
| 首页 | 始终显示真实门店/医生公开目录、真实预约摘要、真实服务入口；只有未登录时显示独立的本地演示样例；登录后隐藏所有演示入口。 |
| 门店页 | 顶部和门店选择读取真实公开门店；增加“登录后真实预约/真实预约”按钮；演示门店块只在未登录演示模式显示。 |
| 医生页 | 真实公开医生名录和登录后的真实排班始终显示；收藏按账号代次清理；演示医生块只在未登录且演示开启时显示。 |
| 真实预约页 | 读取真实排班/项目/可用时段/本人预约；切换账号清空旧数据并使旧请求失效。 |
| 真实记录页 | 读取本人真实就诊、复诊建议和治疗计划；切换账号清空旧数据。 |
| 个人中心 | 读取本人资料；未登录不展示真实资料；退出清理会话。 |
| 费用页 | 只读本人账单/付款/退款/发票；没有真实记录时不显示演示账单。 |
| 演示预约页 | 仍为本地虚构流程；明确“不写入后台、不占真实号源”；新增“进入真实预约”出口。 |
| 演示预约列表 | 仍为本地虚构状态；明确不写后台；新增“进入真实预约”出口。 |
| 演示就诊记录 | 仍为演示；明确不含完整病历；新增“进入真实就诊记录”出口。 |
| 登录页 | “浏览演示（虚构）”入口保留，和真实登录分开。 |

## 本批修正

- 首页原先只有真实预约/记录/费用，门店和医生只在演示块；现增加真实公开门店、医生入口，并从首页读取公开目录。
- 首页登录后读取本人下一次有效真实预约；切换账号时清空旧摘要并忽略迟到响应。
- 首页删除与真实服务重复的演示“常用服务/门店/医生团队/下一次就诊/最近就诊”卡片，改为一个明确的演示样例入口。
- 登录后首页、门店页、医生页不再显示本地演示块或演示横幅。
- 医生页按 `patientSession.revision` 清理收藏来源和旧真实排班，避免上一账号资料在新账号下短暂残留。
- 演示预约、演示预约列表、演示记录各自增加进入对应真实页面的出口，避免按钮只能停在演示状态。
- 个人中心未登录文案明确：只有预约/记录可浏览演示，费用不展示演示账单。

## 仍为演示及原因

- 演示预约、演示改约/取消、替诊请求、演示就诊记录仍是本地状态，因为对应页面使用 `demo/store`，不是真实服务；真实预约改约/取消/协调在真实预约页。
- 门店/医生页下方的虚构样例仍保留为未登录课堂样例，但不再与真实卡片混用；登录后隐藏。
- 付款页不发起支付；发票只读。真实支付/开票/通知不在本批能力内。

## 需要真机/开发者工具验证

- 已在开发者工具执行普通编译：首页实际渲染成功，渲染层读到公开门店/医生（含“教学虚拟”标识）、真实服务卡和独立演示样例；没有只靠单测替代 WXML 渲染结论。
- 本次只读取公开门店/医生接口，没有写入、删除或修改云端数据，没有操作真实预约。
- 真实微信登录、真实患者会话、授权后的排班/预约/记录/费用读取：本轮未覆盖。
- 真机触屏：老师/患者实际点击、扫码、返回栈和分享等能力仍需真机。
- 真实云端预约没有读取或修改；只读取了公开门店/医生目录，没有写入、删除或修改云端数据。

## 测试

```powershell
cd patient-miniapp
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
```

结果：

- `typecheck` 通过；
- **18 个测试文件、134 项通过**；
- 新增 `home-real.test.ts`：登录后真实门店/医生/下一次预约、未登录演示隔离、换账号旧摘要不覆盖新结果；
- 新增 `doctors-session.test.ts`：换账号先清空收藏和旧排班；
- 更新 `demo-pages.test.ts`：首页真实/演示入口分离、演示开关、演示页边界和演示页真实出口。

## 修改文件

- `patient-miniapp/miniprogram/pages/home/home.ts`、`home.wxml`、`home.wxss`
- `patient-miniapp/miniprogram/pages/doctors/doctors.ts`、`doctors.wxml`
- `patient-miniapp/miniprogram/pages/clinic/clinic.ts`、`clinic.wxml`
- `patient-miniapp/miniprogram/pages/booking/booking.ts`、`booking.wxml`
- `patient-miniapp/miniprogram/pages/appointments/appointments.ts`、`appointments.wxml`
- `patient-miniapp/miniprogram/pages/records/records.ts`、`records.wxml`
- `patient-miniapp/miniprogram/pages/index/index.wxml`
- `patient-miniapp/miniprogram/pages/profile/profile.wxml`
- `patient-miniapp/__tests__/home-real.test.ts`（新增）、`doctors-session.test.ts`（新增）、`demo-pages.test.ts`
- 本文件与 `handoff.md`

未修改后端、OpenAPI、数据库、权限、迁移、真实预约、Word 或云端；未提交、推送或发布。

## 2026-11-21 界面改进补充

- 首页增加真实/演示模式切换、下一步引导、需登录标识和本地图标；演示模式不请求真实接口。
- 建档页改为外层边框控件、分组字段、字段错误提示，保留原服务端校验。
- 新增界面验收记录与截图：`docs/development/patient-miniapp-ui-polish-20261121.md`、`docs/screenshots/patient-ui/`。
- 患者端目前 18 个测试文件、134 项通过；已普通编译并生成预览二维码，未上传体验版/未发布。
