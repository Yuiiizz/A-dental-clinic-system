import { createHttpClient } from './http';
import type { HttpClient } from './http';
import { API_BASE_URL } from './config';
import { patientSession } from './session';

export type AllergyStatus = '未申报' | '无已知' | '有' | '不确定';
export interface PatientProfile {
  patient_id: string; name: string; birth_date: string | null; phone: string;
  email: string | null; emergency_name: string | null; emergency_phone: string | null;
  primary_dentist_id: string | null; allergy_status: AllergyStatus; allergy_notes: string | null; version: number;
}
export interface ProfileChange {
  phone: string; email: string; emergency_name: string; emergency_phone: string;
  allergy_status: AllergyStatus; allergy_notes: string; expected_version: number;
}
const states: AllergyStatus[] = ['未申报', '无已知', '有', '不确定'];
function profile(value: unknown): PatientProfile {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('个人信息格式有误。');
  const v = value as Record<string, unknown>;
  const nullable = (key: string): string | null => v[key] == null ? null : typeof v[key] === 'string' ? v[key] as string : (() => { throw new Error('个人信息格式有误。'); })();
  if (typeof v['patient_id'] !== 'string' || typeof v['name'] !== 'string' || typeof v['phone'] !== 'string'
      || !states.includes(v['allergy_status'] as AllergyStatus) || !Number.isInteger(v['version']))
    throw new Error('个人信息格式有误。');
  return { patient_id: v['patient_id'], name: v['name'], birth_date: nullable('birth_date'), phone: v['phone'],
    email: nullable('email'), emergency_name: nullable('emergency_name'), emergency_phone: nullable('emergency_phone'),
    primary_dentist_id: nullable('primary_dentist_id'), allergy_status: v['allergy_status'] as AllergyStatus,
    allergy_notes: nullable('allergy_notes'), version: v['version'] as number };
}

export function createProfileApi(client?: HttpClient) {
  const http = client ?? createHttpClient({ baseUrl: API_BASE_URL, getBearerToken: () => patientSession.token });
  const keys = new Map<string,string>();
  let revision = patientSession.revision;
  return {
    async get(): Promise<PatientProfile> { return profile((await http.get<unknown>('/me/profile')).data); },
    async update(change: ProfileChange): Promise<PatientProfile> {
      if (revision !== patientSession.revision) { keys.clear(); revision = patientSession.revision; }
      const fingerprint = JSON.stringify(change);
      const key = keys.get(fingerprint) ?? `profile-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
      keys.set(fingerprint,key);
      const result = profile((await http.post<unknown>('/me/profile',change,{ idempotencyKey:key })).data);
      if (revision === patientSession.revision && keys.get(fingerprint) === key) keys.delete(fingerprint);
      return result;
    },
  };
}
