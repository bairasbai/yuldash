// ================================================================
//  «Центр справедливости»: моё положение + споры (зеркало backend
//  routers/incidents.py). Спор — это две версии одной истории:
//  заявитель рассказал, вторая сторона объясняется, админ решает.
//
//  Телефонов сторон в пользовательской витрине НЕТ (только имя) —
//  их видит лишь админ в своей ручке.
// ================================================================
import { apiGet, apiPost } from "./client";

/** GET /me/standing — моё положение: надёжность, страйки, пауза. */
export interface Standing {
  standing: string; // good | warned | striked | suspended | banned
  strikes: number;
  warnings: number;
  reliability: number; // 0..100
  suspended_until: string | null;
  suspend_reason: string;
  rating_shield: boolean; // щит рейтинга после несправедливой оценки
  active_incidents: number;
  can_act: boolean; // false = аккаунт на паузе, новые заказы недоступны
}

export function fetchStanding(signal?: AbortSignal): Promise<Standing> {
  return apiGet<Standing>("/me/standing", { signal });
}

/** Спор глазами участника (IncidentOut). */
export interface Incident {
  id: number;
  booking_id: number | null;
  type: string;
  severe: boolean;
  status: string; // awaiting_response | under_review | appealed | resolved | closed
  reporter_role: string;
  description: string;
  respondent_statement: string;
  responded_at: string | null;
  resolution: string; // dismissed | warning | strike | suspend | ban | mutual_resolved
  fault: string; // none | reporter | respondent | both | unclear
  resolution_note: string;
  compensation_kop: number;
  appeal_text: string;
  appeal_status: string;
  created_at: string;
  updated_at: string;
  resolved_at: string | null;
  my_role: string; // reporter | respondent | admin
  other_name: string; // имя второй стороны, без телефона
  evidence_urls: string[]; // фото заявителя (/secure/evidence)
  respondent_evidence_urls: string[]; // фото обвинённого
  booking_route: string | null;
}

export function fetchMyIncidents(signal?: AbortSignal): Promise<Incident[]> {
  return apiGet<Incident[]>("/incidents/mine", { signal });
}

export function fetchIncident(id: number, signal?: AbortSignal): Promise<Incident> {
  return apiGet<Incident>(`/incidents/${id}`, { signal });
}

/** Право на защиту: объяснение обвинённого (можно с фото). */
export function respondIncident(
  id: number,
  statement: string,
  evidenceUrls: string[] = []
): Promise<Incident> {
  return apiPost<Incident>(`/incidents/${id}/respond`, {
    statement,
    evidence_urls: evidenceUrls.length ? evidenceUrls : undefined,
  });
}

/** Не согласен с решением — апелляция (один раз). */
export function appealIncident(id: number, text: string): Promise<Incident> {
  return apiPost<Incident>(`/incidents/${id}/appeal`, { text });
}

/** «Мы решили миром» — заявитель закрывает спор сам. */
export function withdrawIncident(id: number): Promise<Incident> {
  return apiPost<Incident>(`/incidents/${id}/withdraw`);
}

/** Открыть спор: по брони попутки, по такси-заказу или по посылке (respondent_id обязателен). */
export function createIncident(body: {
  respondent_id: number;
  type: string;
  description?: string;
  booking_id?: number;
  order_id?: number;
  evidence_urls?: string[];
}): Promise<Incident> {
  return apiPost<Incident>("/incidents", body);
}
