// 患者端本地演示数据：仅用于界面演示，不写入后台，也不代表真实预约、支付或发票。
export interface Clinic {
  id: string;
  name: string;
  area: string;
  address: string;
  phone: string;
  hours: string;
  tags: string[];
  intro: string;
}
export interface Doctor {
  id: string;
  name: string;
  title: string;
  clinic: string;
  specialties: string;
  schedule: string[];
  intro: string;
}
export interface Appointment {
  id: string;
  date: string;
  time: string;
  clinic: string;
  doctor: string;
  item: string;
  status: string;
  source: string;
}
export interface Visit {
  id: string;
  date: string;
  clinic: string;
  doctor: string;
  diagnosis: string;
  items: string;
}
export interface Recall {
  id: string;
  due: string;
  doctor: string;
  reason: string;
  status: string;
}
export interface Bill {
  id: string;
  date: string;
  item: string;
  amount: string;
  status: string;
}
export interface Payment {
  id: string;
  date: string;
  method: string;
  amount: string;
  status: string;
}
export interface Invoice {
  id: string;
  date: string;
  title: string;
  amount: string;
  status: string;
}

export const DEMO_NOTICE = '演示数据 · 本地模拟，未写入后台';

export const clinics: Clinic[] = [
  { id: 'CL-01', name: '云栖总院', area: '西湖区', address: '云栖路 18 号 2 层', phone: '0571-8800 2100', hours: '周一至周日 09:00–18:00', tags: ['显微根管', '正畸', '儿牙'], intro: '课程虚构的综合门店，展示初诊评估、儿童口腔、修复与正畸等服务如何协同预约。这里的地址、电话和营业时间均为样例。' },
  { id: 'CL-02', name: '滨江分院', area: '滨江区', address: '江晖路 66 号 1 层', phone: '0571-8800 2200', hours: '周一至周日 09:00–18:00', tags: ['种植', '口腔外科', '牙周'], intro: '课程虚构的社区门店，展示牙周维护、洁牙和种植术后复查等连续就诊场景。实际可约项目与医生以真实排班为准。' },
  { id: 'CL-03', name: '城东分院', area: '上城区', address: '景芳路 28 号 1 层', phone: '0571-8800 2300', hours: '周一至周六 09:00–17:30', tags: ['初诊检查', '牙体修复', '洁牙'], intro: '课程虚构门店，便于演示从选择门店到选择服务的完整路径；页面资料不代表真实经营场所。' },
  { id: 'CL-04', name: '城北分院', area: '拱墅区', address: '湖州街 36 号 2 层', phone: '0571-8800 2400', hours: '周二至周日 09:00–17:30', tags: ['儿童口腔', '正畸评估', '复诊'], intro: '课程虚构门店，展示家庭成员在不同门店浏览医生和复诊信息的界面。预约能力仍以后台已发布排班为准。' },
];

export const doctors: Doctor[] = [
  { id: 'DR-01', name: '林知夏', title: '主治医师', clinic: '云栖总院', specialties: '牙体牙髓 · 显微根管', schedule: ['周二全天', '周三上午', '周四下午'], intro: '擅长显微根管治疗与牙周维护，复诊优先原主治。' },
  { id: 'DR-02', name: '周屿', title: '副主任医师', clinic: '云栖总院', specialties: '正畸 · 儿童早期矫治', schedule: ['周二全天', '周三全天', '周六上午'], intro: '固定与隐形正畸，儿童早期矫治评估。' },
  { id: 'DR-03', name: '陈望', title: '主治医师', clinic: '滨江分院', specialties: '种植 · 口腔外科', schedule: ['周二上午', '周四上午', '周五下午'], intro: '单颗/多颗种植修复与术后管理。' },
  { id: 'DR-04', name: '沈禾', title: '医师', clinic: '滨江分院', specialties: '牙周 · 洁牙', schedule: ['周二下午', '周五上午', '周日全天'], intro: '牙周基础治疗与口腔卫生指导。' },
  { id: 'DR-05', name: '许清禾', title: '主治医师', clinic: '云栖总院', specialties: '儿童口腔 · 窝沟封闭', schedule: ['周一下午', '周三上午', '周六全天'], intro: '儿童口腔健康管理与防龋评估，治疗前说明适应证。' },
  { id: 'DR-06', name: '唐予安', title: '主治医师', clinic: '云栖总院', specialties: '修复 · 全瓷冠', schedule: ['周一全天', '周四全天', '周日上午'], intro: '牙体缺损修复与牙冠维护，按检查结果制定方案。' },
  { id: 'DR-07', name: '贺景行', title: '副主任医师', clinic: '滨江分院', specialties: '种植 · 复杂拔牙', schedule: ['周二全天', '周五上午', '周六下午'], intro: '种植和复杂牙拔除评估，术前需完成个体化检查。' },
  { id: 'DR-08', name: '叶澄', title: '医师', clinic: '滨江分院', specialties: '牙体牙髓 · 补牙', schedule: ['周一上午', '周三全天', '周五上午'], intro: '龋病治疗与树脂修复，重视术后复查和日常维护。' },
  { id: 'DR-09', name: '顾予川', title: '医师', clinic: '城东分院', specialties: '初诊检查 · 龋病评估', schedule: ['周一上午', '周三下午'], intro: '虚构人物，用于展示初诊问诊、检查与后续治疗建议的流程。' },
  { id: 'DR-10', name: '宋初晴', title: '主治医师', clinic: '城东分院', specialties: '牙体修复 · 洁牙', schedule: ['周二上午', '周五下午'], intro: '虚构人物，用于展示修复与定期维护的预约页面。' },
  { id: 'DR-11', name: '陆知远', title: '医师', clinic: '城北分院', specialties: '儿童口腔 · 防龋', schedule: ['周三上午', '周六上午'], intro: '虚构人物，用于展示儿童口腔检查和预防服务的介绍方式。' },
  { id: 'DR-12', name: '温澄', title: '主治医师', clinic: '城北分院', specialties: '正畸评估 · 复诊', schedule: ['周四下午', '周日上午'], intro: '虚构人物，用于展示正畸评估与定期复诊的选择流程。' },
];

export const appointments: Appointment[] = [
  { id: 'AP-1001', date: '2026-10-28', time: '09:30', clinic: '云栖总院', doctor: '林知夏', item: '牙周复查', status: '已确认', source: '患者自助' },
  { id: 'AP-1002', date: '2026-11-04', time: '14:00', clinic: '云栖总院', doctor: '周屿', item: '正畸复诊', status: '待确认', source: '患者自助' },
];

export const visits: Visit[] = [
  { id: 'VS-01', date: '2026-09-18', clinic: '云栖总院', doctor: '林知夏', diagnosis: '慢性牙周炎维护期', items: '龈下刮治 · 牙周检查' },
  { id: 'VS-02', date: '2026-08-02', clinic: '云栖总院', doctor: '周屿', diagnosis: '正畸复诊', items: '弓丝调整 · 口腔卫生指导' },
];

export const recalls: Recall[] = [
  { id: 'RC-01', due: '2026-11-02', doctor: '林知夏', reason: '牙周维护 6 周复诊', status: '待预约' },
  { id: 'RC-02', due: '2026-10-30', doctor: '周屿', reason: '正畸加力', status: '已预约' },
];

export const bills: Bill[] = [
  { id: 'BL-01', date: '2026-09-18', item: '牙周维护', amount: '¥ 680.00', status: '已收款（演示）' },
  { id: 'BL-02', date: '2026-10-28', item: '牙周复查', amount: '¥ 120.00', status: '待收款（演示）' },
];

export const payments: Payment[] = [
  { id: 'PM-01', date: '2026-09-18', method: '演示-微信支付', amount: '¥ 680.00', status: '演示记录' },
];

export const invoices: Invoice[] = [
  { id: 'IV-01', date: '2026-09-18', title: '个人', amount: '¥ 680.00', status: '演示发票（非正式）' },
];

export const items = ['初诊检查', '牙周维护', '牙周复查', '正畸复诊', '种植术后复查', '洁牙', '儿童窝沟封闭', '儿牙涂氟', '全瓷冠复诊', '树脂补牙', '拔牙评估'];
export const slots = ['09:00', '09:30', '10:00', '10:30', '14:00', '14:30', '15:00'];

export function doctorById(id: string): Doctor | undefined {
  return doctors.find((d) => d.id === id);
}
