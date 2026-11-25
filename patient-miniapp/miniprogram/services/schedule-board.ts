import type { RealBookingOverview } from './booking-api';

export interface BoardShift {
  key: string;
  branchId: string;
  branchName: string;
  dentistName: string;
  timeRange: string;
  available: boolean;
}
export interface BoardDay {
  date: string;
  label: string;
  morning: BoardShift[];
  afternoon: BoardShift[];
  fullDay: BoardShift[];
}
export interface BoardService {
  id: string;
  name: string;
  days: BoardDay[];
}

function local(value: string): { date: string; time: string; hour: number } | null {
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return null;
  const d = new Date(instant.getTime() + 8 * 3600000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`,
    time: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`,
    hour: d.getUTCHours(),
  };
}
function weekday(value: string): string {
  const date = new Date(`${value}T00:00:00Z`);
  return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getUTCDay()];
}

/** 只用实际 availability 关联的服务与已发布排班；空 slots 不表示约满。 */
export function buildScheduleBoard(overview: RealBookingOverview, branchId = ''): BoardService[] {
  const schedules = new Map(overview.schedules.map((schedule) => [schedule.schedule_id, schedule]));
  const services = new Map(overview.services.map((service) => [service.item_id, service.name]));
  const board = new Map<string, BoardService>();
  for (const entry of overview.availability) {
    const schedule = schedules.get(entry.schedule_id);
    const name = services.get(entry.item_id);
    if (!schedule || !name || (branchId && schedule.branch_id !== branchId)) continue;
    const start = local(schedule.starts_at);
    const end = local(schedule.ends_at);
    if (!start || !end || start.date !== end.date) continue;
    const service = board.get(entry.item_id) ?? { id: entry.item_id, name, days: [] };
    let day = service.days.find((row) => row.date === start.date);
    if (!day) {
      day = { date: start.date, label: `${start.date.slice(5).replace('-', '/')} ${weekday(start.date)}`, morning: [], afternoon: [], fullDay: [] };
      service.days.push(day);
    }
    const shift: BoardShift = {
      key: `${entry.item_id}:${schedule.schedule_id}`,
      branchId: schedule.branch_id,
      branchName: schedule.branch_name,
      dentistName: schedule.dentist_name,
      timeRange: `${start.time}–${end.time}`,
      available: entry.slots.length > 0,
    };
    const period = start.hour < 12 && end.hour > 12 ? day.fullDay : start.hour < 12 ? day.morning : day.afternoon;
    if (!period.some((row) => row.key === shift.key)) period.push(shift);
    board.set(entry.item_id, service);
  }
  return [...board.values()].map((service) => ({ ...service,
    days: service.days.sort((a, b) => a.date.localeCompare(b.date)).map((day) => ({ ...day,
      morning: day.morning.sort((a, b) => a.timeRange.localeCompare(b.timeRange)),
      afternoon: day.afternoon.sort((a, b) => a.timeRange.localeCompare(b.timeRange)),
      fullDay: day.fullDay.sort((a, b) => a.timeRange.localeCompare(b.timeRange)),
    })),
  }));
}
