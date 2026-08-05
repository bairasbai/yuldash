import type { Metadata, Viewport } from "next";
import { Lora, Marck_Script, Manrope } from "next/font/google";
import Sky from "@/components/Sky";
import { dawnProgress } from "@/lib/time";
import "./globals.css";

// Шрифт писем. Мягкий и плотный: на телефоне читается легче
// тонких вытянутых антикв, а тон остаётся книжным, не парадным.
const letterFont = Lora({
  variable: "--font-letter",
  subsets: ["cyrillic", "latin"],
  display: "swap",
});

// Почерк для подписи и надписи на конверте: перо с наклоном,
// а не маркер — под сургучом это единственное, что не спорит
const handwriting = Marck_Script({
  variable: "--font-hand-script",
  subsets: ["cyrillic", "latin"],
  weight: "400",
  display: "swap",
});

const manrope = Manrope({
  variable: "--font-manrope",
  subsets: ["cyrillic", "latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "Пока рассветает",
  description: "Письма до встречи",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: "#04060f",
  // Небо должно уходить под чёлку телефона, а не упираться в белую полосу
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="ru"
      className={`${letterFont.variable} ${handwriting.variable} ${manrope.variable} h-full antialiased`}
    >
      <body className="min-h-full">
        {/*
          Небо живёт здесь, а не на страницах: при переходе между разделами
          оно не перерисовывается. Ощущение одного пространства, по которому
          ходишь, а не отдельных экранов.
        */}
        <Sky progress={dawnProgress()} />
        {children}
      </body>
    </html>
  );
}
