import { describe, expect, it } from 'vitest';
import { deriveDates, deriveServices, visitTypeFromHistory } from '../miniprogram/services/booking-flow';
import type { RealBookingOverview } from '../miniprogram/services/booking-api';

const overview: RealBookingOverview = {
  schedules: [
    { schedule_id: 's1', starts_at: '2026-11-23T01:00:00Z', ends_at: '2026-11-23T04:00:00Z', branch_id: '1', branch_name: '门店一', dentist_id: 'd1', dentist_name: '医生一' },
    { schedule_id: 's2', starts_at: '2026-11-23T06:00:00Z', ends_at: '2026-11-23T09:00:00Z', branch_id: '1', branch_name: '门店一', dentist_id: 'd2', dentist_name: '医生二' },
    { schedule_id: 's3', starts_at: '2026-11-24T01:00:00Z', ends_at: '2026-11-24T04:00:00Z', branch_id: '2', branch_name: '门店二', dentist_id: 'd3', dentist_name: '医生三' },
  ],
  services: [
    { item_id: '1', name: '检查', minutes: 30, price: '80.00' },
    { item_id: '2', name: '洁牙', minutes: 45, price: '180.00' },
    { item_id: '3', name: '仅其他门店', minutes: 30, price: '50.00' },
  ],
  availability: [
    { schedule_id: 's1', item_id: '1', duration_minutes: 30, slots: ['2026-11-23T01:00:00Z', '2026-11-23T01:30:00Z'] },
    { schedule_id: 's2', item_id: '1', duration_minutes: 30, slots: ['2026-11-23T06:30:00Z'] },
    { schedule_id: 's1', item_id: '2', duration_minutes: 45, slots: [] },
    { schedule_id: 's3', item_id: '3', duration_minutes: 30, slots: ['2026-11-24T01:00:00Z'] },
  ],
  appointments: [],
};

describe('预约流程派生数据', () => {
  it('只把当前门店 availability 中出现的项目展示为可约项目', () => {
    expect(deriveServices(overview, '1').map((item) => item.id)).toEqual(['1', '2']);
    expect(deriveServices(overview, '2').map((item) => item.id)).toEqual(['3']);
  });

  it('按上海日期和上午/下午分组，保留空 availability 的已无可约状态', () => {
    const first = deriveDates(overview, '1', '1');
    expect(first.map((date) => date.date)).toEqual(['2026-11-23']);
    expect(first[0].morning.map((slot) => slot.time)).toEqual(['09:00', '09:30']);
    expect(first[0].afternoon.map((slot) => slot.time)).toEqual(['14:30']);
    expect(first[0].morningStatus).toBe('available');
    expect(first[0].afternoonStatus).toBe('available');

    const second = deriveDates(overview, '1', '2');
    expect(second).toHaveLength(1);
    expect(second[0].morningStatus).toBe('unavailable');
    expect(second[0].afternoonStatus).toBe('unavailable');
    const third = deriveDates(overview, '2', '3');
    expect(third[0].morningStatus).toBe('available');
    expect(third[0].afternoonStatus).toBe('none');
  });

  it('没有实际 availability 的日期不补造', () => {
    expect(deriveDates(overview, '1', '3')).toEqual([]);
  });

  it('就诊类型只根据本人历史记录派生，不默认所有人为初诊', () => {
    expect(visitTypeFromHistory([])).toBe('初诊');
    expect(visitTypeFromHistory([{ visit_id: '1' }])).toBe('复诊');
  });
});
