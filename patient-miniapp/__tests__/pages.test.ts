import { beforeEach, describe, expect, it, vi } from 'vitest';

const flowMock = vi.hoisted(() => ({
  login: vi.fn(),
  verifySession: vi.fn(),
  register: vi.fn(),
  bind: vi.fn(),
  recheck: vi.fn(),
  cancelPending: vi.fn(),
  hasRegistration: true,
  hasSession: false,
  sessionRevision: 0,
}));

vi.mock('../miniprogram/services/auth-flow', () => ({ authFlow: flowMock }));

const wxMock = vi.hoisted(() => ({
  login: vi.fn(),
  request: vi.fn(),
  reLaunch: vi.fn(),
  navigateTo: vi.fn(),
  navigateBack: vi.fn(),
  showToast: vi.fn(),
}));

interface PageLike {
  data: Record<string, unknown>;
  setData: (patch: Record<string, unknown>) => void;
  active?: boolean;
  [key: string]: unknown;
}

async function loadPage(path: string): Promise<PageLike> {
  let config: Record<string, unknown> | null = null;
  vi.stubGlobal('Page', (value: Record<string, unknown>) => {
    config = value;
  });
  vi.stubGlobal('wx', wxMock);
  vi.resetModules();
  await import(path);
  if (!config) throw new Error('Page config not captured');
  const source = config as Record<string, unknown> & { data: Record<string, unknown> };
  const page: PageLike = {
    data: JSON.parse(JSON.stringify(source.data)),
    setData(patch: Record<string, unknown>) {
      Object.assign(this.data, patch);
    },
  };
  for (const [key, value] of Object.entries(source)) {
    if (typeof value === 'function') page[key] = (value as (...args: unknown[]) => unknown).bind(page);
  }
  return page;
}

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('患者小程序页面生命周期', () => {
  beforeEach(() => {
    flowMock.login.mockReset();
    flowMock.verifySession.mockReset();
    flowMock.register.mockReset();
    flowMock.bind.mockReset();
    flowMock.recheck.mockReset();
    flowMock.cancelPending.mockReset();
    flowMock.hasRegistration = true;
    flowMock.hasSession = false;
    flowMock.sessionRevision = 0;
    Object.values(wxMock).forEach((fn) => fn.mockReset());
  });

  it('登录已绑定后跳转个人页；未绑定则显示建档入口', async () => {
    flowMock.login.mockResolvedValueOnce({ status: 'session', message: '', me: { name: '张三' } });
    const page = await loadPage('../miniprogram/pages/index/index');
    page.onLoad?.();
    await (page.handleLogin as () => Promise<void>)();
    expect(wxMock.reLaunch).toHaveBeenCalledWith({ url: '/pages/profile/profile' });

    flowMock.login.mockResolvedValueOnce({ status: 'registration', message: '未绑定' });
    const second = await loadPage('../miniprogram/pages/index/index');
    second.onLoad?.();
    await (second.handleLogin as () => Promise<void>)();
    expect(second.data.showRegistration).toBe(true);
    expect(wxMock.navigateTo).not.toHaveBeenCalled();
  });

  it('注册页提交表单并跳转个人页', async () => {
    flowMock.register.mockResolvedValue({ status: 'session', message: '' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    page.onBirthChange?.({ detail: { value: '1990-01-02' } });
    page.onAllergyChange?.({ detail: { value: '2' } });
    page.onNotesInput?.({ detail: { value: '青霉素' } });
    await (page.handleSubmit as () => Promise<void>)();

    expect(flowMock.register).toHaveBeenCalledWith(
      expect.objectContaining({ active: expect.any(Function) }),
      {
        name: '张三',
        phone: '13800000000',
        birth_date: '1990-01-02',
        allergy_status: '有',
        allergy_notes: '青霉素',
      },
    );
    expect(wxMock.reLaunch).toHaveBeenCalledWith({ url: '/pages/profile/profile' });
  });

  it('从真实预约流程登录后返回原流程，不强制跳个人页', async () => {
    flowMock.login.mockResolvedValueOnce({ status: 'session', message: '' });
    const page = await loadPage('../miniprogram/pages/index/index');
    page.onLoad?.({ returnTo: 'booking-flow' });
    await (page.handleLogin as () => Promise<void>)();
    expect(wxMock.navigateBack).toHaveBeenCalledWith({ delta: 1 });
    expect(wxMock.reLaunch).not.toHaveBeenCalled();
  });

  it('从真实预约流程建档完成后返回两层原流程', async () => {
    flowMock.register.mockResolvedValue({ status: 'session', message: '' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.({ returnTo: 'booking-flow' });
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    await (page.handleSubmit as () => Promise<void>)();
    expect(wxMock.navigateBack).toHaveBeenCalledWith({ delta: 2 });
    expect(wxMock.reLaunch).not.toHaveBeenCalled();
  });

  it('注册页必填为空时显示字段错误且不提交', async () => {
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    await (page.handleSubmit as () => Promise<void>)();
    expect(flowMock.register).not.toHaveBeenCalled();
    expect((page.data.fieldErrors as Record<string, string>).name).toContain('姓名');
    expect((page.data.fieldErrors as Record<string, string>).phone).toContain('电话');
    expect(wxMock.showToast).toHaveBeenCalledWith(expect.objectContaining({ title: '请检查必填项' }));
  });

  it('注册结果不明时保留中性提示，重新核对后才继续', async () => {
    flowMock.register.mockResolvedValue({ status: 'unknown', message: '结果未确认，请重新登录核对。' });
    flowMock.recheck.mockResolvedValue({ status: 'registration', message: '仍未绑定' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    await (page.handleSubmit as () => Promise<void>)();

    expect(flowMock.recheck).toHaveBeenCalledTimes(1);
    expect(page.data.showRecheck).toBe(false);
    expect(page.data.showForm).toBe(true);
    expect(page.data.canSubmit).toBe(true);
    expect(page.data.message).toContain('仍未绑定');
  });

  it('注册超时后的重新核对仍失败时保留中性提示并可重试', async () => {
    flowMock.register.mockResolvedValue({ status: 'unknown', message: '结果未确认' });
    flowMock.recheck.mockResolvedValue({ status: 'unknown', message: '核对未完成' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    await (page.handleSubmit as () => Promise<void>)();

    expect(page.data.showRecheck).toBe(true);
    expect(page.data.canSubmit).toBe(false);
    expect(page.data.message).toContain('核对未完成');
  });

  it('绑定页只提交 binding_token，并在结果不明时重新登录核对', async () => {
    flowMock.bind.mockResolvedValue({ status: 'unknown', message: '结果未确认' });
    flowMock.recheck.mockResolvedValue({ status: 'registration', message: '仍未绑定' });
    const page = await loadPage('../miniprogram/pages/bind/bind');
    page.onLoad?.();
    page.onTokenInput?.({ detail: { value: ' bind-token ' } });
    await (page.handleSubmit as () => Promise<void>)();

    expect(flowMock.bind).toHaveBeenCalledWith(
      expect.objectContaining({ active: expect.any(Function) }),
      ' bind-token ',
    );
    expect(flowMock.recheck).toHaveBeenCalledTimes(1);
    expect(page.data.showForm).toBe(true);
    expect(page.data.canSubmit).toBe(true);
    expect(page.data.message).toContain('仍未绑定');
  });

  it('页面卸载后迟到的登录结果不写页面、不跳转', async () => {
    const pending = deferred<{ status: string; message: string }>();
    flowMock.login.mockReturnValue(pending.promise);
    const page = await loadPage('../miniprogram/pages/index/index');
    page.onLoad?.();
    const request = (page.handleLogin as () => Promise<void>)();
    page.onUnload?.();
    pending.resolve({ status: 'session', message: '' });
    await request;

    expect(wxMock.reLaunch).not.toHaveBeenCalled();
    expect(page.data.statusText).toBe('未登录');
  });

  it('个人页只展示已核验的患者身份；网络未确认保留重试', async () => {
    flowMock.verifySession.mockResolvedValueOnce({
      status: 'session',
      message: '',
      me: { name: '张三', identity_type: 'patient' },
    });
    const page = await loadPage('../miniprogram/pages/profile/profile');
    page.onLoad?.();
    await flush();
    expect(page.data.name).toBe('张三');
    expect(page.data.identityLabel).toBe('就诊用户');
    expect(page.data.statusText).toBe('个人信息已核实');

    flowMock.verifySession.mockResolvedValueOnce({ status: 'unverified', message: '身份尚未确认' });
    const unverified = await loadPage('../miniprogram/pages/profile/profile');
    unverified.onLoad?.();
    await flush();
    expect(unverified.data.statusText).toBe('身份未确认');
    expect(unverified.data.showRetry).toBe(true);
    expect(wxMock.reLaunch).not.toHaveBeenCalled();
  });

  it('员工身份/失效会话显示返回登录，不展示身份资料', async () => {
    flowMock.verifySession.mockResolvedValue({ status: 'staff-rejected', message: '员工身份' });
    const page = await loadPage('../miniprogram/pages/profile/profile');
    page.onLoad?.();
    await flush();
    expect(page.data.name).toBe('');
    expect(page.data.showLogin).toBe(true);
  });

  it('无注册上下文时注册页/绑定页不显示表单', async () => {
    flowMock.hasRegistration = false;
    const register = await loadPage('../miniprogram/pages/register/register');
    register.onLoad?.();
    expect(register.data.showForm).toBe(false);

    const bind = await loadPage('../miniprogram/pages/bind/bind');
    bind.onLoad?.();
    expect(bind.data.showForm).toBe(false);
  });

  it('无 Bearer 的首页重试重新 wx.login，不调用 me', async () => {
    flowMock.hasSession = false;
    flowMock.login.mockResolvedValueOnce({ status: 'session', message: '' });
    const page = await loadPage('../miniprogram/pages/index/index');
    page.onLoad?.();
    await (page.handleRetry as () => Promise<void>)();

    expect(flowMock.login).toHaveBeenCalledTimes(1);
    expect(flowMock.verifySession).not.toHaveBeenCalled();
    expect(wxMock.reLaunch).toHaveBeenCalledWith({ url: '/pages/profile/profile' });
  });

  it('注册已有 Bearer 但 me 失败时优先只重试 me', async () => {
    flowMock.register.mockResolvedValue({ status: 'unverified', message: '身份未确认' });
    flowMock.hasSession = true;
    flowMock.verifySession.mockResolvedValue({ status: 'session', message: '' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    await (page.handleSubmit as () => Promise<void>)();
    expect(page.data.showRecheck).toBe(true);

    await (page.handleRecheck as () => Promise<void>)();
    expect(flowMock.verifySession).toHaveBeenCalledTimes(1);
    expect(flowMock.recheck).not.toHaveBeenCalled();
    expect(wxMock.reLaunch).toHaveBeenCalledWith({ url: '/pages/profile/profile' });
  });

  it('注册结果不明后的核对连续失败仍保留重试入口', async () => {
    flowMock.register.mockResolvedValue({ status: 'unknown', message: '结果未确认' });
    flowMock.recheck.mockResolvedValue({ status: 'unknown', message: '核对未完成' });
    const page = await loadPage('../miniprogram/pages/register/register');
    page.onLoad?.();
    page.onNameInput?.({ detail: { value: '张三' } });
    page.onPhoneInput?.({ detail: { value: '13800000000' } });
    await (page.handleSubmit as () => Promise<void>)();
    expect(page.data.showRecheck).toBe(true);

    await (page.handleRecheck as () => Promise<void>)();
    expect(page.data.showRecheck).toBe(true);
    expect(page.data.canSubmit).toBe(false);
    expect(page.data.message).toContain('核对未完成');
  });

  it('hide→show 使旧登录结果失效，恢复后可继续操作', async () => {
    const pending = deferred<{ status: string; message: string }>();
    flowMock.login.mockReturnValue(pending.promise);
    const page = await loadPage('../miniprogram/pages/index/index');
    page.onLoad?.();
    const request = (page.handleLogin as () => Promise<void>)();
    page.onHide?.();
    expect(flowMock.cancelPending).toHaveBeenCalledTimes(1);
    page.onShow?.();
    expect(page.data.busy).toBe(false);

    pending.resolve({ status: 'session', message: '' });
    await request;
    expect(wxMock.reLaunch).not.toHaveBeenCalled();
    expect(page.data.statusText).toBe('未登录');
  });

  it('个人页连续核验失败仍保留重试入口，成功后显示姓名', async () => {
    flowMock.verifySession
      .mockResolvedValueOnce({ status: 'unverified', message: '未确认1' })
      .mockResolvedValueOnce({ status: 'error', message: '未确认2' })
      .mockResolvedValueOnce({ status: 'session', message: '', me: { name: '张三' } });
    const page = await loadPage('../miniprogram/pages/profile/profile');
    page.onLoad?.();
    await flush();
    expect(page.data.showRetry).toBe(true);

    await (page.handleVerify as () => Promise<void>)();
    expect(page.data.showRetry).toBe(true);
    expect(page.data.name).toBe('');

    await (page.handleVerify as () => Promise<void>)();
    expect(page.data.name).toBe('张三');
    expect(page.data.statusText).toBe('个人信息已核实');
  });

  it('个人页隐藏期间会话变化后不展示旧姓名', async () => {
    flowMock.verifySession
      .mockResolvedValueOnce({ status: 'session', message: '', me: { name: '旧姓名' } })
      .mockResolvedValueOnce({ status: 'anonymous', message: '未登录' });
    const page = await loadPage('../miniprogram/pages/profile/profile');
    page.onLoad?.();
    await flush();
    expect(page.data.name).toBe('旧姓名');

    flowMock.sessionRevision = 1;
    flowMock.hasSession = false;
    page.onHide?.();
    page.onShow?.();
    await flush();

    expect(page.data.name).toBe('');
    expect(page.data.showLogin).toBe(true);
  });

  it('身份核验 403 保留 Bearer 提示访问被拒，不伪装登录失效', async () => {
    flowMock.hasSession = true;
    flowMock.verifySession.mockResolvedValue({ status: 'forbidden', message: '当前访问被拒' });
    const page = await loadPage('../miniprogram/pages/profile/profile');
    page.onLoad?.();
    await flush();

    expect(page.data.name).toBe('');
    expect(page.data.statusText).toBe('访问被拒');
    expect(page.data.showLogin).toBe(false);
    expect(page.data.showRetry).toBe(false);
  });
});
