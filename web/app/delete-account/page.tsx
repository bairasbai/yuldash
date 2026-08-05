import type { Metadata } from "next";
import { LegalView } from "@/components/LegalView";
import { DELETE_ACCOUNT } from "@/components/legal-content";

// Отдельная страница удаления аккаунта — требование Google Play.
// Магазин требует ССЫЛКУ, доступную БЕЗ установки приложения: человек должен иметь
// возможность узнать, как удалить свои данные, даже если приложение уже снёс.
// Ссылка указывается в форме Data safety при публикации.
export const metadata: Metadata = {
  title: "Удаление аккаунта и данных — Юлдаш",
  description:
    "Как удалить аккаунт «Юлдаш» и все персональные данные: через приложение или по запросу. Что удаляется, что остаётся и почему.",
  alternates: { canonical: "/delete-account" },
};

export default function Page() {
  return <LegalView doc={DELETE_ACCOUNT} />;
}
