import type { RealBookingOverview } from './booking-api';

export type FlowPeriod = 'morning' | 'afternoon';
export type FlowPeriodStatus = 'available' | 'unavailable' | 'none';

export interface FlowService {
  id: string;
  name: string;
  minutes: number;
  price: string;
}

export interface FlowSlot {
  scheduleId: string;
  dentistId: string;
  dentistName: string;
  startsAt: string;
  endsAt: string;
  slot: string;
  time: string;
  period: FlowPeriod;
}

export interface FlowDate {
  date: string;
  label: string;
  weekday: string;
  morning: FlowSlot[];
  afternoon: FlowSlot[];
  morningStatus: FlowPeriodStatus;
  afternoonStatus: FlowPeriodStatus;
  selectable: boolean;
}

function shanghaiParts(value: string): { date: string; time: string; hour: number } {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return { date: '', time: '', hour: -1 };
  const local = new Date(date.getTime() + 8 * 3600000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    date: `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}`,
    time: `${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}`,
    hour: local.getUTCHours(),
  };
}

function weekday(value: string): string {
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return '';
  return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getUTCDay()];
}

function periodOf(hour: number): FlowPeriod {
  return hour < 12 ? 'morning' : 'afternoon';
}

/** 只展示当前门店实际出现在 availability 中的项目，不从全量服务目录臆造。 */
export function deriveServices(overview: RealBookingOverview, branchId: string): FlowService[] {
  const scheduleIds = new Set(overview.schedules
    .filter((schedule) => schedule.branch_id === branchId)
    .map((schedule) => schedule.schedule_id));
  const itemIds = new Set(overview.availability
    .filter((entry) => scheduleIds.has(entry.schedule_id))
    .map((entry) => entry.item_id));
  return overview.services
    .filter((service) => itemIds.has(service.item_id))
    .map((service) => ({
      id: service.item_id,
      name: service.name,
      minutes: service.minutes,
      price: service.price,
    }));
}

/**
 * 以 availability 的实际日期为准，不补齐未返回的日期。
 * 上午/下午只根据已返回的 slot 和门店排班区间判断；无班次显示 none，有班次但无 slot 显示 unavailable。
 */
export function deriveDates(
  overview: RealBookingOverview,
  branchId: string,
  itemId: string,
): FlowDate[] {
  const schedules = overview.schedules.filter((schedule) => schedule.branch_id === branchId);
  const scheduleById = new Map(schedules.map((schedule) => [schedule.schedule_id, schedule]));
  const entries = overview.availability.filter((entry) => {
    const schedule = scheduleById.get(entry.schedule_id);
    return Boolean(schedule) && entry.item_id === itemId;
  });

  const byDate = new Map<string, { slots: FlowSlot[]; hasMorningShift: boolean; hasAfternoonShift: boolean }>();
  const shifts = new Map<string, { morning: boolean; afternoon: boolean }>();
  for (const schedule of schedules) {
    const start = shanghaiParts(schedule.starts_at);
    const end = shanghaiParts(schedule.ends_at);
    if (!start.date) continue;
    const entry = shifts.get(start.date) ?? { morning: false, afternoon: false };
    if (start.hour < 12 && end.hour >= 9) entry.morning = true;
    if (end.hour > 12 || (start.hour >= 12 && end.hour > start.hour)) entry.afternoon = true;
    shifts.set(start.date, entry);
  }

  for (const availability of entries) {
    const schedule = scheduleById.get(availability.schedule_id);
    if (!schedule) continue;
    const start = shanghaiParts(schedule.starts_at);
    if (!start.date) continue;
    const record = byDate.get(start.date) ?? { slots: [], hasMorningShift: false, hasAfternoonShift: false };
    const shift = shifts.get(start.date);
    record.hasMorningShift = record.hasMorningShift || Boolean(shift?.morning);
    record.hasAfternoonShift = record.hasAfternoonShift || Boolean(shift?.afternoon);
    for (const value of availability.slots) {
      const slot = shanghaiParts(value);
      if (!slot.date || slot.hour < 0) continue;
      if (record.slots.some((existing) => existing.scheduleId === schedule.schedule_id && existing.slot === value)) continue;
      record.slots.push({
        scheduleId: schedule.schedule_id,
        dentistId: schedule.dentist_id,
        dentistName: schedule.dentist_name,
        startsAt: schedule.starts_at,
        endsAt: schedule.ends_at,
        slot: value,
        time: slot.time,
        period: periodOf(slot.hour),
      });
    }
    byDate.set(start.date, record);
  }

  return [...byDate.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, record]) => {
      const sortSlots = (a: FlowSlot, b: FlowSlot) => a.slot.localeCompare(b.slot);
      const morning = record.slots.filter((slot) => slot.period === 'morning').sort(sortSlots);
      const afternoon = record.slots.filter((slot) => slot.period === 'afternoon').sort(sortSlots);
      return {
        date,
        label: `${date.slice(5).replace('-', '/')} ${weekday(date)}`,
        weekday: weekday(date),
        morning,
        afternoon,
        morningStatus: morning.length > 0 ? 'available' : record.hasMorningShift ? 'unavailable' : 'none',
        afternoonStatus: afternoon.length > 0 ? 'available' : record.hasAfternoonShift ? 'unavailable' : 'none',
        selectable: morning.length > 0 || afternoon.length > 0,
      };
    });
}

export function visitTypeFromHistory(visits: readonly unknown[]): '初诊' | '复诊' {
  return visits.length > 0 ? '复诊' : '初诊';
}
