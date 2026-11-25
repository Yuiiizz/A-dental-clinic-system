import { describe, expect, it } from 'vitest';
import { resolveRedirect } from '@/router';

describe('路由守卫决策', () => {
  it('未登录访问受保护页 → 登录页', () => {
    expect(resolveRedirect('guest', { name: 'home' })).toEqual({ name: 'login' });
  });

  it('已登录访问首页 → 放行', () => {
    expect(resolveRedirect('ready', { name: 'home' })).toBe(true);
  });

  it('强制改密访问首页 → 改密页，不能靠 URL 进入首页', () => {
    expect(resolveRedirect('mustChangePassword', { name: 'home' })).toEqual({ name: 'change-password' });
  });

  it('强制改密停留改密页 → 放行', () => {
    expect(resolveRedirect('mustChangePassword', { name: 'change-password' })).toBe(true);
  });

  it('已改密访问改密页 → 首页', () => {
    expect(resolveRedirect('ready', { name: 'change-password' })).toEqual({ name: 'home' });
  });

  it('未登录访问登录页 → 放行', () => {
    expect(resolveRedirect('guest', { name: 'login', meta: { public: true } })).toBe(true);
  });

  it('已登录访问登录页 → 首页', () => {
    expect(resolveRedirect('ready', { name: 'login', meta: { public: true } })).toEqual({ name: 'home' });
  });

  it('强制改密访问登录页 → 改密页', () => {
    expect(resolveRedirect('mustChangePassword', { name: 'login', meta: { public: true } })).toEqual({
      name: 'change-password',
    });
  });

  it('系统管理员访问账号管理页 → 放行', () => {
    expect(resolveRedirect('ready', { name: 'admin-accounts', meta: { roles: ['sysadmin'] } }, ['sysadmin'])).toBe(true);
  });

  it('按角色挡住直接输入的其他岗位页面地址', () => {
    expect(resolveRedirect('ready', { name: 'clinical', meta: { roles: ['dentist'] } }, ['frontdesk']))
      .toEqual({ name: 'home' });
    expect(resolveRedirect('ready', { name: 'frontdesk-schedule', meta: { roles: ['frontdesk'] } }, ['owner']))
      .toEqual({ name: 'home' });
    expect(resolveRedirect('ready', { name: 'inventory-real', meta: { roles: ['owner', 'inventory'] } }, ['inventory']))
      .toBe(true);
  });

  it('未登录访问账号管理页 → 登录页', () => {
    expect(resolveRedirect('guest', { name: 'admin-accounts' })).toEqual({ name: 'login' });
  });

  it('身份未确认访问受保护页 → 登录页（由登录页展示重试，不形成循环）', () => {
    expect(resolveRedirect('unverified', { name: 'home' })).toEqual({ name: 'login' });
  });

  it('身份未确认访问登录页 → 放行（登录页是 public，可展示“身份确认未完成”重试）', () => {
    expect(resolveRedirect('unverified', { name: 'login', meta: { public: true } })).toBe(true);
  });

  it('初始化核验中访问受保护页 → 登录页，避免守卫循环', () => {
    expect(resolveRedirect('checking', { name: 'home' })).toEqual({ name: 'login' });
  });
});
