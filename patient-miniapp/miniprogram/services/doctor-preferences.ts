import { createHttpClient } from './http';
import type { HttpClient, PageResult } from './http';
import { API_BASE_URL } from './config';
import { patientSession } from './session';

export type PreferenceSource = '本人选择' | '熟人推荐' | '既往就诊';
export interface DoctorPreference { dentist_id: string; source: PreferenceSource; created_at: string }
const sources: PreferenceSource[] = ['本人选择', '熟人推荐', '既往就诊'];

function parse(value: unknown): DoctorPreference {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('收藏资料格式有误。');
  const v = value as Record<string, unknown>;
  if (typeof v['dentist_id'] !== 'string' || !sources.includes(v['source'] as PreferenceSource)
      || typeof v['created_at'] !== 'string') throw new Error('收藏资料格式有误。');
  return { dentist_id: v['dentist_id'], source: v['source'] as PreferenceSource, created_at: v['created_at'] };
}

export function createDoctorPreferencesApi(client?: HttpClient) {
  const http = client ?? createHttpClient({ baseUrl: API_BASE_URL, getBearerToken: () => patientSession.token });
  return {
    async list(): Promise<DoctorPreference[]> {
      const all: DoctorPreference[] = [];
      let cursor: string | null = null;
      do {
        const page: PageResult<unknown> = await http.list<unknown>('/me/doctor-preferences', { query: { limit: 100, ...(cursor ? { cursor } : {}) } });
        all.push(...page.items.map(parse));
        if (page.nextCursor === cursor) throw new Error('收藏分页格式有误。');
        cursor = page.nextCursor;
      } while (cursor);
      return all;
    },
    async set(doctorId: string, source: PreferenceSource): Promise<void> {
      await http.put<void>(`/me/doctor-preferences/${encodeURIComponent(doctorId)}`, { source });
    },
    async delete(doctorId: string): Promise<void> {
      await http.delete<void>(`/me/doctor-preferences/${encodeURIComponent(doctorId)}`);
    },
  };
}
