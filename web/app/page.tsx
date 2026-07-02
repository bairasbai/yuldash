"use client";

import { MotionConfig } from "framer-motion";
import { LangProvider } from "@/components/lang";
import { Aurora } from "@/components/Aurora";
import { Header } from "@/components/Header";
import { Hero } from "@/components/Hero";
import { SearchTrip } from "@/components/SearchTrip";
import { CoverageMap } from "@/components/CoverageMap";
import { Savings } from "@/components/Savings";
import { Features } from "@/components/Features";
import { HowItWorks } from "@/components/HowItWorks";
import { AppShowcase } from "@/components/AppShowcase";
import { Comparison } from "@/components/Comparison";
import { Routes } from "@/components/Routes";
import { Testimonials } from "@/components/Testimonials";
import { DownloadProvider } from "@/components/DownloadProvider";
import { OrnamentBand } from "@/components/Ornament";
import { Marquee } from "@/components/Marquee";
import { StatsBand } from "@/components/StatsBand";
import { ScrollTop } from "@/components/ScrollTop";
import { Trust } from "@/components/Trust";
import { FounderLetter } from "@/components/FounderLetter";
import { FAQ } from "@/components/FAQ";
import { Partners } from "@/components/Partners";
import { Download } from "@/components/Download";
import { Footer } from "@/components/Footer";
import { StickyDownloadBar } from "@/components/StickyDownloadBar";
import { ScrollProgress } from "@/components/ScrollProgress";
import { CookieConsent } from "@/components/CookieConsent";

export default function Page() {
  return (
    // reducedMotion="user" — framer-motion сам глушит анимации при системной настройке
    <MotionConfig reducedMotion="user">
      <LangProvider>
        <DownloadProvider>
          <ScrollProgress />
          <main className="relative min-h-screen">
            <Aurora />
            <Header />
            <Hero />
            <SearchTrip />
            <Marquee />
            <StatsBand />
            <Features />
            <HowItWorks />
            <AppShowcase />
            <CoverageMap />
            <OrnamentBand />
            <Comparison />
            <Routes />
            <Savings />
            <Testimonials />
            <Trust />
            <OrnamentBand />
            <FounderLetter />
            <FAQ />
            <Partners />
            <Download />
            <Footer />
            <StickyDownloadBar />
            <ScrollTop />
            <CookieConsent />
          </main>
        </DownloadProvider>
      </LangProvider>
    </MotionConfig>
  );
}
