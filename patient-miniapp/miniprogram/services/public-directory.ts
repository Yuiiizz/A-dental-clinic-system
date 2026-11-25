import { createHttpClient } from './http';
import type { HttpClient } from './http';
import { API_BASE_URL } from './config';

export interface PublicBranch {
  branch_id: string;
  name: string;
  address: string;
  phone: string;
  opening_hours: string;
  public_intro: string | null;
}

export interface PublicDoctor {
  doctor_id: string;
  name: string;
  job_title: string;
  branches: Array<{ branch_id: string; branch_name: string }>;
  public_intro: string | null;
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('公开资料格式有误，请稍后重试。');
  return value as Record<string, unknown>;
}
function string(value: unknown): string {
  if (typeof value !== 'string') throw new Error('公开资料格式有误，请稍后重试。');
  return value;
}
function display(value: unknown): string {
  return string(value).replace(/【教学(?:虚拟|虚构)】/g, '【示例】').replace(/教学(?:虚拟|虚构)/g, '示例').replace(/教学/g, '');
}
function optionalIntro(value: unknown): string | null {
  if (value == null) return null;
  return display(value);
}
function branch(value: unknown): PublicBranch {
  const v = object(value);
  return { branch_id: string(v['branch_id']), name: display(v['name']), address: display(v['address']),
    phone: string(v['phone']), opening_hours: display(v['opening_hours']), public_intro: optionalIntro(v['public_intro']) };
}
function doctor(value: unknown): PublicDoctor {
  const v = object(value);
  if (!Array.isArray(v['branches'])) throw new Error('公开资料格式有误，请稍后重试。');
  return { doctor_id: string(v['doctor_id']), name: display(v['name']), job_title: display(v['job_title']), public_intro: optionalIntro(v['public_intro']),
    branches: v['branches'].map((entry) => {
      const b = object(entry);
      return { branch_id: string(b['branch_id']), branch_name: display(b['branch_name']) };
    }) };
}

export function createPublicDirectoryApi(client?: HttpClient) {
  const http = client ?? createHttpClient({ baseUrl: API_BASE_URL, timeoutMs: 15000 });
  return {
    async branches(): Promise<PublicBranch[]> {
      const page = await http.list<unknown>('/public/branches', { auth: false, query: { limit: 100 } });
      return page.items.map(branch);
    },
    async doctors(): Promise<PublicDoctor[]> {
      const page = await http.list<unknown>('/public/doctors', { auth: false, query: { limit: 100 } });
      return page.items.map(doctor);
    },
  };
}
