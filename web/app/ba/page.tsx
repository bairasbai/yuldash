"use client";

import { LandingPage } from "@/components/LandingPage";

// Башкироязычная версия лендинга (/ba). Тот же контент, язык по умолчанию — БА.
// Переключатель в шапке всё так же работает. SEO: см. app/ba/layout.tsx (hreflang, canonical).
export default function BaPage() {
  return <LandingPage initialLang="ba" />;
}
