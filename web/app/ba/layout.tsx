import type { Metadata } from "next";
import { SITE_URL } from "@/components/config";

// SEO для башкирской версии: свой title/description на башкирском, canonical=/ba,
// hreflang связывает RU (/) и BA (/ba) как альтернативы одной страницы.
const title = "Юлдаш — үҙ-ара юлдаштар";
const description =
  "Юлдаш — Башҡортостан буйлап үҙ-ара юлдаштар ҡушымтаһы. Ышаныс, йылы юлдар һәм ғәҙел хаҡ. Android өсөн йөкләргә.";

export const metadata: Metadata = {
  title,
  description,
  alternates: {
    canonical: `${SITE_URL}/ba`,
    languages: {
      "ru-RU": SITE_URL,
      "ba-RU": `${SITE_URL}/ba`,
      "x-default": SITE_URL,
    },
  },
  openGraph: {
    title,
    description,
    type: "website",
    locale: "ba_RU",
    url: `${SITE_URL}/ba`,
    siteName: "Юлдаш",
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "Юлдаш — үҙ-ара юлдаштар" }],
  },
};

export default function BaLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
