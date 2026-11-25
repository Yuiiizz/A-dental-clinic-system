import {
  demoAssets, demoChairs, demoClinics, demoDoctors, demoPatients, demoServices, demoSuppliers,
  clinicName, billRemainingCents, demoState, doctorName, formatCents, patientName, supplierName,
} from './data';

export interface DemoColumn { key: string; label: string; }
export interface DemoAction { key: string; label: string; tone?: string; }
export interface DemoModule {
  key: string;
  title: string;
  subtitle: string;
  group: string;
  columns: DemoColumn[];
  rows: () => Record<string, unknown>[];
  actions?: DemoAction[];
  metric?: string;
  metricLabel?: string;
  note?: string;
}

const appointmentRows = () => demoState.appointments.map((a) => ({
  _id: a.id,
  _actionId: a.id,
  时间: a.time,
  患者: patientName(a.patientId),
  医生: doctorName(a.doctorId),
  门店: clinicName(a.clinicId),
  项目: a.item,
  来源: a.source,
  状态: a.status,
}));
const appointmentActions: DemoAction[] = [
  { key: 'checkin', label: '签到', tone: 'primary' },
  { key: 'confirm', label: '确认' },
  { key: 'cancel', label: '取消', tone: 'danger' },
];

export const demoModules: Record<string, DemoModule> = {
  appointments: {
    key: 'appointments', title: '预约列表', subtitle: '按时间查看今日与近期预约，支持前台签到、确认和取消演示。', group: '预约接诊',
    columns: [{ key: '时间', label: '时间' }, { key: '患者', label: '患者' }, { key: '医生', label: '医生' }, { key: '项目', label: '项目' }, { key: '状态', label: '状态' }],
    rows: appointmentRows, actions: appointmentActions,
  },
  calendar: {
    key: 'calendar', title: '日历排班', subtitle: '以医生与诊椅为维度查看本周排班；演示数据不写入排班表。', group: '预约接诊',
    columns: [{ key: '时间', label: '时间' }, { key: '医生', label: '医生' }, { key: '患者', label: '患者' }, { key: '诊椅', label: '诊椅' }, { key: '状态', label: '状态' }],
    rows: () => demoState.appointments.map((a) => ({ _id: a.id, 时间: a.time, 医生: doctorName(a.doctorId), 患者: patientName(a.patientId), 诊椅: `${a.chair} · ${clinicName(a.clinicId)}`, 状态: a.status })),
  },
  walkin: {
    key: 'walkin', title: '代预约', subtitle: '前台代患者预约；演示表单只生成本地记录，不占用真实号源。', group: '预约接诊',
    columns: [{ key: '时间', label: '时间' }, { key: '患者', label: '患者' }, { key: '医生', label: '医生' }, { key: '来源', label: '来源' }, { key: '状态', label: '状态' }],
    rows: () => demoState.appointments.filter((a) => a.source === '前台代约').map((a) => ({ _id: a.id, 时间: a.time, 患者: patientName(a.patientId), 医生: doctorName(a.doctorId), 来源: a.source, 状态: a.status })),
    actions: appointmentActions,
  },
  checkin: {
    key: 'checkin', title: '签到候诊', subtitle: '演示签到只改变本地候诊状态，不触发真实叫号或收费。', group: '预约接诊',
    columns: [{ key: '时间', label: '时间' }, { key: '患者', label: '患者' }, { key: '医生', label: '医生' }, { key: '诊椅', label: '诊椅' }, { key: '状态', label: '状态' }],
    rows: () => demoState.appointments.filter((a) => !a.status.includes('取消')).map((a) => ({ _id: a.id, 时间: a.time, 患者: patientName(a.patientId), 医生: doctorName(a.doctorId), 诊椅: a.chair, 状态: a.status })),
    actions: appointmentActions,
  },
  patients: {
    key: 'patients', title: '患者档案', subtitle: '统一查看患者标签、联系方式与最近就诊；不展示未授权诊疗细节。', group: '患者与复诊',
    columns: [{ key: '姓名', label: '姓名' }, { key: '性别', label: '性别' }, { key: '电话', label: '电话' }, { key: '标签', label: '标签' }, { key: '最近就诊', label: '最近就诊' }],
    rows: () => demoPatients.map((p) => ({ _id: p.id, 姓名: p.name, 性别: p.gender, 电话: p.phone, 标签: p.tags.join(' / '), 最近就诊: p.lastVisit })),
  },
  visits: {
    key: 'visits', title: '就诊记录', subtitle: '演示病历摘要不包含真实医疗建议或完整病历。', group: '患者与复诊',
    columns: [{ key: '日期', label: '日期' }, { key: '患者', label: '患者' }, { key: '医生', label: '医生' }, { key: '诊断摘要', label: '诊断摘要' }, { key: '处置', label: '处置' }],
    rows: () => demoState.visits.map((v) => ({ _id: v.id, 日期: v.date, 患者: patientName(v.patientId), 医生: doctorName(v.doctorId), 诊断摘要: v.diagnosis, 处置: v.items })),
  },
  recalls: {
    key: 'recalls', title: '复诊计划', subtitle: '复诊优先原主治医生；演示改约与联系不发送真实通知。', group: '患者与复诊',
    columns: [{ key: '到期', label: '到期' }, { key: '患者', label: '患者' }, { key: '主治医生', label: '主治医生' }, { key: '原因', label: '原因' }, { key: '状态', label: '状态' }],
    rows: () => demoState.recalls.map((r) => ({ _id: r.id, 到期: r.due, 患者: patientName(r.patientId), 主治医生: doctorName(r.doctorId), 原因: r.reason, 状态: r.status })),
  },
  contacts: {
    key: 'contacts', title: '联系记录', subtitle: '记录电话/微信联系摘要；演示内容不写入患者档案。', group: '患者与复诊',
    columns: [{ key: '时间', label: '时间' }, { key: '患者', label: '患者' }, { key: '渠道', label: '渠道' }, { key: '摘要', label: '摘要' }, { key: '负责人', label: '负责人' }],
    rows: () => demoState.contacts.map((c) => ({ _id: c.id, 时间: c.time, 患者: patientName(c.patientId), 渠道: c.channel, 摘要: c.summary, 负责人: c.owner })),
  },
  treatments: {
    key: 'treatments', title: '治疗项目', subtitle: '服务目录与时长仅用于页面演示，不改变真实收费目录。', group: '诊疗与收费',
    columns: [{ key: '项目', label: '项目' }, { key: '时长', label: '时长' }, { key: '参考价', label: '参考价（演示）' }, { key: '可执行医生', label: '可执行医生' }],
    rows: () => demoServices.map((s) => ({ _id: s.id, 项目: s.name, 时长: s.duration, 参考价: s.price, 可执行医生: s.doctors })),
  },
  plans: {
    key: 'plans', title: '治疗计划', subtitle: '计划阶段、负责医生与患者同意状态，均为本地演示。', group: '诊疗与收费',
    columns: [{ key: '患者', label: '患者' }, { key: '计划', label: '计划' }, { key: '次数', label: '计划次数' }, { key: '负责医生', label: '负责医生' }, { key: '状态', label: '状态' }],
    rows: () => demoState.plans.map((p) => ({ _id: p.id, 患者: patientName(p.patientId), 计划: p.item, 次数: `${p.count} 次`, 负责医生: doctorName(p.doctorId), 状态: '进行中（演示）' })),
  },
  bills: {
    key: 'bills', title: '账单', subtitle: '账单和金额为演示数据；“收款”不会发起真实支付。', group: '诊疗与收费',
    columns: [{ key: '账单号', label: '账单号' }, { key: '患者', label: '患者' }, { key: '日期', label: '日期' }, { key: '总额', label: '总额（演示）' }, { key: '已付', label: '已付（演示）' }, { key: '待付', label: '待付（演示）' }, { key: '状态', label: '状态' }],
    rows: () => demoState.bills.map((b) => ({ _id: b.id, _actionId: b.id, 账单号: b.id, 患者: patientName(b.patientId), 日期: b.date, 总额: b.amount, 已付: formatCents(b.paidCents), 待付: formatCents(billRemainingCents(b)), 状态: b.status })),
    actions: [{ key: 'collect', label: '模拟收款', tone: 'primary' }],
  },
  payments: {
    key: 'payments', title: '收款记录', subtitle: '仅演示流水，不对应真实支付渠道或账务凭证。', group: '诊疗与收费',
    columns: [{ key: '流水号', label: '流水号' }, { key: '账单', label: '账单' }, { key: '日期', label: '日期' }, { key: '方式', label: '方式' }, { key: '金额', label: '金额（演示）' }, { key: '状态', label: '状态' }],
    rows: () => demoState.payments.map((p) => ({ _id: p.id, 流水号: p.id, 账单: p.billId, 日期: p.date, 方式: p.method, 金额: p.amount, 状态: p.status })),
  },
  refunds: {
    key: 'refunds', title: '退款', subtitle: '退款为演示审批流，不发生真实资金退回。', group: '诊疗与收费',
    columns: [{ key: '申请号', label: '申请号' }, { key: '患者', label: '患者' }, { key: '金额', label: '金额（演示）' }, { key: '原因', label: '原因' }, { key: '状态', label: '状态' }],
    rows: () => [
      { _id: 'RF-01', 申请号: 'RF-01', 患者: '陈小雨', 金额: '¥ 160.00', 原因: '项目调整（演示）', 状态: '待审批' },
      { _id: 'RF-02', 申请号: 'RF-02', 患者: '李沐', 金额: '¥ 300.00', 原因: '重复预约（演示）', 状态: '已拒绝（演示）' },
    ],
  },
  invoices: {
    key: 'invoices', title: '发票', subtitle: '演示发票不具法律效力，不是正式发票。', group: '诊疗与收费',
    columns: [{ key: '发票号', label: '发票号' }, { key: '账单', label: '账单' }, { key: '抬头', label: '抬头' }, { key: '金额', label: '金额（演示）' }, { key: '状态', label: '状态' }],
    rows: () => demoState.invoices.map((i) => ({ _id: i.id, 发票号: i.id, 账单: i.billId, 抬头: i.title, 金额: i.amount, 状态: i.status })),
  },
  suppliers: {
    key: 'suppliers', title: '供应商', subtitle: '供应商联系信息为虚构演示数据。', group: '采购与成本',
    columns: [{ key: '名称', label: '名称' }, { key: '联系人', label: '联系人' }, { key: '电话', label: '电话' }, { key: '类别', label: '类别' }],
    rows: () => demoSuppliers.map((s) => ({ _id: s.id, 名称: s.name, 联系人: s.contact, 电话: s.phone, 类别: s.category })),
  },
  purchases: {
    key: 'purchases', title: '采购验收', subtitle: '验收只改变本地演示状态，不入库、不生成应付款。', group: '采购与成本',
    columns: [{ key: '单号', label: '单号' }, { key: '供应商', label: '供应商' }, { key: '日期', label: '日期' }, { key: '金额', label: '金额（演示）' }, { key: '状态', label: '状态' }],
    rows: () => demoState.purchases.map((p) => ({ _id: p.id, 单号: p.id, 供应商: supplierName(p.supplierId), 日期: p.date, 金额: p.amount, 状态: p.status })),
    actions: [{ key: 'accept', label: '模拟验收', tone: 'primary' }],
  },
  inventory: {
    key: 'inventory', title: '药品耗材库存', subtitle: '库存为本地演示数字，不参与真实扣减。', group: '采购与成本',
    columns: [{ key: '名称', label: '名称' }, { key: '类别', label: '类别' }, { key: '库存', label: '库存' }, { key: '预警线', label: '预警线' }, { key: '有效期', label: '有效期' }],
    rows: () => demoState.inventory.map((i) => ({ _id: i.id, 名称: i.name, 类别: i.category, 库存: `${i.stock} ${i.unit}`, 预警线: `${i.min} ${i.unit}`, 有效期: i.expiry })),
  },
  assets: {
    key: 'assets', title: '设备资产', subtitle: '资产价值与折旧为演示估算，不构成财务账目。', group: '采购与成本',
    columns: [{ key: '资产', label: '资产' }, { key: '门店', label: '门店' }, { key: '购入', label: '购入' }, { key: '原值', label: '原值（演示）' }, { key: '状态', label: '状态' }],
    rows: () => demoAssets.map((a) => ({ _id: a.id, 资产: a.name, 门店: clinicName(a.clinicId), 购入: a.purchased, 原值: a.value, 状态: a.status })),
  },
  depreciation: {
    key: 'depreciation', title: '折旧与成本', subtitle: '成本拆分为演示口径，不可用于真实经营决策。', group: '采购与成本',
    columns: [{ key: '月份', label: '月份' }, { key: '设备折旧', label: '设备折旧（演示）' }, { key: '耗材成本', label: '耗材成本（演示）' }, { key: '人力成本', label: '人力成本（演示）' }],
    rows: () => [
      { _id: 'CO-10', 月份: '2026-10', 设备折旧: '¥ 12,800', 耗材成本: '¥ 18,400', 人力成本: '¥ 86,000' },
      { _id: 'CO-09', 月份: '2026-09', 设备折旧: '¥ 12,800', 耗材成本: '¥ 16,900', 人力成本: '¥ 84,000' },
    ],
  },
  credentials: {
    key: 'credentials', title: '员工资格', subtitle: '资格摘要为演示；真实录入/审核请进入员工管理（真实接口）。', group: '基础设置',
    columns: [{ key: '员工', label: '员工' }, { key: '资格', label: '资格' }, { key: '状态', label: '状态' }, { key: '有效期', label: '有效期' }, { key: '版本', label: '版本' }],
    rows: () => [
      { _id: 'CR-01', 员工: '林知夏', 资格: '医师执业证', 状态: '已核验', 有效期: '2028-12-31', 版本: 'v2' },
      { _id: 'CR-02', 员工: '周屿', 资格: '正畸专项培训', 状态: '待审核', 有效期: '—', 版本: 'v1' },
      { _id: 'CR-03', 员工: '陈望', 资格: '种植专项培训', 状态: '未通过/已撤销', 有效期: '2027-06-30', 版本: 'v3' },
    ],
  },
  assignments: {
    key: 'assignments', title: '门店任职', subtitle: '跨店任职为演示关系，不写入真实员工任职。', group: '基础设置',
    columns: [{ key: '员工', label: '员工' }, { key: '门店', label: '门店' }, { key: '岗位', label: '岗位' }, { key: '状态', label: '状态' }],
    rows: () => [
      { _id: 'AS-01', 员工: '林知夏', 门店: '云栖总院', 岗位: '牙体牙髓', 状态: '启用' },
      { _id: 'AS-02', 员工: '陈望', 门店: '滨江分院', 岗位: '种植外科', 状态: '启用' },
      { _id: 'AS-03', 员工: '沈禾', 门店: '滨江分院', 岗位: '牙周洁牙', 状态: '停用' },
    ],
  },
  services: {
    key: 'services', title: '医生服务范围', subtitle: '服务范围仅演示，真实配置未开放。', group: '基础设置',
    columns: [{ key: '医生', label: '医生' }, { key: '门店', label: '门店' }, { key: '项目', label: '项目' }, { key: '状态', label: '状态' }],
    rows: () => demoDoctors.flatMap((d) => d.specialties.map((s, i) => ({ _id: `${d.id}-${i}`, 医生: d.name, 门店: clinicName(d.clinicId), 项目: s, 状态: '启用（演示）' }))),
  },
  chairs: {
    key: 'chairs', title: '诊椅设备', subtitle: '诊椅状态为演示，不触发消毒或设备工单。', group: '基础设置',
    columns: [{ key: '诊椅', label: '诊椅' }, { key: '门店', label: '门店' }, { key: '设备', label: '设备' }, { key: '状态', label: '状态' }],
    rows: () => demoChairs.map((c) => ({ _id: c.id, 诊椅: c.name, 门店: clinicName(c.clinicId), 设备: c.equipment, 状态: c.status })),
  },
  policies: {
    key: 'policies', title: '预约政策', subtitle: '政策值仅用于界面演示，不修改真实预约规则。', group: '基础设置',
    columns: [{ key: '政策', label: '政策' }, { key: '值', label: '值' }, { key: '说明', label: '说明' }],
    rows: () => demoState.policies.map((p) => ({ _id: p.id, 政策: p.name, 值: p.value, 说明: p.note })),
  },
};

export interface NavGroup {
  title: string;
  items: Array<{ key: string; label: string; demo: boolean; path?: string }>;
}

export const navGroups: NavGroup[] = [
  { title: '工作台', items: [{ key: 'dashboard', label: '经营工作台', demo: true }] },
  { title: '预约接诊', items: [
    { key: 'appointments', label: '预约列表', demo: false, path: '/appointments' }, { key: 'appointment-workbench', label: '前台预约工作台', demo: false, path: '/appointments' }, { key: 'calendar', label: '医生排班表', demo: false, path: '/frontdesk-schedule' },
    { key: 'walkin', label: '代预约', demo: false, path: '/appointments' }, { key: 'checkin', label: '签到候诊', demo: false, path: '/appointments' },
    { key: 'internal-messages', label: '店内沟通', demo: false, path: '/internal-messages' },
    { key: 'unavailability', label: '停诊与资源维护', demo: false, path: '/unavailability' },
  ] },
  { title: '患者与复诊', items: [
    { key: 'patients', label: '就诊用户检索', demo: false, path: '/appointments' }, { key: 'clinical', label: '就诊复诊工作台', demo: false, path: '/clinical' }, { key: 'visits', label: '就诊记录', demo: false, path: '/clinical?tab=visits' },
    { key: 'recalls', label: '复诊计划', demo: false, path: '/clinical?tab=plans' }, { key: 'contacts', label: '联系记录', demo: false, path: '/appointments' },
  ] },
  { title: '诊疗与收费', items: [
    { key: 'treatments', label: '治疗项目', demo: false, path: '/scheduling?tab=catalog' }, { key: 'treatment-plans', label: '治疗计划', demo: false, path: '/treatment-plans' },
    { key: 'bills', label: '账单', demo: false, path: '/billing' }, { key: 'billing', label: '收费票据工作台', demo: false, path: '/billing' }, { key: 'payments', label: '收款记录', demo: false, path: '/billing' },
    { key: 'refunds', label: '退款', demo: false, path: '/billing' }, { key: 'invoices', label: '票据登记', demo: false, path: '/billing' },
  ] },
  { title: '采购与成本', items: [
    { key: 'suppliers', label: '供应商', demo: false, path: '/suppliers' }, { key: 'purchases', label: '采购验收', demo: false, path: '/inventory?tab=receiving' },
    { key: 'inventory', label: '普通耗材库存', demo: false, path: '/inventory?tab=balances' }, { key: 'assets', label: '设备资产', demo: true },
    { key: 'depreciation', label: '折旧与成本', demo: true },
  ] },
  { title: '基础设置', items: [
    { key: 'branches', label: '机构门店', demo: false, path: '/branches' },
    { key: 'staff', label: '员工管理', demo: false, path: '/staff-members' },
    { key: 'credentials', label: '员工资格摘要', demo: false, path: '/staff-members' },
    { key: 'assignments', label: '门店任职', demo: true },
    { key: 'service-catalog', label: '服务目录', demo: false, path: '/scheduling?tab=catalog' },
    { key: 'services', label: '医生服务范围', demo: false, path: '/scheduling?tab=scope' },
    { key: 'chairs', label: '诊椅设备', demo: false, path: '/scheduling?tab=chairs' },
    { key: 'scheduling', label: '排班与基础配置', demo: false, path: '/scheduling' },
    { key: 'accounts', label: '账号角色', demo: false, path: '/admin/accounts' },
    { key: 'policies', label: '预约政策', demo: true },
  ] },
];

export const clinicList = demoClinics;
export const doctorList = demoDoctors;
