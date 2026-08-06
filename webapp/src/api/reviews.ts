// ================================================================
//  Отзыв о приложении (зеркало backend/app/routers/reviews.py).
//  POST /reviews {stars, text, city} — сохраняется неопубликованным,
//  на лендинг попадёт после модерации админом. text ≥ 10 символов.
// ================================================================
import { apiPost } from "./client";

export interface AppReviewIn {
  stars: number; // 1..5
  text: string; // ≥ 10 символов
  city?: string;
}

export interface AppReviewOut {
  id: number;
  stars: number;
  text: string;
  city: string;
  published: boolean;
  created_at: string;
}

/** Минимальная длина текста на бэке — 10 символов (иначе 400). */
export const REVIEW_MIN_LEN = 10;
export const REVIEW_MAX_LEN = 600;

export function submitAppReview(body: AppReviewIn): Promise<AppReviewOut> {
  return apiPost<AppReviewOut>("/reviews", {
    stars: body.stars,
    text: body.text,
    city: body.city ?? "",
  });
}
