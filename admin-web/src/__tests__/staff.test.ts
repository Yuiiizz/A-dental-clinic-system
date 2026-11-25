import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { effectScope, nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { useSessionStore } from '@/stores/session';
import { useStaff } from '@/composables/useStaff';
import { ApiError } from '@/services/http';
import type { StaffApi, StaffDetail, StaffRow } from '@/services/staff';
import StaffMembersView from '@/views/StaffMembersView.vue';

const api = vi.hoisted(() => ({
  list: vi.fn(),
  detail: vi.fn(),
  create: vi.fn(),
}));
const credentialApi = vi.hoisted(() => ({ facts: vi.fn(), decide: vi.fn(), reviews: vi.fn(), add: vi.fn() }));

vi.mock('@/services/credential-review', async (load) => ({
  ...(await load<typeof import('@/services/credential-review')>()),
  createCredentialReviewApi: () => credentialApi,
}));

vi.mock('@/services/staff', async (load) => ({
  ...(await load<typeof import('@/services/staff')>()),
  createStaffApi: () => api,
}));

const row = (id = '1'): StaffRow => ({
  staff_id: id,
  name: `员工${id}`,
  staff_type: '其他人员',
  job_title: '员工',
  employment_state: '在岗',
  version: 1,
});
const detail = (id = '1', overrides: Partial<StaffDetail> = {}): StaffDetail => ({
  ...row(id),
  practice_no: id === '1' ? null : 'P-1',
  branches: [{ branch_id: '10', name: '合成门店', state: '营业', enabled: true, verified_at: null }],
  credentials: [
    {
      credential_id: 'c1',
      credential_type: 'other',
      verification_state: 'verified',
      valid_from: '2020-01-01',
      valid_to: null,
      no_expiry_confirmed: true,
      verified_at: null,
      version: 2,
      effective: true,
    },
  ],
  ...overrides,
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
}

function model() {
  const scope = effectScope();
  const m = scope.run(() => useStaff(api as unknown as StaffApi))!;
  return { m, scope };
}

function readySession() {
  const session = useSessionStore();
  session.status = 'ready';
  session.csrfToken = 'synthetic';
  session.me = {
    account_id: '1',
    name: '合成负责人',
    identity_type: 'staff',
    must_change_password: false,
    roles: [{ role_code: 'owner', branch_ids: ['10'] }],
  };
  return session;
}

beforeEach(() => {
  setActivePinia(createPinia());
  vi.resetAllMocks();
  readySession();
  api.list.mockResolvedValue({ items: [row()], nextCursor: null });
  api.detail.mockResolvedValue(detail());
  api.create.mockResolvedValue(detail());
  credentialApi.facts.mockResolvedValue({
    credential_id: '9', staff_id: '1', credential_type: 'dentist_practice',
    certificate_no: 'PRIVATE-123', valid_from: '2026-01-01', valid_to: '2030-01-01',
    no_expiry_confirmed: false, verification_state: 'pending', version: 1,
  });
  credentialApi.reviews.mockResolvedValue({ items: [], next_cursor: null });
  credentialApi.decide.mockResolvedValue(undefined);
  credentialApi.add.mockResolvedValue('12');
});

describe('员工数据与页面代次', () => {
  it('按 nextCursor 加载并去重，刷新失败保留上次已确认列表', async () => {
    const { m, scope } = model();
    api.list.mockResolvedValueOnce({ items: [row('1')], nextCursor: '1' });
    api.list.mockResolvedValueOnce({ items: [row('2')], nextCursor: null });
    expect(await m.load()).toBe(true);
    expect(m.nextCursor.value).toBe('1');
    await m.loadMore();
    expect(m.rows.value.map((x) => x.staff_id)).toEqual(['1', '2']);
    api.list.mockRejectedValueOnce(new ApiError('TIMEOUT', 0, '超时'));
    await m.refresh();
    expect(m.rows.value).toHaveLength(2);
    expect(m.listError.value).toContain('未确认');
    scope.stop();
  });

  it('A→B→A 只提交最后一次详情，旧详情不能回写', async () => {
    const { m, scope } = model();
    const a1 = deferred<StaffDetail>();
    const b = deferred<StaffDetail>();
    const a2 = deferred<StaffDetail>();
    api.detail.mockReturnValueOnce(a1.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(a2.promise);
    const p1 = m.select('1');
    const p2 = m.select('2');
    const p3 = m.select('1');
    a1.resolve(detail('1'));
    b.resolve(detail('2'));
    a2.resolve(detail('1', { job_title: '最新岗位' }));
    expect(await p1).toBe(false);
    expect(await p2).toBe(false);
    expect(await p3).toBe(true);
    expect(m.detail.value?.job_title).toBe('最新岗位');
    scope.stop();
  });

  it('会话变化清空列表和详情，旧请求结果被丢弃', async () => {
    const { m, scope } = model();
    const pending = deferred<{ items: StaffRow[]; nextCursor: null }>();
    api.list.mockReturnValue(pending.promise);
    const call = m.load();
    useSessionStore().clearSession();
    pending.resolve({ items: [row()], nextCursor: null });
    expect(await call).toBe(false);
    expect(m.rows.value).toEqual([]);
    scope.stop();
  });

  it('同内容超时保留幂等键，内容变化才换键且不自动重发', async () => {
    const { m, scope } = model();
    api.create.mockRejectedValue(new ApiError('TIMEOUT', 0, '超时'));
    const first = { name: 'A', staff_type: '其他人员' as const, job_title: '员工' };
    await m.create(first);
    await m.create(first);
    await m.create({ ...first, name: 'B' });
    expect(api.create.mock.calls[0][1]).toBe(api.create.mock.calls[1][1]);
    expect(api.create.mock.calls[1][1]).not.toBe(api.create.mock.calls[2][1]);
    expect(api.create).toHaveBeenCalledTimes(3);
    scope.stop();
  });

  it('建档请求进行中不能重复提交', async () => {
    const { m, scope } = model();
    const pending = deferred<StaffDetail>();
    api.create.mockReturnValue(pending.promise);
    const p = m.create({ name: 'A', staff_type: '其他人员', job_title: '员工' });
    const second = await m.create({ name: 'A', staff_type: '其他人员', job_title: '员工' });
    expect(second.executed).toBe(false);
    pending.resolve(detail());
    expect((await p).applied).toBe(true);
    expect(api.create).toHaveBeenCalledTimes(1);
    scope.stop();
  });

  it('403 不清空草稿也不登出', async () => {
    const { m, scope } = model();
    api.create.mockRejectedValue(new ApiError('FORBIDDEN', 403, '拒绝'));
    const outcome = await m.create({ name: 'A', staff_type: '其他人员', job_title: '员工' });
    expect(outcome.status).toBe('forbidden');
    expect(m.createError.value).toContain('组织负责人');
    expect(useSessionStore().status).toBe('ready');
    scope.stop();
  });

  it('401 只重新核对会话，不自动退出', async () => {
    const { m, scope } = model();
    const session = useSessionStore();
    const fetchMe = vi.spyOn(session, 'fetchMe').mockResolvedValue(false);
    api.list.mockRejectedValue(new ApiError('UNAUTHENTICATED', 401, '失效'));
    await m.load();
    expect(fetchMe).toHaveBeenCalledTimes(1);
    expect(session.status).toBe('ready');
    expect(m.listError.value).toContain('会话');
    scope.stop();
  });

  it('本地校验失败明确为失败，畸形2xx/解析失败为结果不明', async () => {
    const { m, scope } = model();
    api.create.mockRejectedValueOnce(new ApiError('VALIDATION_FAILED', 0, '请修正日期。'));
    const local = await m.create({ name: 'A', staff_type: '其他人员', job_title: '员工' });
    expect(local.status).toBe('failed');
    expect(local.message).toContain('请修正日期');
    api.create.mockRejectedValueOnce(new ApiError('INVALID_RESPONSE', 201, '畸形成功响应'));
    const malformed = await m.create({ name: 'A', staff_type: '其他人员', job_title: '员工' });
    expect(malformed.status).toBe('unknown');
    expect(malformed.message).toContain('未确认');
    scope.stop();
  });

  it('旧建档回调失效后不清除新上下文的提示', async () => {
    const { m, scope } = model();
    const old = deferred<StaffDetail>();
    api.create.mockReturnValueOnce(old.promise);
    const oldCall = m.create({ name: '旧上下文', staff_type: '其他人员', job_title: '员工' });
    useSessionStore().clearSession();
    readySession();
    api.create.mockResolvedValueOnce(detail('2'));
    await m.create({ name: '新上下文', staff_type: '其他人员', job_title: '员工' });
    const newMessage = m.message.value;
    old.resolve(detail('1'));
    await oldCall;
    expect(m.message.value).toBe(newMessage);
    expect(newMessage).toContain('建档已确认');
    scope.stop();
  });

  it('列表403使列表/详情共享失效，已显示详情不能继续保留', async () => {
    const { m, scope } = model();
    await m.load();
    await m.select('1');
    api.list.mockRejectedValue(new ApiError('FORBIDDEN', 403, '拒绝'));
    await m.refresh();
    expect(m.rows.value).toEqual([]);
    expect(m.detail.value).toBeNull();
    expect(m.selectedId.value).toBe('');
    expect(useSessionStore().status).toBe('ready');
    scope.stop();
  });

  it('详情403使列表在途请求失效', async () => {
    const { m, scope } = model();
    await m.load();
    const lateList = deferred<{ items: StaffRow[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(lateList.promise);
    const refresh = m.refresh();
    api.detail.mockRejectedValue(new ApiError('FORBIDDEN', 403, '拒绝'));
    await m.select('1');
    lateList.resolve({ items: [row('1')], nextCursor: null });
    await refresh;
    await flushPromises();
    expect(m.rows.value).toEqual([]);
    expect(m.detail.value).toBeNull();
    expect(m.selectedId.value).toBe('');
    scope.stop();
  });
});

describe('员工页面展示', () => {
  it('为既有员工录入待审核资格并刷新摘要', async () => {
    api.detail.mockResolvedValueOnce(detail('1', { credentials: [] }))
      .mockResolvedValueOnce(detail('1', { credentials: [{
        credential_id: '12', credential_type: 'dentist_practice', verification_state: 'pending',
        valid_from: null, valid_to: null, no_expiry_confirmed: false,
        verified_at: null, version: 1, effective: false,
      }] }));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    await wrapper.find('.detail-panel button').trigger('click');
    await wrapper.find('form.review-panel input[maxlength="80"]').setValue('SYNTHETIC-12');
    await wrapper.find('form.review-panel').trigger('submit');
    await flushPromises();
    expect(credentialApi.add).toHaveBeenCalledWith('1', expect.objectContaining({
      certificate_no: 'SYNTHETIC-12', no_expiry_confirmed: false,
    }), expect.any(String));
    expect(wrapper.text()).toContain('资格已录入为待审核');
    expect(wrapper.text()).toContain('资格版本 v1');
    expect(wrapper.text()).not.toContain('SYNTHETIC-12');
    wrapper.unmount();
  });

  it('追加资格结果不明保留同一幂等键，切换身份后旧响应不写回', async () => {
    const pending = deferred<string>();
    credentialApi.add.mockRejectedValueOnce(new ApiError('TIMEOUT', 0, '超时'))
      .mockReturnValueOnce(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    await wrapper.find('.detail-panel button').trigger('click');
    await wrapper.find('form.review-panel input[maxlength="80"]').setValue('SYNTHETIC-13');
    await wrapper.find('form.review-panel').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('结果尚未确认');
    await wrapper.find('form.review-panel').trigger('submit');
    expect(credentialApi.add.mock.calls[0][2]).toBe(credentialApi.add.mock.calls[1][2]);
    useSessionStore().clearSession();
    pending.resolve('13');
    await flushPromises();
    expect(wrapper.text()).not.toContain('SYNTHETIC-13');
    expect(wrapper.text()).not.toContain('资格已录入为待审核');
    wrapper.unmount();
  });

  it('审核历史按页显示，切换会话后迟到依据不能泄露', async () => {
    const first = { review_id: '1', credential_id: 'c1', action: 'submit', from_state: null,
      to_state: 'pending', actor_staff_id: '8', basis: null,
      occurred_at: '2026-11-19T01:00:00Z', resulting_version: 1 };
    const later = { ...first, review_id: '2', action: 'verify', from_state: 'pending',
      to_state: 'verified', basis: '仅负责人可见的依据', resulting_version: 2 };
    const pending = deferred<{ items: typeof later[]; next_cursor: null }>();
    credentialApi.reviews.mockResolvedValueOnce({ items: [first], next_cursor: '1' })
      .mockReturnValueOnce(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    await wrapper.findAll('.credential-actions button')[1].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('提交待审核');
    expect(credentialApi.reviews).toHaveBeenCalledWith('1', 'c1', null);
    await wrapper.find('.history-panel button:last-child').trigger('click');
    useSessionStore().clearSession();
    pending.resolve({ items: [later], next_cursor: null });
    await flushPromises();
    expect(wrapper.text()).not.toContain('仅负责人可见的依据');
    expect(wrapper.find('.history-panel').exists()).toBe(false);
    wrapper.unmount();
  });

  it('证件事实仅显式点开后显示，切换会话后旧响应不能泄露', async () => {
    api.detail.mockResolvedValue(detail('1', { credentials: [{
      credential_id: '9', credential_type: 'dentist_practice', verification_state: 'pending',
      valid_from: '2026-01-01', valid_to: '2030-01-01', no_expiry_confirmed: false,
      verified_at: null, version: 1, effective: false,
    }] }));
    const pending = deferred<Awaited<ReturnType<typeof credentialApi.facts>>>();
    credentialApi.facts.mockReturnValueOnce(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('PRIVATE-123');
    await wrapper.find('.credential-list button').trigger('click');
    useSessionStore().clearSession();
    pending.resolve({ credential_id: '9', staff_id: '1', credential_type: 'dentist_practice',
      certificate_no: 'PRIVATE-123', valid_from: '2026-01-01', valid_to: '2030-01-01',
      no_expiry_confirmed: false, verification_state: 'pending', version: 1 });
    await flushPromises();
    expect(wrapper.text()).not.toContain('PRIVATE-123');
    expect(credentialApi.facts).toHaveBeenCalledWith('1', '9');
    wrapper.unmount();
  });

  it('组织负责人核对后按当前版本提交审核，成功后刷新摘要', async () => {
    const pendingCredential = { credential_id: '9', credential_type: 'dentist_practice',
      verification_state: 'pending' as const, valid_from: '2026-01-01', valid_to: '2030-01-01',
      no_expiry_confirmed: false, verified_at: null, version: 1, effective: false };
    api.detail.mockResolvedValueOnce(detail('1', { credentials: [pendingCredential] }))
      .mockResolvedValueOnce(detail('1', { credentials: [{ ...pendingCredential,
        verification_state: 'verified', version: 2, effective: true }] }));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    await wrapper.find('.credential-list button').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('PRIVATE-123');
    await wrapper.find('.review-form textarea').setValue('已核对原件');
    await wrapper.find('.review-form button').trigger('click');
    await flushPromises();
    expect(credentialApi.decide).toHaveBeenCalledWith(expect.objectContaining({ version: 1 }),
      'verified', '已核对原件', expect.any(String));
    expect(wrapper.text()).not.toContain('PRIVATE-123');
    expect(wrapper.text()).toContain('资格状态已更新');
    wrapper.unmount();
  });

  it('审核提交在途切换身份后，旧结果不刷新旧员工或显示旧成功', async () => {
    api.detail.mockResolvedValue(detail('1', { credentials: [{
      credential_id: '9', credential_type: 'dentist_practice', verification_state: 'pending',
      valid_from: '2026-01-01', valid_to: '2030-01-01', no_expiry_confirmed: false,
      verified_at: null, version: 1, effective: false,
    }] }));
    const pending = deferred<void>();
    credentialApi.decide.mockReturnValueOnce(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('.staff-list button').trigger('click');
    await flushPromises();
    await wrapper.find('.credential-list button').trigger('click');
    await flushPromises();
    await wrapper.find('.review-form textarea').setValue('已核对原件');
    await wrapper.find('.review-form button').trigger('click');
    useSessionStore().clearSession();
    pending.resolve();
    await flushPromises();
    expect(api.detail).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain('PRIVATE-123');
    expect(wrapper.text()).not.toContain('资格状态已更新');
    wrapper.unmount();
  });

  it('展示可空执业编号、任职和资格状态/日期有效性，不展示账号角色字段', async () => {
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(wrapper.text()).toContain('员工1');
    await wrapper.findAll('.staff-list button')[0].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('未填写');
    expect(wrapper.text()).toContain('已核验');
    expect(wrapper.text()).toContain('资格自身当前有效');
    expect(wrapper.text()).toContain('不代表可以接诊');
    expect(wrapper.text()).not.toContain('undefined');
    expect(wrapper.text()).not.toContain('certificate_no');
    expect(wrapper.text()).not.toContain('account_id');
    wrapper.unmount();
  });

  it('未启用资格时不向建档请求发送空 credential', async () => {
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('新员工');
    await inputs[1].setValue('员工');
    await inputs[2].setValue('');
    (<HTMLFormElement>wrapper.find('form').element).dispatchEvent(new Event('submit'));
    await flushPromises();
    expect(api.create).toHaveBeenCalledTimes(1);
    const body = api.create.mock.calls[0][0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('credential');
    expect(body['staff_type']).toBe('其他人员');
    wrapper.unmount();
  });

  it('建档成功后刷新列表并打开新员工详情', async () => {
    api.create.mockResolvedValue(detail('99'));
    api.detail.mockResolvedValue(detail('99'));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('新员工');
    await inputs[1].setValue('员工');
    await inputs[2].setValue('');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(api.detail).toHaveBeenCalledWith('99');
    expect(api.list.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(wrapper.text()).toContain('员工99');
    wrapper.unmount();
  });

  it('建档成功后刷新悬挂时切换身份，旧回调不发旧详情、不显示旧提示', async () => {
    api.create.mockResolvedValue(detail('99'));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const refresh = deferred<{ items: StaffRow[]; nextCursor: null }>();
    api.list.mockReturnValueOnce(refresh.promise);
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('悬挂员工');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    useSessionStore().clearSession();
    refresh.resolve({ items: [row('99')], nextCursor: null });
    await flushPromises();
    expect(api.detail).not.toHaveBeenCalledWith('99');
    expect(wrapper.text()).not.toContain('建档已确认');
    wrapper.unmount();
  });

  it('建档成功但列表刷新403时，不打开详情，并把建档成功与刷新失败分开', async () => {
    api.create.mockResolvedValue(detail('99'));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    api.list.mockRejectedValueOnce(new ApiError('FORBIDDEN', 403, '拒绝'));
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('刷新失败员工');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(api.detail).not.toHaveBeenCalledWith('99');
    expect(wrapper.text()).toContain('建档已确认');
    expect(wrapper.text()).toContain('刷新未完成');
    expect(useSessionStore().status).toBe('ready');
    wrapper.unmount();
  });

  it('建档请求在途用户 A→B→A 后，成功结果不抢当前选择', async () => {
    api.list.mockResolvedValue({ items: [row('1'), row('2')], nextCursor: null });
    api.detail.mockImplementation((id: string) => Promise.resolve(detail(id, { job_title: id === '2' ? '第二岗位' : '第一岗位' })));
    const pending = deferred<StaffDetail>();
    api.create.mockReturnValue(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.findAll('.staff-list button')[0].trigger('click');
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('A选择员工');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await nextTick();
    const buttons = wrapper.findAll('.staff-list button');
    await buttons[1].trigger('click');
    await flushPromises();
    await buttons[0].trigger('click');
    await flushPromises();
    const detailCalls = api.detail.mock.calls.length;
    pending.resolve(detail('99'));
    await flushPromises();
    expect(api.detail.mock.calls.length).toBe(detailCalls);
    expect(wrapper.text()).toContain('第一岗位');
    expect(wrapper.text()).not.toContain('员工99');
    wrapper.unmount();
  });

  it('提交在途禁用整组建档输入，旧成功不清除提交后的新草稿', async () => {
    const pending = deferred<StaffDetail>();
    api.create.mockReturnValue(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('旧草稿');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await nextTick();
    const fields = wrapper.find('fieldset.form-fields');
    expect((fields.element as HTMLFieldSetElement).disabled).toBe(true);
    // 即使通过程序化输入绕过 disabled，也不能让本次旧结果清掉新草稿。
    (inputs[0].element as HTMLInputElement).value = '提交后的新草稿';
    inputs[0].element.dispatchEvent(new Event('input'));
    await nextTick();
    pending.resolve(detail('99'));
    await flushPromises();
    expect((inputs[0].element as HTMLInputElement).value).toBe('提交后的新草稿');
    wrapper.unmount();
  });

  it('建档等待期间用户选择另一员工后，旧结果不强行切回新员工', async () => {
    api.list.mockResolvedValue({ items: [row('1'), row('2')], nextCursor: null });
    api.detail.mockImplementation((id: string) => Promise.resolve(detail(id, { job_title: id === '2' ? '第二岗位' : '第一岗位' })));
    const pending = deferred<StaffDetail>();
    api.create.mockReturnValue(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.findAll('.staff-list button')[0].trigger('click');
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('并发员工');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await nextTick();
    await wrapper.findAll('.staff-list button')[1].trigger('click');
    await flushPromises();
    pending.resolve(detail('99'));
    await flushPromises();
    expect(wrapper.text()).toContain('第二岗位');
    expect(wrapper.text()).not.toContain('员工99');
    wrapper.unmount();
  });

  it('身份变化后旧建档响应不显示成功、不清新表单', async () => {
    const pending = deferred<StaffDetail>();
    api.create.mockReturnValue(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('旧身份草稿');
    await inputs[1].setValue('员工');
    await wrapper.find('form').trigger('submit');
    await nextTick();
    useSessionStore().clearSession();
    await nextTick();
    pending.resolve(detail());
    await flushPromises();
    expect(wrapper.text()).not.toContain('建档已确认');
    wrapper.unmount();
  });

  it('详情权限失败不清草稿，也不自动退出', async () => {
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('保留的草稿');
    await inputs[1].setValue('员工');
    api.list.mockRejectedValue(new ApiError('FORBIDDEN', 403, '拒绝'));
    await wrapper.find('.list-panel button').trigger('click');
    await flushPromises();
    expect((inputs[0].element as HTMLInputElement).value).toBe('保留的草稿');
    expect(useSessionStore().status).toBe('ready');
    wrapper.unmount();
  });

  it('详情读取中明确禁用员工切换，不出现可点但静默忽略', async () => {
    const pending = deferred<StaffDetail>();
    api.detail.mockReturnValueOnce(pending.promise);
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const button = wrapper.findAll('.staff-list button')[0];
    await button.trigger('click');
    await nextTick();
    expect((button.element as HTMLButtonElement).disabled).toBe(true);
    pending.resolve(detail('1'));
    await flushPromises();
    expect((button.element as HTMLButtonElement).disabled).toBe(false);
    wrapper.unmount();
  });

  it('rejected 显示中性的未通过/已撤销，不冒充已驳回或推断原因', async () => {
    api.detail.mockResolvedValue(detail('1', {
      credentials: [{
        credential_id: 'c-revoked',
        credential_type: 'other',
        verification_state: 'rejected',
        valid_from: null,
        valid_to: null,
        no_expiry_confirmed: false,
        verified_at: null,
        version: 3,
        effective: false,
      }],
    }));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.findAll('.staff-list button')[0].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('未通过/已撤销');
    expect(wrapper.text()).not.toContain('已驳回');
    expect(wrapper.text()).toContain('不能据此推断');
    wrapper.unmount();
  });

  it('建档 403 后保留输入并显示服务端权限提示', async () => {
    api.create.mockRejectedValue(new ApiError('FORBIDDEN', 403, '拒绝'));
    const wrapper = mount(StaffMembersView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const inputs = wrapper.findAll('.create-panel input');
    await inputs[0].setValue('保留姓名');
    await inputs[1].setValue('员工');
    (<HTMLFormElement>wrapper.find('form').element).dispatchEvent(new Event('submit'));
    await flushPromises();
    expect((inputs[0].element as HTMLInputElement).value).toBe('保留姓名');
    expect(wrapper.text()).toContain('仅组织负责人');
    expect(useSessionStore().status).toBe('ready');
    wrapper.unmount();
  });
});
