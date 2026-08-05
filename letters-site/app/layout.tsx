import type { Metadata, Viewport } from "next";
import { Cormorant, Caveat, Manrope } from "next/font/google";
import Sky from "@/components/Sky";
import { dawnProgress } from "@/lib/time";
import "./globals.css";

const cormorant = Cormorant({
  variable: "--font-cormorant",
  subsets: ["cyrillic", "latin"],
  display: "swap",
});

const caveat = Caveat({
  variable: "--font-caveat",
  subsets: ["cyrillic", "latin"],
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
      className={`${cormorant.variable} ${caveat.variable} ${manrope.variable} h-full antialiased`}
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
