import type { BookingOverview } from './appointments';

export interface Shift {
  id: string;
  dentist: string;
  time: string;
  services: string[];
  hasSlots: boolean;
}
export interface ScheduleDay {
  date: string;
  morning: Shift[];
  afternoon: Shift[];
  morningAssistants: Array<{ id: string; name: string; title: string; time: string }>;
  afternoonAssistants: Array<{ id: string; name: string; title: string; time: string }>;
}

function parts(value: string): { date: string; time: string; hour: number } {
  const entries = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(new Date(value));
  const p = Object.fromEntries(entries.map((part) => [part.type, part.value]));
  const hour = Number(p.hour) % 24;
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${String(hour).padStart(2, '0')}:${p.minute}`, hour };
}

export function frontdeskSchedule(overview: BookingOverview, branchId: string): ScheduleDay[] {
  const byDate = new Map<string, ScheduleDay>();
  const itemNames = new Map(overview.services.map((item) => [item.item_id, item.name]));
  for (const schedule of overview.schedules.filter((row) => row.branch_id === branchId)) {
    const start = parts(schedule.starts_at);
    const end = parts(schedule.ends_at);
    const day = byDate.get(start.date) ?? { date: start.date, morning: [], afternoon: [], morningAssistants: [], afternoonAssistants: [] };
    byDate.set(start.date, day);
    const availability = overview.availability.filter((row) => row.schedule_id === schedule.schedule_id);
    const shift: Shift = {
      id: schedule.schedule_id,
      dentist: schedule.dentist_name,
      time: `${start.time}–${end.time}`,
      services: [...new Set(availability.map((row) => itemNames.get(row.item_id)).filter((name): name is string => Boolean(name)))],
      hasSlots: availability.some((row) => row.slots.length > 0),
    };
    // 排班跨午间时同时出现在两栏，时间仍显示完整班次。
    if (start.hour < 12) day.morning.push(shift);
    if (end.hour > 12) day.afternoon.push(shift);
  }
  for (const row of overview.assistant_shifts.filter((shift) => shift.branch_id === branchId)) {
    const start = parts(row.starts_at);
    const end = parts(row.ends_at);
    const day = byDate.get(start.date) ?? { date: start.date, morning: [], afternoon: [], morningAssistants: [], afternoonAssistants: [] };
    byDate.set(start.date, day);
    const assistant = { id: row.shift_id, name: row.name, title: row.job_title, time: `${start.time}–${end.time}` };
    if (start.hour < 12) day.morningAssistants.push(assistant);
    if (end.hour > 12) day.afternoonAssistants.push(assistant);
  }
  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));
}
