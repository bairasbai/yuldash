"use client";

import Image from "next/image";
import { useLang } from "./lang";
import { SOCIAL, LEGAL } from "./config";
import { KuraiBloom } from "./Ornament";

export function Footer() {
  const { tr } = useLang();
  const year = new Date().getFullYear();

  return (
    <footer className="relative border-t border-white/10 px-6 pb-10 pt-14">
      <div className="mx-auto max-w-6xl">
        <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4">
          {/* Бренд */}
          <div className="lg:col-span-1">
            <div className="flex items-center gap-3">
              <Image src="/logo.png" alt="Юлдаш" width={40} height={40} className="rounded-lg" />
              <div>
                <div className="font-display text-lg font-extrabold">Юлдаш</div>
                <div className="text-sm text-white/50">{tr("foot_tagline")}</div>
              </div>
            </div>
          </div>

          {/* Разделы */}
          <nav className="text-sm">
            <h3 className="mb-4 font-display font-bold text-white/80">{tr("foot_nav")}</h3>
            <ul className="space-y-2.5 text-white/55">
              <li><a href="#features" className="transition-colors hover:text-white">{tr("nav_features")}</a></li>
              <li><a href="#how" className="transition-colors hover:text-white">{tr("hero_cta2")}</a></li>
              <li><a href="#routes" className="transition-colors hover:text-white">{tr("routes_title")}</a></li>
              <li><a href="#trust" className="transition-colors hover:text-white">{tr("nav_trust")}</a></li>
              <li><a href="#faq" className="transition-colors hover:text-white">{tr("faq_title")}</a></li>
              <li><a href="#partners" className="transition-colors hover:text-white">{tr("partner_kicker")}</a></li>
            </ul>
          </nav>

          {/* Документы */}
          <nav className="text-sm">
            <h3 className="mb-4 font-display font-bold text-white/80">{tr("foot_legal")}</h3>
            <ul className="space-y-2.5 text-white/55">
              <li><a href="/safety" className="transition-colors hover:text-white">{tr("nav_trust")}</a></li>
              <li><a href="/help" className="transition-colors hover:text-white">{tr("foot_help")}</a></li>
              <li><a href={LEGAL.privacy} className="transition-colors hover:text-white">{tr("foot_privacy")}</a></li>
              <li><a href={LEGAL.terms} className="transition-colors hover:text-white">{tr("foot_terms")}</a></li>
            </ul>
          </nav>

          {/* Создатель */}
          <nav className="text-sm">
            <h3 className="mb-4 font-display font-bold text-white/80">{tr("foot_contacts")}</h3>
            <p className="mb-3 font-display font-bold text-white">Байрас Байбулов</p>
            <ul className="space-y-2.5 text-white/55">
              <li>
                <a href={SOCIAL.telegram} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 transition-colors hover:text-white">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21.9 4.3 18.6 20c-.2 1.1-.9 1.4-1.8.9l-4.9-3.6-2.4 2.3c-.3.3-.5.5-1 .5l.3-5 9.1-8.2c.4-.4-.1-.6-.6-.2L6.3 13.9l-4.8-1.5c-1-.3-1-1 .2-1.5l18.8-7.2c.9-.3 1.6.2 1.4 1.6Z"/></svg>
                  Telegram
                </a>
              </li>
              <li>
                <a href={SOCIAL.vk} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 transition-colors hover:text-white">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M13.2 17c-5.3 0-8.5-3.7-8.6-9.8h2.7c.1 4.5 2.1 6.4 3.7 6.8V7.2h2.5v3.8c1.6-.2 3.2-2 3.8-3.8h2.5c-.4 2.2-2 4-3.2 4.7 1.2.6 3 2.2 3.7 4.9h-2.8c-.5-1.7-2-3-3.7-3.2V17h-.3Z"/></svg>
                  ВКонтакте
                </a>
              </li>
            </ul>
          </nav>
        </div>

        {/* курай-мотив + его значение (символ дружбы) */}
        <div className="mt-10 flex flex-col items-center gap-2 text-center">
          <span className="text-gold-light/70">
            <KuraiBloom size={34} strokeWidth={1.4} />
          </span>
          <p className="max-w-xs text-xs leading-relaxed text-white/40">{tr("kurai_meaning")}</p>
        </div>

        <div className="mt-8 flex flex-col items-center justify-between gap-3 border-t border-white/10 pt-6 text-sm text-white/45 sm:flex-row">
          <p>© {year} Юлдаш. {tr("foot_rights")}</p>
          <p className="text-gold-light/70">{tr("foot_made")} 🐎</p>
        </div>
      </div>
    </footer>
  );
}
