"use client";

import { m, useMotionValue, useSpring, useReducedMotion } from "framer-motion";
import { useDownload } from "./DownloadProvider";
import { useLang } from "./lang";
import { APK_URL } from "./config";
import { track } from "./analytics";

// Главная кнопка скачивания. Поведение зависит от APP_READY:
// готово → скачивает APK; не готово → открывает модалку «скоро» (через провайдер).
export function DownloadButton({
  label,
  sub,
  size = "lg",
}: {
  label: string;
  sub?: string;
  size?: "lg" | "md";
}) {
  const { request, ready } = useDownload();
  const { tr } = useLang();
  const reduce = useReducedMotion();
  // пока приложения нет — честная подпись «Скоро запуск» вместо «APK · ~12 МБ»
  const subLabel = ready ? sub : tr("cs_badge");
  const pad = size === "lg" ? "px-8 py-4 text-base" : "px-6 py-3 text-sm";

  // магнитный эффект — кнопка тянется за курсором
  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const x = useSpring(mx, { stiffness: 250, damping: 18 });
  const y = useSpring(my, { stiffness: 250, damping: 18 });
  const onMove = (e: React.MouseEvent<HTMLElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    mx.set(((e.clientX - r.left) / r.width - 0.5) * 16);
    my.set(((e.clientY - r.top) / r.height - 0.5) * 12);
  };
  const onLeave = () => {
    mx.set(0);
    my.set(0);
  };
  const inner = (
    <>
      {/* бегущий блик */}
      <span className="pointer-events-none absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/40 to-transparent transition-transform duration-700 group-hover:translate-x-full" />
      <span className="flex items-center gap-2.5">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 3v12" />
          <path d="m7 11 5 5 5-5" />
          <path d="M5 21h14" />
        </svg>
        {label}
      </span>
      {subLabel && <span className="mt-0.5 text-xs font-semibold text-night/90">{subLabel}</span>}
    </>
  );
  const className = `group relative inline-flex flex-col items-center overflow-hidden rounded-canon bg-green-bright font-bold text-night shadow-glow ${pad}`;

  if (ready) {
    return (
      <m.a
        href={APK_URL}
        download
        onClick={() => track("download")}
        onMouseMove={reduce ? undefined : onMove}
        onMouseLeave={reduce ? undefined : onLeave}
        style={reduce ? undefined : { x, y }}
        whileHover={{ scale: 1.04 }}
        whileTap={{ scale: 0.97 }}
        transition={{ type: "spring", stiffness: 400, damping: 22 }}
        className={className}
      >
        {inner}
      </m.a>
    );
  }

  return (
    <m.button
      type="button"
      onClick={request}
      onMouseMove={reduce ? undefined : onMove}
      onMouseLeave={reduce ? undefined : onLeave}
      style={reduce ? undefined : { x, y }}
      whileHover={{ scale: 1.04 }}
      whileTap={{ scale: 0.97 }}
      transition={{ type: "spring", stiffness: 400, damping: 22 }}
      className={className}
    >
      {inner}
    </m.button>
  );
}
