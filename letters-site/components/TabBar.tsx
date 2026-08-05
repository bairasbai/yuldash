"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  ArchiveIcon,
  EnvelopeIcon,
  HeartIcon,
  MapPin,
  TwoPeople,
} from "./Icons";
import { thinkOfYou } from "@/app/actions";
import { haptic } from "@/lib/haptics";

/**
 * Нижняя панель. Центральная кнопка — не «добавить», а «думаю о тебе»:
 * единственное действие на сайте, которое срабатывает мгновенно
 * и сразу доходит до второго.
 */

const TABS = [
  { href: "/", label: "Письмо", Icon: EnvelopeIcon },
  { href: "/letters", label: "Архив", Icon: ArchiveIcon },
  { href: "/together", label: "Вместе", Icon: TwoPeople },
  { href: "/us", label: "Мы", Icon: MapPin },
];

export default function TabBar() {
  const pathname = usePathname();
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  const press = async () => {
    if (busy || sent) return;
    haptic(12);
    setBusy(true);
    await thinkOfYou();
    setBusy(false);
    setSent(true);
    window.setTimeout(() => setSent(false), 4000);
  };

  const isActive = (href: string) =>
    href === "/" ? pathname === "/" : pathname.startsWith(href);

  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-30 border-t border-panel-border bg-white/92 backdrop-blur-xl"
      style={{ paddingBottom: "max(0.5rem, env(safe-area-inset-bottom))" }}
    >
      <div className="mx-auto flex w-full max-w-[38rem] items-center justify-around px-2 pt-2">
        {TABS.slice(0, 2).map(({ href, label, Icon }) => (
          <Tab key={href} href={href} label={label} Icon={Icon} active={isActive(href)} />
        ))}

        {/* Центральная кнопка */}
        <button
          type="button"
          onClick={press}
          aria-label="Сказать, что думаешь о нём"
          className="relative -mt-7 flex h-14 w-14 shrink-0 items-center justify-center rounded-full text-white transition-transform duration-300 active:scale-95"
          style={{
            background:
              "linear-gradient(160deg, #e08c76 0%, var(--color-coral) 60%, #c96a58 100%)",
            boxShadow:
              "0 6px 16px -4px rgb(212 121 106 / 0.55), 0 2px 4px rgb(47 38 32 / 0.12)",
          }}
        >
          <AnimatePresence>
            {sent &&
              [0, 1].map((i) => (
                <motion.span
                  key={i}
                  className="absolute inset-0 rounded-full border border-[var(--color-coral)]"
                  initial={{ scale: 1, opacity: 0.7 }}
                  animate={{ scale: 2, opacity: 0 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 1.4, delay: i * 0.2, ease: "easeOut" }}
                />
              ))}
          </AnimatePresence>
          <motion.span
            animate={sent ? { scale: [1, 1.25, 1] } : { scale: 1 }}
            transition={{ duration: 0.6 }}
          >
            <HeartIcon size={23} />
          </motion.span>
        </button>

        {TABS.slice(2).map(({ href, label, Icon }) => (
          <Tab key={href} href={href} label={label} Icon={Icon} active={isActive(href)} />
        ))}
      </div>
    </nav>
  );
}

function Tab({
  href,
  label,
  Icon,
  active,
}: {
  href: string;
  label: string;
  Icon: (p: { size?: number; className?: string }) => React.ReactElement;
  active: boolean;
}) {
  return (
    <Link
      href={href}
      className="flex w-16 flex-col items-center gap-1 pb-1 transition-colors duration-300"
      style={{ color: active ? "var(--color-coral)" : "var(--color-sky-ink-soft)" }}
    >
      <Icon size={22} />
      <span className="font-sans text-[0.62rem] tracking-wide">{label}</span>
    </Link>
  );
}
