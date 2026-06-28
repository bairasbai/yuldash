import type { Metadata } from "next";
import { LegalView } from "@/components/LegalView";
import { PRIVACY } from "@/components/legal-content";

export const metadata: Metadata = {
  title: "Политика конфиденциальности — Юлдаш",
  description: "Как сервис «Юлдаш» собирает, использует и защищает персональные данные (152-ФЗ).",
  alternates: { canonical: "/privacy" },
};

export default function Page() {
  return <LegalView doc={PRIVACY} />;
}
