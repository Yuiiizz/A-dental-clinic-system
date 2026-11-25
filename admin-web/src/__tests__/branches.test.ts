import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { effectScope, nextTick } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import { useSessionStore } from '@/stores/session';
import { useBranches } from '@/composables/useBranches';
import { ApiError } from '@/services/http';
import { branchDetail, intervalFromInputs, type BranchDetail, type Opening } from '@/services/branches';
import BranchesView from '@/views/BranchesView.vue';

const api = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), create: vi.fn(), mutate: vi.fn(), getOpening: vi.fn(), putOpening: vi.fn() }));
vi.mock('@/services/branches', async load => ({ ...(await load<typeof import('@/services/branches')>()), createBranchApi: () => api }));
const detail = (id = '10', version = 1): BranchDetail => ({ branch_id: id, version, code: 'A', name: '合成门店', state: '草稿', manager_id: '1', address: '合成地址', phone: '000', license_no: 'TEST', license_valid_to: null, license_no_expiry_confirmed: false, opening_hours: '09:00—18:00', verified_by: null, verified_at: null, reauthentication_required: false });
const hours = (version = 1): Opening => ({ branch_id: '10', version, from_date: '2030-01-01', to_date: '2030-01-01', intervals: [] });
function deferred<T>() { let resolve!: (v:T) => void, reject!: (v:unknown) => void; const promise = new Promise<T>((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; }
function model() { const scope = effectScope(); const m = scope.run(() => useBranches(api))!; return {m, scope}; }
beforeEach(() => {
  setActivePinia(createPinia()); vi.resetAllMocks();
  const s = useSessionStore(); s.status='ready'; s.csrfToken='synthetic';
  s.me={account_id:'1',name:'合成负责人',identity_type:'staff',must_change_password:false,roles:[{role_code:'owner',branch_ids:['10']}]};
  api.list.mockResolvedValue({items:[detail()],nextCursor:null}); api.detail.mockResolvedValue(detail()); api.getOpening.mockResolvedValue(hours());
});
describe('门店数据与时间', () => {
  it('拒绝不完整详情', () => { expect(() => branchDetail({branch_id:'10'})).toThrow(); });
  it('午夜终点按上海日期拆分，拒绝跨日和无效日期', () => {
    expect(intervalFromInputs('2030-01-31','23:00','24:00').ends_at).toBe('2030-02-01T00:00:00+08:00');
    expect(() => intervalFromInputs('2030-01-31','23:00','01:00')).toThrow();
    expect(() => intervalFromInputs('2030-02-30','09:00','18:00')).toThrow();
  });
});
describe('门店页面操作隔离', () => {
  it('同一页面操作互斥，不能重复保存或切换目标', async () => {
    const {m,scope}=model(); await m.select('10'); const d=deferred<void>(); api.mutate.mockReturnValue(d.promise);
    const running=m.mutate('patch',{name:'新名称'});
    expect(await m.mutate('patch',{name:'重复'})).toBe(false); expect(await m.select('11')).toBe(false);
    d.resolve(); await running; expect(api.mutate).toHaveBeenCalledTimes(1); scope.stop();
  });
  it('切换会话清空列表，旧成功不能填回', async () => {
    const {m,scope}=model(); const d=deferred<{items:BranchDetail[];nextCursor:null}>(); api.list.mockReturnValue(d.promise);
    const pending=m.list(); useSessionStore().clearSession(); d.resolve({items:[detail()],nextCursor:null});
    expect(await pending).toBe(false); expect(m.rows.value).toEqual([]); scope.stop();
  });
  it('旧401不触发新身份核验', async () => {
    const {m,scope}=model(); const d=deferred<{items:BranchDetail[];nextCursor:null}>(); api.list.mockReturnValue(d.promise);
    const s=useSessionStore(), verify=vi.spyOn(s,'fetchMe'); const pending=m.list(); s.clearSession();
    d.reject(new ApiError('UNAUTHORIZED',401,'失效')); await pending; expect(verify).not.toHaveBeenCalled(); scope.stop();
  });
  it('409禁止旧版本继续保存，重新读取后才允许', async () => {
    const {m,scope}=model(); await m.select('10'); api.mutate.mockRejectedValue(new ApiError('VERSION_CONFLICT',409,'冲突'));
    await m.mutate('patch',{name:'草稿'}); expect(m.detail.value).toBeNull(); expect(m.error.value).toContain('草稿已保留');
    expect(await m.mutate('patch',{name:'草稿'})).toBe(false); expect(api.mutate).toHaveBeenCalledTimes(1); scope.stop();
  });
  it('创建结果不明时相同内容保留幂等键，修改内容才换键', async () => {
    const {m,scope}=model(); api.create.mockRejectedValue(new ApiError('TIMEOUT',0,'超时'));
    await m.create({name:'A'}); await m.create({name:'A'}); await m.create({name:'B'});
    expect(api.create.mock.calls[0][1]).toBe(api.create.mock.calls[1][1]); expect(api.create.mock.calls[1][1]).not.toBe(api.create.mock.calls[2][1]); scope.stop();
  });
  it('403清除缓存但不登出', async () => {
    const {m,scope}=model(); await m.list(); api.detail.mockRejectedValue(new ApiError('FORBIDDEN',403,'拒绝'));
    await m.select('10'); expect(m.rows.value).toEqual([]); expect(useSessionStore().status).toBe('ready'); scope.stop();
  });
  it('状态重放后GET当前资料而非使用历史响应', async () => {
    const {m,scope}=model(); await m.select('10'); api.mutate.mockResolvedValue(undefined); api.detail.mockResolvedValue({...detail('10',5),state:'营业'});
    await m.mutate('submit',{}); expect(m.detail.value?.version).toBe(5); expect(m.detail.value?.state).toBe('营业'); scope.stop();
  });
  it('营业时间使用读取的集合版本，失败不自动重试', async () => {
    const {m,scope}=model(); await m.select('10'); api.getOpening.mockResolvedValue(hours(7)); await m.readHours('2030-01-01','2030-01-01');
    api.putOpening.mockRejectedValue(new ApiError('TIMEOUT',0,'超时')); await m.saveHours([]);
    expect(api.putOpening.mock.calls[0][1].expected_version).toBe(7); expect(m.hours.value).toBeNull(); expect(api.putOpening).toHaveBeenCalledTimes(1); scope.stop();
  });
  it('创建引起授权变化后要求重新登录', async () => {
    const {m,scope}=model(); api.create.mockResolvedValue({...detail(),reauthentication_required:true});
    await m.create({name:'A'}); expect(useSessionStore().status).toBe('guest'); expect(m.message.value).toContain('门店已创建'); scope.stop();
  });
  it('页面卸载后旧结果不可回写', async () => {
    const {m,scope}=model(); const d=deferred<BranchDetail>(); api.detail.mockReturnValue(d.promise); const pending=m.select('10'); scope.stop(); d.resolve(detail());
    expect(await pending).toBe(false); expect(m.detail.value).toBeNull();
  });
});
describe('门店页面', () => {
  it('展示门店列表，选择后显示档案和时间配置，未确认不可保存', async () => {
    const wrapper=mount(BranchesView,{global:{stubs:{RouterLink:true}}}); await flushPromises();
    expect(wrapper.text()).toContain('合成门店');
    const configure=wrapper.findAll('button').find(b=>b.text()==='配置')!; await configure.trigger('click'); await flushPromises();
    expect(wrapper.text()).toContain('门店档案'); expect(wrapper.text()).toContain('实际营业时间');
    expect(wrapper.findAll('button').find(b=>b.text()==='保存资料')!.attributes('disabled')).toBeDefined(); wrapper.unmount();
  });
  it('身份失效立即隐藏表单与旧门店', async () => {
    const wrapper=mount(BranchesView,{global:{stubs:{RouterLink:true}}}); await flushPromises(); useSessionStore().clearSession(); await nextTick();
    expect(wrapper.text()).not.toContain('合成门店'); expect(wrapper.text()).toContain('请先确认登录身份'); wrapper.unmount();
  });
});
