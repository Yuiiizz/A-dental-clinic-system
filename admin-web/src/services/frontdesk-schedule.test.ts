import { describe, expect, it } from 'vitest';
import { frontdeskSchedule } from './frontdesk-schedule';
import type { BookingOverview } from './appointments';

describe('前台医生排班表', () => {
  it('按门店和上海日期区分上午、下午，排班不等于可约', () => {
    const overview = {
      branches: [], patient_count: 0, policies: [], appointments: [],
      services: [{ item_id: '1', name: '补牙', minutes: 30, price: '100' }],
      schedules: [
        { schedule_id: '1', branch_id: 'a', branch_name: '甲店', dentist_id: 'd1', dentist_name: '林医生', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z' },
        { schedule_id: '2', branch_id: 'a', branch_name: '甲店', dentist_id: 'd2', dentist_name: '周医生', starts_at: '2030-01-02T05:00:00Z', ends_at: '2030-01-02T09:00:00Z' },
        { schedule_id: '3', branch_id: 'b', branch_name: '乙店', dentist_id: 'd3', dentist_name: '陈医生', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z' },
      ],
      assistant_shifts: [
        { shift_id: 'a1', branch_id: 'a', staff_id: 's1', name: '小禾', job_title: '诊疗助手', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z' },
        { shift_id: 'a2', branch_id: 'a', staff_id: 's2', name: '小宁', job_title: '诊疗助手', starts_at: '2030-01-02T05:00:00Z', ends_at: '2030-01-02T09:00:00Z' },
        { shift_id: 'b1', branch_id: 'b', staff_id: 's3', name: '小岚', job_title: '诊疗助手', starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T04:00:00Z' },
      ],
      availability: [{ schedule_id: '1', item_id: '1', slots: ['2030-01-02T01:00:00Z'] }],
    } satisfies BookingOverview;
    const days = frontdeskSchedule(overview, 'a');
    expect(days).toHaveLength(1);
    expect(days[0]?.morning[0]).toMatchObject({ dentist: '林医生', time: '09:00–12:00', hasSlots: true, services: ['补牙'] });
    expect(days[0]?.afternoon[0]).toMatchObject({ dentist: '周医生', time: '13:00–17:00', hasSlots: false });
    expect(days[0]?.morning.some((shift) => shift.dentist === '陈医生')).toBe(false);
    expect(days[0]?.morningAssistants).toMatchObject([{ name: '小禾', time: '09:00–12:00' }]);
    expect(days[0]?.afternoonAssistants).toMatchObject([{ name: '小宁', time: '13:00–17:00' }]);
  });
});
