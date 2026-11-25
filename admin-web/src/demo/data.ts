// 管理端本地演示数据：只在显式演示模式下使用，不写入后端，也不代表真实经营/支付/发票。
import { reactive } from 'vue';

export interface DemoClinic {
  id: string;
  name: string;
  area: string;
  address: string;
  phone: string;
}
export interface DemoDoctor {
  id: string;
  name: string;
  title: string;
  specialties: string[];
  clinicId: string;
  schedule: string[];
}
export interface DemoPatient {
  id: string;
  name: string;
  gender: string;
  phone: string;
  tags: string[];
  lastVisit: string;
}
export interface DemoAppointment {
  id: string;
  time: string;
  patientId: string;
  doctorId: string;
  clinicId: string;
  item: string;
  status: string;
  source: string;
  chair: string;
}
export interface DemoVisit {
  id: string;
  date: string;
  patientId: string;
  doctorId: string;
  diagnosis: string;
  items: string;
}
export interface DemoRecall {
  id: string;
  patientId: string;
  due: string;
  doctorId: string;
  reason: string;
  status: string;
}
export interface DemoBill {
  id: string;
  patientId: string;
  date: string;
  amount: string;
  totalCents: number;
  paidCents: number;
  status: string;
  item: string;
}
export interface DemoPlan {
  id: string;
  patientId: string;
  item: string;
  count: number;
  note: string;
  doctorId: string;
}
export interface DemoMaterial {
  id: string;
  name: string;
  unit: string;
  unitPriceCents: number;
}
export interface DemoInventory {
  id: string;
  name: string;
  category: string;
  stock: number;
  unit: string;
  min: number;
  expiry: string;
}
export interface DemoSupplier {
  id: string;
  name: string;
  contact: string;
  phone: string;
  category: string;
}
export interface DemoPurchase {
  id: string;
  supplierId: string;
  date: string;
  amount: string;
  status: string;
}
export interface DemoAsset {
  id: string;
  name: string;
  clinicId: string;
  purchased: string;
  value: string;
  status: string;
}
export interface DemoContact {
  id: string;
  patientId: string;
  time: string;
  channel: string;
  summary: string;
  owner: string;
}
export interface DemoService {
  id: string;
  name: string;
  duration: string;
  price: string;
  doctors: string;
}
export interface DemoChair {
  id: string;
  name: string;
  clinicId: string;
  status: string;
  equipment: string;
}
export interface DemoPolicy {
  id: string;
  name: string;
  value: string;
  note: string;
}

export const demoClinics: DemoClinic[] = [
  { id: 'CL-01', name: '云栖总院', area: '西湖区', address: '云栖路 18 号 2 层', phone: '0571-8800 2100' },
  { id: 'CL-02', name: '滨江分院', area: '滨江区', address: '江晖路 66 号 1 层', phone: '0571-8800 2200' },
];

export const demoDoctors: DemoDoctor[] = [
  { id: 'DR-01', name: '林知夏', title: '主治医师', specialties: ['牙体牙髓', '显微根管'], clinicId: 'CL-01', schedule: ['周二全天', '周三上午', '周四下午'] },
  { id: 'DR-02', name: '周屿', title: '副主任医师', specialties: ['正畸', '儿童早期矫治'], clinicId: 'CL-01', schedule: ['周二全天', '周三全天', '周六上午'] },
  { id: 'DR-03', name: '陈望', title: '主治医师', specialties: ['种植', '口腔外科'], clinicId: 'CL-02', schedule: ['周二上午', '周四上午', '周五下午'] },
  { id: 'DR-04', name: '沈禾', title: '医师', specialties: ['牙周', '洁牙'], clinicId: 'CL-02', schedule: ['周二下午', '周五上午', '周日全天'] },
  { id: 'DR-05', name: '许清禾', title: '主治医师', specialties: ['儿童口腔', '窝沟封闭'], clinicId: 'CL-01', schedule: ['周一下午', '周三上午', '周六全天'] },
  { id: 'DR-06', name: '唐予安', title: '主治医师', specialties: ['修复', '全瓷冠'], clinicId: 'CL-01', schedule: ['周一全天', '周四全天', '周日上午'] },
  { id: 'DR-07', name: '贺景行', title: '副主任医师', specialties: ['种植', '复杂拔牙'], clinicId: 'CL-02', schedule: ['周二全天', '周五上午', '周六下午'] },
  { id: 'DR-08', name: '叶澄', title: '医师', specialties: ['牙体牙髓', '补牙'], clinicId: 'CL-02', schedule: ['周一上午', '周三全天', '周五上午'] },
];

export const demoPatients: DemoPatient[] = [
  { id: 'PT-1001', name: '张岚', gender: '女', phone: '138****2210', tags: ['复诊', '牙周维护'], lastVisit: '2026-09-18' },
  { id: 'PT-1002', name: '李沐', gender: '男', phone: '139****8031', tags: ['正畸'], lastVisit: '2026-10-12' },
  { id: 'PT-1003', name: '王启', gender: '男', phone: '137****5642', tags: ['种植', '术后'], lastVisit: '2026-10-20' },
  { id: 'PT-1004', name: '陈小雨', gender: '女', phone: '136****1188', tags: ['儿牙', '家长陪同'], lastVisit: '2026-08-30' },
  { id: 'PT-1005', name: '周舟', gender: '女', phone: '135****7720', tags: ['初诊'], lastVisit: '—' },
  { id: 'PT-1006', name: '赵宁', gender: '女', phone: '138****4306', tags: ['儿牙', '监护人陪同'], lastVisit: '2026-10-11' },
  { id: 'PT-1007', name: '何川', gender: '男', phone: '139****6172', tags: ['修复', '复诊'], lastVisit: '2026-10-16' },
  { id: 'PT-1008', name: '刘可', gender: '女', phone: '137****2048', tags: ['洁牙'], lastVisit: '2026-09-26' },
  { id: 'PT-1009', name: '宋晨', gender: '男', phone: '136****9503', tags: ['补牙'], lastVisit: '2026-10-09' },
];

export const demoAppointments: DemoAppointment[] = [
  { id: 'AP-2601', time: '2026-10-27 09:00', patientId: 'PT-1001', doctorId: 'DR-01', clinicId: 'CL-01', item: '牙周复查', status: '候诊', source: '复诊预约', chair: 'A1' },
  { id: 'AP-2602', time: '2026-10-27 09:30', patientId: 'PT-1002', doctorId: 'DR-02', clinicId: 'CL-01', item: '正畸复诊', status: '已确认', source: '患者自助', chair: 'B2' },
  { id: 'AP-2603', time: '2026-10-27 10:15', patientId: 'PT-1003', doctorId: 'DR-03', clinicId: 'CL-02', item: '种植术后复查', status: '接诊中', source: '医生预约', chair: 'C1' },
  { id: 'AP-2604', time: '2026-10-27 14:00', patientId: 'PT-1004', doctorId: 'DR-02', clinicId: 'CL-01', item: '儿牙涂氟', status: '待确认', source: '前台代约', chair: 'B1' },
  { id: 'AP-2605', time: '2026-10-28 09:30', patientId: 'PT-1005', doctorId: 'DR-01', clinicId: 'CL-01', item: '初诊检查', status: '已确认', source: '患者自助', chair: 'A2' },
  { id: 'AP-2606', time: '2026-10-27 15:30', patientId: 'PT-1001', doctorId: 'DR-04', clinicId: 'CL-02', item: '洁牙', status: '已完成', source: '复诊预约', chair: 'D1' },
  { id: 'AP-2607', time: '2026-10-28 10:00', patientId: 'PT-1006', doctorId: 'DR-05', clinicId: 'CL-01', item: '儿童窝沟封闭', status: '已确认', source: '前台代约', chair: 'B1' },
  { id: 'AP-2608', time: '2026-10-29 14:30', patientId: 'PT-1007', doctorId: 'DR-06', clinicId: 'CL-01', item: '全瓷冠复诊', status: '已确认', source: '患者自助', chair: 'A2' },
  { id: 'AP-2609', time: '2026-10-30 09:00', patientId: 'PT-1008', doctorId: 'DR-04', clinicId: 'CL-02', item: '洁牙', status: '待确认', source: '前台代约', chair: '待分配' },
  { id: 'AP-2610', time: '2026-10-30 10:30', patientId: 'PT-1009', doctorId: 'DR-08', clinicId: 'CL-02', item: '树脂补牙', status: '已确认', source: '患者自助', chair: 'C1' },
];

export const demoVisits: DemoVisit[] = [
  { id: 'VS-901', date: '2026-09-18', patientId: 'PT-1001', doctorId: 'DR-01', diagnosis: '慢性牙周炎维护期', items: '龈下刮治 · 牙周检查' },
  { id: 'VS-902', date: '2026-10-12', patientId: 'PT-1002', doctorId: 'DR-02', diagnosis: '安氏 II 类，正畸中', items: '弓丝调整 · 口腔卫生指导' },
  { id: 'VS-903', date: '2026-10-20', patientId: 'PT-1003', doctorId: 'DR-03', diagnosis: '种植术后恢复良好', items: '拆线 · 影像复查' },
];

export const demoRecalls: DemoRecall[] = [
  { id: 'RC-01', patientId: 'PT-1001', due: '2026-11-02', doctorId: 'DR-01', reason: '牙周维护 6 周复诊', status: '待联系' },
  { id: 'RC-02', patientId: 'PT-1002', due: '2026-10-30', doctorId: 'DR-02', reason: '正畸加力', status: '已预约' },
  { id: 'RC-03', patientId: 'PT-1003', due: '2026-10-29', doctorId: 'DR-03', reason: '种植术后 2 周复查', status: '待确认' },
];

export const demoBills: DemoBill[] = [
  { id: 'BL-501', patientId: 'PT-1001', date: '2026-09-18', amount: '¥ 680.00', totalCents: 68000, paidCents: 68000, status: '已收款（演示）', item: '牙周维护' },
  { id: 'BL-502', patientId: 'PT-1002', date: '2026-10-12', amount: '¥ 1,280.00', totalCents: 128000, paidCents: 0, status: '待收款（演示）', item: '正畸复诊' },
  { id: 'BL-503', patientId: 'PT-1003', date: '2026-10-20', amount: '¥ 2,460.00', totalCents: 246000, paidCents: 120000, status: '部分收款（演示）', item: '种植复查' },
  { id: 'BL-504', patientId: 'PT-1004', date: '2026-10-27', amount: '¥ 320.00', totalCents: 32000, paidCents: 0, status: '待收款（演示）', item: '儿牙涂氟' },
];

export const demoPayments = [
  { id: 'PM-301', billId: 'BL-501', date: '2026-09-18', method: '演示-微信支付', amount: '¥ 680.00', status: '演示记录' },
  { id: 'PM-302', billId: 'BL-503', date: '2026-10-20', method: '演示-银行卡', amount: '¥ 1,200.00', status: '演示记录' },
];

export const demoInvoices = [
  { id: 'IV-201', billId: 'BL-501', title: '个人', amount: '¥ 680.00', status: '演示发票（非正式）' },
  { id: 'IV-202', billId: 'BL-503', title: '个人', amount: '¥ 1,200.00', status: '演示发票（非正式）' },
];

export const demoSuppliers: DemoSupplier[] = [
  { id: 'SP-01', name: '杭州青禾齿科材料', contact: '许经理', phone: '0571-8899 1201', category: '耗材' },
  { id: 'SP-02', name: '华东影像设备服务', contact: '罗工', phone: '0571-8899 3202', category: '设备维保' },
  { id: 'SP-03', name: '安齿医疗科技', contact: '卢经理', phone: '021-6688 4103', category: '种植体' },
];

export const demoPurchases: DemoPurchase[] = [
  { id: 'PO-701', supplierId: 'SP-01', date: '2026-10-21', amount: '¥ 8,600.00', status: '待验收（演示）' },
  { id: 'PO-702', supplierId: 'SP-03', date: '2026-10-18', amount: '¥ 24,000.00', status: '已验收（演示）' },
  { id: 'PO-703', supplierId: 'SP-02', date: '2026-10-15', amount: '¥ 3,200.00', status: '已验收（演示）' },
];

export const demoInventory: DemoInventory[] = [
  { id: 'IN-01', name: '一次性口腔检查盘', category: '耗材', stock: 42, unit: '盒', min: 20, expiry: '2027-06-30' },
  { id: 'IN-02', name: '树脂充填材料 A2', category: '耗材', stock: 8, unit: '支', min: 10, expiry: '2027-02-28' },
  { id: 'IN-03', name: '0.022 正畸托槽', category: '耗材', stock: 15, unit: '套', min: 5, expiry: '2028-01-31' },
  { id: 'IN-04', name: '一次性注射针头', category: '耗材', stock: 6, unit: '盒', min: 8, expiry: '2026-12-31' },
];

export const demoAssets: DemoAsset[] = [
  { id: 'AS-01', name: '口腔综合治疗台 A1', clinicId: 'CL-01', purchased: '2023-05-12', value: '¥ 86,000', status: '在用' },
  { id: 'AS-02', name: '数字化口扫仪', clinicId: 'CL-01', purchased: '2024-03-08', value: '¥ 128,000', status: '在用' },
  { id: 'AS-03', name: 'CBCT 影像设备', clinicId: 'CL-02', purchased: '2022-11-20', value: '¥ 460,000', status: '维保中' },
];

export const demoContacts: DemoContact[] = [
  { id: 'CT-01', patientId: 'PT-1001', time: '2026-10-26 15:10', channel: '电话', summary: '确认 10-27 牙周复查，提醒提前 10 分钟到院。', owner: '前台-小顾' },
  { id: 'CT-02', patientId: 'PT-1002', time: '2026-10-25 11:20', channel: '微信', summary: '正畸复诊改约至 10-27 09:30。', owner: '前台-小顾' },
  { id: 'CT-03', patientId: 'PT-1003', time: '2026-10-24 17:05', channel: '电话', summary: '术后无不适，提醒按时复诊。', owner: '护士-小夏' },
];

export const demoServices: DemoService[] = [
  { id: 'SV-01', name: '初诊检查', duration: '30 分钟', price: '¥ 80', doctors: '林知夏 / 陈望' },
  { id: 'SV-02', name: '牙周维护', duration: '60 分钟', price: '¥ 680', doctors: '林知夏 / 沈禾' },
  { id: 'SV-03', name: '正畸复诊', duration: '30 分钟', price: '¥ 1,280', doctors: '周屿' },
  { id: 'SV-04', name: '种植术后复查', duration: '30 分钟', price: '¥ 320', doctors: '陈望' },
  { id: 'SV-05', name: '儿童窝沟封闭', duration: '30 分钟', price: '¥ 120', doctors: '许清禾' },
  { id: 'SV-06', name: '全瓷冠复诊', duration: '30 分钟', price: '¥ 160', doctors: '唐予安' },
  { id: 'SV-07', name: '树脂补牙', duration: '45 分钟', price: '¥ 360', doctors: '林知夏 / 叶澄' },
  { id: 'SV-08', name: '拔牙评估', duration: '30 分钟', price: '¥ 80', doctors: '陈望 / 贺景行' },
  { id: 'SV-09', name: '洁牙', duration: '45 分钟', price: '¥ 180', doctors: '沈禾' },
  { id: 'SV-10', name: '牙周复查', duration: '30 分钟', price: '¥ 120', doctors: '林知夏 / 沈禾' },
  { id: 'SV-11', name: '儿牙涂氟', duration: '30 分钟', price: '¥ 80', doctors: '周屿 / 许清禾' },
];

export const demoChairs: DemoChair[] = [
  { id: 'CH-01', name: 'A1 综合治疗台', clinicId: 'CL-01', status: '使用中', equipment: '口扫 · 内窥镜' },
  { id: 'CH-02', name: 'B2 正畸诊椅', clinicId: 'CL-01', status: '空闲', equipment: '正畸摄影' },
  { id: 'CH-03', name: 'C1 种植诊椅', clinicId: 'CL-02', status: '消毒中', equipment: '种植机 · CBCT' },
];

export const demoPolicies: DemoPolicy[] = [
  { id: 'PL-01', name: '预约提前量', value: '30 分钟', note: '演示政策，不对应后台配置' },
  { id: 'PL-02', name: '取消截止', value: '就诊前 4 小时', note: '演示政策，不对应后台配置' },
  { id: 'PL-03', name: '复诊优先主治', value: '开启', note: '临时替诊需患者同意' },
];

export const demoPlans: DemoPlan[] = [
  { id: 'TP-01', patientId: 'PT-1001', item: '牙周维护', count: 4, note: '6 周维护期，复诊优先林知夏', doctorId: 'DR-01' },
  { id: 'TP-02', patientId: 'PT-1002', item: '正畸复诊', count: 12, note: '固定正畸第 8 个月', doctorId: 'DR-02' },
  { id: 'TP-03', patientId: 'PT-1003', item: '种植术后复查', count: 3, note: '术后复查阶段', doctorId: 'DR-03' },
];

export const demoMaterials: DemoMaterial[] = [
  { id: 'MT-01', name: '一次性口腔检查盘', unit: '盒', unitPriceCents: 12800 },
  { id: 'MT-02', name: '树脂充填材料 A2', unit: '支', unitPriceCents: 8600 },
  { id: 'MT-03', name: '0.022 正畸托槽', unit: '套', unitPriceCents: 42000 },
  { id: 'MT-04', name: '一次性注射针头', unit: '盒', unitPriceCents: 3200 },
];

export const demoState = reactive({
  appointments: demoAppointments,
  visits: demoVisits,
  recalls: demoRecalls,
  bills: demoBills,
  payments: demoPayments,
  invoices: demoInvoices,
  inventory: demoInventory,
  purchases: demoPurchases,
  contacts: demoContacts,
  policies: demoPolicies,
  plans: demoPlans,
  paymentKeys: [] as string[],
  lastAction: '',
});

export function patientName(id: string): string {
  return demoPatients.find((p) => p.id === id)?.name ?? id;
}
export function doctorName(id: string): string {
  return demoDoctors.find((d) => d.id === id)?.name ?? id;
}
export function clinicName(id: string): string {
  return demoClinics.find((c) => c.id === id)?.name ?? id;
}
export function supplierName(id: string): string {
  return demoSuppliers.find((s) => s.id === id)?.name ?? id;
}
export function materialById(id: string): DemoMaterial | undefined {
  return demoMaterials.find((m) => m.id === id);
}
export function formatCents(cents: number): string {
  return `¥ ${(cents / 100).toFixed(2)}`;
}
export function billRemainingCents(bill: DemoBill): number {
  return Math.max(0, bill.totalCents - bill.paidCents);
}
export function parseMoneyToCents(value: string): number | null {
  const text = value.trim().replace(/^¥\s*/, '');
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null;
  const [whole, fraction = ''] = text.split('.');
  return Number(whole) * 100 + Number((fraction + '00').slice(0, 2));
}

export interface DemoAppointmentInput {
  date: string;
  time: string;
  patientId: string;
  doctorId: string;
  clinicId: string;
  item: string;
  source: string;
}
export interface DemoResult { ok: boolean; message: string; id?: string; }

export function addDemoAppointment(input: DemoAppointmentInput): DemoResult {
  if (!input.date || !/^\d{4}-\d{2}-\d{2}$/.test(input.date)) return { ok: false, message: '请选择有效日期。' };
  if (!/^\d{2}:\d{2}$/.test(input.time)) return { ok: false, message: '请选择时段。' };
  if (!demoPatients.some((p) => p.id === input.patientId)) return { ok: false, message: '请选择患者。' };
  const doctor = demoDoctors.find((d) => d.id === input.doctorId);
  if (!doctor) return { ok: false, message: '请选择医生。' };
  if (!demoClinics.some((c) => c.id === input.clinicId)) return { ok: false, message: '请选择门店。' };
  if (doctor.clinicId !== input.clinicId) return { ok: false, message: '该医生不在所选门店的演示团队中。' };
  if (!demoServices.some((s) => s.name === input.item.trim())) return { ok: false, message: '请选择可用项目。' };
  const duplicate = demoState.appointments.some((a) =>
    a.patientId === input.patientId && a.doctorId === input.doctorId && a.time === `${input.date} ${input.time}`
    && !a.status.includes('取消'));
  if (duplicate) return { ok: false, message: '同一患者、医生和时段已有预约，请勿重复提交。' };
  const id = `AP-${Date.now().toString(36).toUpperCase()}`;
  demoState.appointments.unshift({
    id, time: `${input.date} ${input.time}`, patientId: input.patientId, doctorId: input.doctorId,
    clinicId: input.clinicId, item: input.item.trim(), status: '待确认', source: input.source, chair: '待分配',
  });
  demoState.lastAction = `${id} 已加入本地演示预约，未写入后台`;
  return { ok: true, message: demoState.lastAction, id };
}

export function addDemoPlan(patientId: string, item: string, count: number, note: string, doctorId = 'DR-01'): DemoResult {
  if (!patientName(patientId) || !patientId) return { ok: false, message: '请选择患者。' };
  if (!item.trim()) return { ok: false, message: '请填写治疗项目。' };
  if (!Number.isInteger(count) || count < 1 || count > 99) return { ok: false, message: '计划次数须为 1–99 的整数。' };
  const id = `TP-${Date.now().toString(36).toUpperCase()}`;
  demoState.plans.unshift({ id, patientId, item: item.trim(), count, note: note.trim(), doctorId });
  demoState.lastAction = `${id} 已加入本地治疗计划，未写入后台`;
  return { ok: true, message: demoState.lastAction, id };
}

export function addDemoPurchase(supplierId: string, materialId: string, quantity: number, unitPriceCents: number): DemoResult {
  if (!supplierName(supplierId) || !supplierId) return { ok: false, message: '请选择供应商。' };
  if (!materialById(materialId)) return { ok: false, message: '请选择物料。' };
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 1000) return { ok: false, message: '数量须为 1–1000 的整数。' };
  if (!Number.isInteger(unitPriceCents) || unitPriceCents < 1) return { ok: false, message: '单价必须大于 0。' };
  const total = quantity * unitPriceCents;
  const id = `PO-${Date.now().toString(36).toUpperCase()}`;
  demoState.purchases.unshift({ id, supplierId, date: '2026-10-27', amount: formatCents(total), status: '待验收（演示）' });
  demoState.lastAction = `${id} 已加入本地采购单，未写入后台`;
  return { ok: true, message: demoState.lastAction, id };
}

export function collectDemoPayment(billId: string, amountText: string, requestKey = ''): DemoResult {
  const bill = demoState.bills.find((b) => b.id === billId);
  if (!bill) return { ok: false, message: '账单不存在。' };
  if (bill.paidCents >= bill.totalCents) return { ok: false, message: '该账单已付清，不能重复收款。' };
  const key = requestKey.trim();
  if (key && demoState.paymentKeys.includes(key)) return { ok: false, message: '该收款请求已处理，请勿重复提交。' };
  const cents = parseMoneyToCents(amountText);
  if (cents === null || cents <= 0) return { ok: false, message: '请输入大于 0 且最多两位小数的收款金额。' };
  const remaining = billRemainingCents(bill);
  if (cents > remaining) return { ok: false, message: `收款金额不能超过待付金额 ${formatCents(remaining)}。` };
  if (key) demoState.paymentKeys.push(key);
  bill.paidCents += cents;
  bill.status = bill.paidCents >= bill.totalCents ? '已收款（演示）' : '部分收款（演示）';
  demoState.payments.unshift({
    id: `PM-${Date.now().toString(36).toUpperCase()}`, billId, date: '2026-10-27',
    method: '演示-本地收款', amount: formatCents(cents), status: '演示记录',
  });
  demoState.lastAction = `${billId} 已模拟收款 ${formatCents(cents)}，未发起真实支付，也未生成发票`;
  return { ok: true, message: demoState.lastAction, id: billId };
}

export function runDemoAction(action: string, id: string): string {
  if (action === 'checkin' || action === 'cancel' || action === 'confirm') {
    const row = demoState.appointments.find((a) => a.id === id);
    if (!row) return '找不到该预约，操作未执行。';
    if (row.status.includes('取消') || row.status.includes('完成')) {
      return '已取消或已完成的预约不能继续操作。';
    }
    if (action === 'checkin') {
      if (row.status !== '已确认') return '只有“已确认”的预约可以签到。';
      row.status = '候诊';
      demoState.lastAction = `${id} 已模拟签到，未写入后台`;
      return demoState.lastAction;
    }
    if (action === 'confirm') {
      if (row.status !== '待确认') return '只有“待确认”的预约可以确认。';
      row.status = '已确认';
      demoState.lastAction = `${id} 已模拟确认，未写入后台`;
      return demoState.lastAction;
    }
    if (row.status !== '待确认' && row.status !== '已确认') return '当前状态不能取消预约。';
    row.status = '已取消（演示）';
    demoState.lastAction = `${id} 已模拟取消，未写入后台`;
    return demoState.lastAction;
  }
  if (action === 'accept') {
    const row = demoState.purchases.find((p) => p.id === id);
    if (!row) return '找不到该采购单，操作未执行。';
    if (row.status !== '待验收（演示）') return '只有待验收的采购单可以验收。';
    row.status = '已验收（演示）';
    demoState.lastAction = `${id} 已模拟验收，未写入后台`;
    return demoState.lastAction;
  }
  demoState.lastAction = `${id} 演示操作已记录在本地，未写入后台`;
  return demoState.lastAction;
}
