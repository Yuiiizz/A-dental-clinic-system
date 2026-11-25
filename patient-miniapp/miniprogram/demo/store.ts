import { appointments as seedAppointments } from './data';
import type { Appointment } from './data';

const STORAGE_KEY = 'a-clinic.patient.demo-enabled';
let demoEnabled = false;
try {
  const stored = wx.getStorageSync(STORAGE_KEY);
  if (stored === '1' || stored === true) demoEnabled = true;
} catch {
  demoEnabled = false;
}

let nextId = 1;
let appointmentList: Appointment[] = seedAppointments.map((a) => ({ ...a }));

export interface AppointmentResult {
  ok: boolean;
  message: string;
  appointment?: Appointment;
}

export function isDemoEnabled(): boolean {
  return demoEnabled;
}

export function setDemoEnabled(value: boolean): void {
  demoEnabled = value;
  try { wx.setStorageSync(STORAGE_KEY, value ? '1' : '0'); } catch { /* noop */ }
}

export function listAppointments(): Appointment[] {
  return appointmentList.map((a) => ({ ...a }));
}

export function nextAppointment(): Appointment | null {
  return appointmentList
    .filter((a) => !a.status.includes('取消') && a.status !== '已完成')
    .sort((a, b) => `${a.date} ${a.time}`.localeCompare(`${b.date} ${b.time}`))[0] ?? null;
}

export function appointmentById(id: string): Appointment | undefined {
  return appointmentList.find((a) => a.id === id);
}

export function createAppointment(input: {
  date: string; time: string; clinic: string; doctor: string; item: string; source: string;
}): AppointmentResult {
  if (!demoEnabled) return { ok: false, message: '演示模式已关闭，不能创建演示预约。' };
  if (!input.date || !/^\d{4}-\d{2}-\d{2}$/.test(input.date)) return { ok: false, message: '日期无效。' };
  if (!/^\d{2}:\d{2}$/.test(input.time)) return { ok: false, message: '请选择时段。' };
  if (!input.clinic || !input.doctor || !input.item) return { ok: false, message: '请完整选择门店、医生和项目。' };
  const duplicate = appointmentList.some((a) =>
    a.date === input.date && a.time === input.time && a.doctor === input.doctor && !a.status.includes('取消'));
  if (duplicate) return { ok: false, message: '该医生该时段已有演示预约，请勿重复提交。' };
  const appointment: Appointment = {
    id: `AP-DEMO-${Date.now().toString(36).toUpperCase()}-${nextId++}`,
    date: input.date, time: input.time, clinic: input.clinic, doctor: input.doctor,
    item: input.item, status: '待确认', source: input.source,
  };
  appointmentList = [appointment, ...appointmentList];
  return { ok: true, message: `已生成演示预约 ${appointment.id}，未写入后台`, appointment };
}

function mutable(id: string): { row?: Appointment; error?: string } {
  if (!demoEnabled) return { error: '演示模式已关闭，不能操作演示预约。' };
  const row = appointmentList.find((a) => a.id === id);
  if (!row) return { error: '找不到该预约。' };
  if (row.status.includes('取消') || row.status === '已完成') return { error: '已取消或已完成的预约不能继续操作。' };
  return { row };
}

export function cancelAppointment(id: string): AppointmentResult {
  const { row, error } = mutable(id);
  if (error || !row) return { ok: false, message: error ?? '找不到该预约。' };
  row.status = '已取消（演示）';
  return { ok: true, message: '已取消演示预约，未写入后台。', appointment: { ...row } };
}

export function rescheduleAppointment(id: string, date: string, time: string): AppointmentResult {
  const { row, error } = mutable(id);
  if (error || !row) return { ok: false, message: error ?? '找不到该预约。' };
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) {
    return { ok: false, message: '请选择新的有效日期和时段。' };
  }
  if (row.date === date && row.time === time) return { ok: false, message: '新时段与当前时段相同。' };
  const duplicate = appointmentList.some((a) => a.id !== id && a.date === date && a.time === time && a.doctor === row.doctor && !a.status.includes('取消'));
  if (duplicate) return { ok: false, message: '该医生新时段已有预约，请选择其他时段。' };
  row.date = date;
  row.time = time;
  row.status = '待确认';
  return { ok: true, message: '改约已更新到本地演示预约，未写入后台。', appointment: { ...row } };
}
