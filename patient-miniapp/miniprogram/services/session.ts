// 患者会话只保存在内存：小程序重启后重新走微信登录，不写 storage。
// 不持久化令牌/注册上下文，避免在设备上留下可重放凭证。
import type { Me } from './auth-types';

export class PatientSessionStore {
  private tokenValue: string | null = null;
  private patientIdValue: string | null = null;
  private expiresAtValue: string | null = null;
  private meValue: Me | null = null;
  private revisionValue = 0;

  get token(): string | null {
    return this.tokenValue;
  }

  get patientId(): string | null {
    return this.patientIdValue;
  }

  get me(): Me | null {
    return this.meValue;
  }

  get revision(): number {
    return this.revisionValue;
  }

  setSession(token: string, patientId?: string, expiresAt?: string): void {
    this.tokenValue = token;
    this.patientIdValue = patientId ?? null;
    this.expiresAtValue = expiresAt ?? null;
    this.meValue = null;
    this.revisionValue += 1;
  }

  setMe(me: Me): void {
    this.meValue = me;
    this.revisionValue += 1;
  }

  clear(): void {
    this.tokenValue = null;
    this.patientIdValue = null;
    this.expiresAtValue = null;
    this.meValue = null;
    this.revisionValue += 1;
  }

  /** 仅用于本地提示；服务端 /auth/me 仍是有效性最终依据。 */
  get isExpired(): boolean {
    if (!this.expiresAtValue) return false;
    const expires = Date.parse(this.expiresAtValue);
    return Number.isFinite(expires) && expires <= Date.now();
  }
}

export const patientSession = new PatientSessionStore();
