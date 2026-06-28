import type { Metadata } from "next";
import { LegalView } from "@/components/LegalView";
import { TERMS } from "@/components/legal-content";

export const metadata: Metadata = {
  title: "Пользовательское соглашение — Юлдаш",
  description: "Условия использования сервиса попутчиков «Юлдаш».",
  alternates: { canonical: "/terms" },
};

export default function Page() {
  return <LegalView doc={TERMS} />;
}
