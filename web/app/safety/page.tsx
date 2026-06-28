import type { Metadata } from "next";
import { InfoPage } from "@/components/InfoPage";
import { SafetyView } from "@/components/SafetyView";

export const metadata: Metadata = {
  title: "Безопасность — Юлдаш",
  description:
    "Как Юлдаш заботится о безопасности: проверенные водители, рейтинги, кнопка SOS, «поделиться поездкой», минимум данных.",
  alternates: { canonical: "/safety" },
};

export default function Page() {
  return (
    <InfoPage>
      <SafetyView />
    </InfoPage>
  );
}
