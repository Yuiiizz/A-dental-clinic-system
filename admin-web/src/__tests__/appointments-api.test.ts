import { describe, expect, it, vi } from 'vitest';
import { createAppointmentApi } from '@/services/appointments';
import type { HttpClient } from '@/services/http';

describe('预约列表接口', () => {
  it('只按已核验的本店和用户编号读取档案摘要', async () => {
    const data = { patient_id: '3', name: '合成用户', birth_date: null, allergy_status: '未申报', source_channel: '转介绍', last_visit_dentist: null, last_visit_at: null };
    const get = vi.fn().mockResolvedValue({ data });
    const api = createAppointmentApi({ get } as unknown as HttpClient);
    expect(await api.patientSummary('1', '3')).toEqual(data);
    expect(get).toHaveBeenCalledWith('/staff/patients/3/summary', { query: { branch_id: '1' } });
  });
  it('按本店和患者编号按需读取接待资料', async () => {
    const data = { patient_id: '3', name: '合成用户', phone: '00000000003',
      appointments: [{ appointment_id: '9', starts_at: '2030-01-02T01:00:00Z', state: '已确认', visit_type: '初诊', dentist_name: null, item_name: '初诊检查' }],
      visits: [] };
    const get = vi.fn().mockResolvedValue({ data });
    const api = createAppointmentApi({ get } as unknown as HttpClient);
    expect(await api.receptionSummary('1', '3')).toEqual(data);
    expect(get).toHaveBeenCalledWith('/staff/patients/3/reception-summary', { query: { branch_id: '1' } });
  });
  it('按上海业务日期查询，允许尚无医生或项目的历史记录', async () => {
    const get = vi.fn().mockResolvedValue({ data: [{
      appointment_id: '8', patient_id: '3', patient_name: '教学用户', schedule_id: null,
      dentist_name: null, branch_id: '1', branch_name: '教学门店',
      starts_at: '2030-01-02T01:00:00Z', ends_at: '2030-01-02T01:30:00Z',
      visit_type: '初诊', channel: '现场', state: '待确认', version: 1,
      purpose: '门诊预约', item_name: null, previous_id: null,
      coordination_state: null, unavailability_alert: false,
    }] });
    const api = createAppointmentApi({ get } as unknown as HttpClient);
    const rows = await api.listForDate('2030-01-02');
    expect(get).toHaveBeenCalledWith('/staff/appointments', { query: { date: '2030-01-02' } });
    expect(rows).toHaveLength(1);
    expect(rows[0]?.dentist_name).toBe('');
    expect(rows[0]?.item_name).toBe('');
  });
});
