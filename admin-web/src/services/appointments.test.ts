import { describe, expect, it, vi } from 'vitest';
import { createAppointmentApi } from './appointments';
import type { HttpClient } from './http';

describe('前台签到接口', () => {
  it('按授权门店生成短效签到码，响应只保留码与到期时间', async () => {
    const post=vi.fn().mockResolvedValue({data:{code:'signed-code',expires_at:'2030-01-02T01:00:00Z'}});
    const api=createAppointmentApi({post} as unknown as HttpClient);
    expect(await api.issueCheckinCode('1')).toEqual({code:'signed-code',expires_at:'2030-01-02T01:00:00Z'});
    expect(post).toHaveBeenCalledWith('/staff/branches/1/checkin-code',undefined,{idempotencyKey:expect.any(String)});
  });
  it('按门店与日期读取最小候诊票，不读取姓名或电话', async () => {
    const get = vi.fn().mockResolvedValue({ data: [{ ticket_id: '9', appointment_id: '7',
      queue_date: '2030-01-02', queue_no: 12, state: '候诊', checkin_channel: '前台' }] });
    const api = createAppointmentApi({ get } as unknown as HttpClient);
    const tickets = await api.queue('1', '2030-01-02');
    expect(get).toHaveBeenCalledWith('/staff/queue', { query: { branch_id: '1', date: '2030-01-02' } });
    expect(tickets).toEqual([{ ticket_id: '9', appointment_id: '7', queue_date: '2030-01-02', queue_no: 12,
      state: '候诊', checkin_channel: '前台' }]);
  });
  it('提交版本、现场依据和稳定幂等键，读取候诊号码', async () => {
    const post = vi.fn().mockResolvedValue({ data: {
      ticket_id: '9', appointment_id: '7', queue_date: '2030-01-02',
      queue_no: 12, state: '候诊', checkin_channel: '前台',
    } });
    const api = createAppointmentApi({ post } as unknown as HttpClient);
    const input = { id: '7', expectedVersion: 2, verificationBasis: '  现场核对就诊凭据  ', exceptionReason: '  人工确认提前到店  ' };
    const ticket = await api.checkin(input);
    expect(ticket.queue_no).toBe(12);
    expect(post).toHaveBeenCalledWith('/staff/appointments/7/checkin', {
      expected_version: 2, verification_basis: '现场核对就诊凭据', exception_reason: '人工确认提前到店',
    }, { idempotencyKey: expect.any(String) });
  });

  it('响应丢失后同内容手动重试沿用原键', async () => {
    const post = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ data: { ticket_id: '9', appointment_id: '7', queue_date: '2030-01-02', queue_no: 12, state: '候诊', checkin_channel: '前台' } });
    const api = createAppointmentApi({ post } as unknown as HttpClient);
    const input = { id: '7', expectedVersion: 2, verificationBasis: '现场核对就诊凭据' };
    await expect(api.checkin(input)).rejects.toThrow('network');
    await api.checkin(input);
    expect(post.mock.calls[0]?.[2]?.idempotencyKey).toBe(post.mock.calls[1]?.[2]?.idempotencyKey);
  });
});

describe('停诊协调人工联系', () => {
  it('读取当前门店的真实待联系清单并传递分页游标', async () => {
    const list=vi.fn().mockResolvedValue({items:[{
      appointment_id:'7',starts_at:'2030-01-02T01:00:00Z',last_contact_id:null,
      last_contacted_at:null,next_contact_at:null,acknowledged:false,
    }],nextCursor:'7'});
    const api=createAppointmentApi({list} as unknown as HttpClient);
    const page=await api.contactTodos('1','5');
    expect(page.items[0]?.appointment_id).toBe('7');
    expect(page.nextCursor).toBe('7');
    expect(list).toHaveBeenCalledWith('/staff/contacts/due',
      {query:{branch_id:'1',limit:'20',cursor:'5'}});
  });
  const saved = { contact_id: '91', appointment_id: '7', kind: '人工联系', channel: '电话',
    state: '已联系', result: '未接通', contacted_at: '2030-01-02T01:01:00Z',
    acknowledged: false, next_contact_at: null, staff_id: '2' };

  it('分页读取本店联系记录，并保留未确认知晓状态', async () => {
    const list = vi.fn().mockResolvedValue({ items: [saved], nextCursor: null });
    const api = createAppointmentApi({ list } as unknown as HttpClient);
    expect(await api.contacts('1')).toEqual([saved]);
    expect(list).toHaveBeenCalledWith('/staff/contacts', { query: { branch_id: '1', limit: '100' } });
  });

  it('结果不明时人工重试复用同一幂等键，不自行声明患者已知晓', async () => {
    const post = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ data: saved });
    const api = createAppointmentApi({ post } as unknown as HttpClient);
    const input = { appointmentId: '7', channel: '电话' as const, result: ' 未接通 ' };
    await expect(api.recordContact(input)).rejects.toThrow('network');
    expect(await api.recordContact(input)).toEqual(saved);
    expect(post.mock.calls[0]?.[2]?.idempotencyKey).toBe(post.mock.calls[1]?.[2]?.idempotencyKey);
    expect(post.mock.calls[1]?.[1]).toEqual({ kind: '人工联系', channel: '电话',
      appointment_id: '7', result: '未接通' });
  });
});

describe('预约协调方案', () => {
  const change={change_id:'8',original_id:'7',proposed_id:'9',state:'已同意',version:2,
    reason:'医生停诊',branch_id:'1',dentist_id:'3',starts_at:'2030-01-02T02:00:00Z',
    ends_at:'2030-01-02T02:30:00Z',candidate_state:'待确认',applied:false};
  it('读取本店方案，答复与落实是不同操作', async () => {
    const get=vi.fn().mockResolvedValue({data:[change]});
    const post=vi.fn().mockResolvedValue({data:change});
    const api=createAppointmentApi({get,post} as unknown as HttpClient);
    expect(await api.changes('1')).toEqual([change]);
    await api.answerChange('8',true,1,'患者电话明确同意');
    await api.applyChange('8',2);
    expect(get).toHaveBeenCalledWith('/staff/changes',{query:{branch_id:'1'}});
    expect(post.mock.calls[0]?.[0]).toBe('/staff/changes/8/response');
    expect(post.mock.calls[0]?.[1]).toEqual({accept:true,expected_version:1,confirmation_basis:'患者电话明确同意'});
    expect(post.mock.calls[1]?.[0]).toBe('/staff/changes/8/apply');
    expect(post.mock.calls[1]?.[1]).toEqual({expected_version:2});
  });
  it('拟方案网络结果不明时同内容保留幂等键', async () => {
    const post=vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({data:change});
    const api=createAppointmentApi({post} as unknown as HttpClient);
    const candidate={scheduleId:'2',itemId:'1',startsAt:'2030-01-02T02:00:00Z',policyId:'4'};
    await expect(api.proposeChange('7',candidate,'医生停诊')).rejects.toThrow('network');
    await api.proposeChange('7',candidate,'医生停诊');
    expect(post.mock.calls[0]?.[2]?.idempotencyKey).toBe(post.mock.calls[1]?.[2]?.idempotencyKey);
  });
  it('撤回方案携带原因与版本，结果不明后同内容沿用幂等键', async () => {
    const post=vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({data:change});
    const api=createAppointmentApi({post} as unknown as HttpClient);
    await expect(api.withdrawChange('8',2,' 候选医生临时无法接诊 ')).rejects.toThrow('network');
    await api.withdrawChange('8',2,' 候选医生临时无法接诊 ');
    expect(post.mock.calls[0]?.[0]).toBe('/staff/changes/8/withdraw');
    expect(post.mock.calls[0]?.[1]).toEqual({expected_version:2,reason:'候选医生临时无法接诊'});
    expect(post.mock.calls[0]?.[2]?.idempotencyKey).toBe(post.mock.calls[1]?.[2]?.idempotencyKey);
  });
});
