import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import Script from "next/script";
import { SITE_URL, METRIKA_ID } from "@/components/config";
import { StructuredData } from "@/components/StructuredData";
import { MotionProvider } from "@/components/MotionProvider";
import "./globals.css";

// Шрифты лежат в репозитории (app/fonts, лицензия SIL OFL 1.1 — OFL-*.txt рядом), а не
// скачиваются с Google Fonts при каждой сборке: сборка падала, когда Google не отвечал
// (CI 16.10, 17.10, 24.10 — «next/font/google queries have exactly one entry»).
// Файлы — из google/fonts (ofl/inter, ofl/montserrat), урезаны fontTools до латиницы, кириллицы
// и расширенной кириллицы (башкирские ҡ ғ ҙ һ ә ө ү ң — U+0460-052F) плюс «₽». Как пересобрать —
// app/fonts/README.md.

// Body — Inter (переменный по толщине 100–900, оптический размер — 14, как отдавал Google).
const inter = localFont({
  src: "./fonts/Inter-latin-cyrillic.woff2",
  weight: "100 900",
  variable: "--font-sans",
  display: "swap",
});

// Display — Montserrat: чистый геометрик с ПОЛНЫМ башкирским. Заменил Unbounded — у того
// башкирские глифы выглядели неровно/непривычно. Только 700–900: 600 на display почти не используется.
const montserrat = localFont({
  src: "./fonts/Montserrat-700-900-latin-cyrillic.woff2",
  weight: "700 900",
  variable: "--font-display",
  display: "swap",
});

const title = "Юлдаш — попутки между своими";
const description =
  "Юлдаш — приложение попуток по Башкортостану между своими. Доверие, тёплые поездки и честная цена. Скачать для Android.";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title,
  description,
  applicationName: "Юлдаш",
  authors: [{ name: "Юлдаш" }],
  keywords: ["Юлдаш", "попутки", "Башкортостан", "поездки", "Android", "Yuldash", "карпулинг"],
  alternates: {
    canonical: SITE_URL,
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
    locale: "ru_RU",
    url: SITE_URL,
    siteName: "Юлдаш",
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "Юлдаш — попутки между своими" }],
  },
  twitter: {
    card: "summary_large_image",
    title,
    description,
    images: ["/og.png"],
  },
  icons: {
    icon: [
      { url: "/favicon.ico", sizes: "any" },
      { url: "/icon-192.png", type: "image/png", sizes: "192x192" },
      { url: "/icon-512.png", type: "image/png", sizes: "512x512" },
    ],
    apple: "/apple-touch-icon.png",
  },
};

export const viewport: Viewport = {
  themeColor: "#0A1410",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru" className={`${inter.variable} ${montserrat.variable}`}>
      <head>
        <StructuredData />
      </head>
      <body>
        {/* skip-link для клавиатуры/скринридеров */}
        <a
          href="#top"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded-full focus:bg-green-bright focus:px-4 focus:py-2 focus:font-semibold focus:text-night"
        >
          Перейти к содержимому
        </a>

        <MotionProvider>{children}</MotionProvider>

        {/* Яндекс.Метрика — грузится только если задан METRIKA_ID */}
        {METRIKA_ID && (
          <>
            <Script id="yandex-metrika" strategy="afterInteractive">
              {`(function(m,e,t,r,i,k,a){if(typeof m[i]!=="function"){m[i]=function(){(m[i].a=m[i].a||[]).push(arguments)}}m[i].l=1*new Date();k=e.createElement(t),a=e.getElementsByTagName(t)[0],k.async=1,k.src=r,a.parentNode.insertBefore(k,a)})(window,document,"script","https://mc.yandex.ru/metrika/tag.js","ym");window.ym(${METRIKA_ID},"init",{clickmap:true,trackLinks:true,accurateTrackBounce:true,webvisor:false,defer:true});`}
            </Script>
            {/* фолбэк без JS */}
            <noscript>
              <div>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={`https://mc.yandex.ru/watch/${METRIKA_ID}`} style={{ position: "absolute", left: "-9999px" }} alt="" />
              </div>
            </noscript>
          </>
        )}
      </body>
    </html>
  );
}
