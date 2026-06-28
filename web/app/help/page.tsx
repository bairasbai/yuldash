import type { Metadata } from "next";
import { InfoPage } from "@/components/InfoPage";
import { HelpView } from "@/components/HelpView";

export const metadata: Metadata = {
  title: "Справочный центр — Юлдаш",
  description: "Ответы на частые вопросы о Юлдаше: общее, безопасность, оплата, водителям, аккаунт и установка.",
  alternates: { canonical: "/help" },
};

export default function Page() {
  return (
    <InfoPage>
      <HelpView />
    </InfoPage>
  );
}
