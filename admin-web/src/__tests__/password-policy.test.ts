import { describe, expect, it } from 'vitest';
import {
  MAX_PASSWORD_UTF8_BYTES,
  MIN_PASSWORD_CODE_POINTS,
  passwordCodePoints,
  passwordUtf8Bytes,
  validateNewPassword,
} from '@/services/password-policy';

describe('新密码策略（12 码点 + 72 UTF-8 字节）', () => {
  it('下限按 Unicode 码点计：11 个 emoji 仍不足 12', () => {
    const eleven = '😀'.repeat(11);
    expect(passwordCodePoints(eleven)).toBe(11);
    expect(passwordUtf8Bytes(eleven)).toBe(44);
    expect(validateNewPassword(eleven, eleven)).toContain(`至少需要 ${MIN_PASSWORD_CODE_POINTS} 个字符`);
  });

  it('ASCII 72 字节通过，73 字节拒绝', () => {
    const atLimit = 'a'.repeat(MAX_PASSWORD_UTF8_BYTES);
    const overLimit = 'a'.repeat(MAX_PASSWORD_UTF8_BYTES + 1);

    expect(passwordUtf8Bytes(atLimit)).toBe(72);
    expect(validateNewPassword(atLimit, atLimit)).toBeNull();
    expect(validateNewPassword(overLimit, overLimit)).toContain('最多 72 个 UTF-8 字节');
  });

  it('中文 24 字 = 72 字节通过，25 字 = 75 字节拒绝', () => {
    const atLimit = '中'.repeat(24);
    const overLimit = '中'.repeat(25);

    expect(passwordUtf8Bytes(atLimit)).toBe(72);
    expect(passwordCodePoints(atLimit)).toBe(24);
    expect(validateNewPassword(atLimit, atLimit)).toBeNull();

    expect(passwordUtf8Bytes(overLimit)).toBe(75);
    expect(validateNewPassword(overLimit, overLimit)).toContain('最多 72 个 UTF-8 字节');
  });

  it('emoji 18 个 = 72 字节通过，19 个 = 76 字节拒绝', () => {
    const atLimit = '😀'.repeat(18);
    const overLimit = '😀'.repeat(19);

    expect(passwordUtf8Bytes(atLimit)).toBe(72);
    expect(passwordCodePoints(atLimit)).toBe(18);
    expect(validateNewPassword(atLimit, atLimit)).toBeNull();

    expect(passwordUtf8Bytes(overLimit)).toBe(76);
    expect(validateNewPassword(overLimit, overLimit)).toContain('最多 72 个 UTF-8 字节');
  });

  it('两次输入必须一致', () => {
    expect(validateNewPassword('123456789012', '123456789013')).toBe('两次输入的新密码不一致。');
  });
});
