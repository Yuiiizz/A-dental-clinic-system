// 患者小程序身份接口的严格类型。字段对齐 openapi.yaml / 后端 PatientAuthController。
// 这里只放类型，不放任何令牌、openid、code 或持久化逻辑。

export interface WechatSession {
  kind: 'session';
  session_token: string;
  expires_at: string;
}

export interface WechatRegistration {
  kind: 'registration';
  registration_token: string;
  registration_expires_at: string;
}

export type WechatLoginResult = WechatSession | WechatRegistration;

export interface PatientSessionResult {
  session_token: string;
  patient_id: string;
  expires_at?: string;
}

export interface MeRole {
  role_code: string;
  branch_ids: string[];
}

export interface Me {
  account_id: string;
  name: string;
  identity_type: 'patient' | 'staff';
  must_change_password: boolean;
  roles: MeRole[];
}

export type AllergyStatus = '未申报' | '无已知' | '有' | '不确定';

export interface PatientRegistrationForm {
  name: string;
  phone: string;
  birth_date?: string;
  allergy_status: AllergyStatus;
  allergy_notes?: string;
}
