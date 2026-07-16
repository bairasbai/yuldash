// ================================================================
//  Голосовое (зеркало backend/app/routers/discovery.py POST /voice).
//  Загружаем записанное аудио (multipart `file`) → публичный URL,
//  который затем кладём в заявку (voice_url). Требует входа.
// ================================================================
import { apiUpload } from "./client";

/** POST /voice (multipart `file`) → {url}. Расширение сервер определяет сам. */
export function uploadVoice(blob: Blob, filename = "voice.webm", signal?: AbortSignal): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", blob, filename);
  return apiUpload<{ url: string }>("/voice", form, { signal });
}
