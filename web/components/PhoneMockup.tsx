"use client";

import { motion, useMotionValue, useSpring, useTransform, useReducedMotion } from "framer-motion";
import { useLang } from "./lang";

// Стилизованный мокап экрана приложения: живая карта (кварталы, парк, река,
// дороги с обводкой), маршрут со свечением, едущая по нему машина, пульс старта
// и выпадающий пин финиша. Чистый CSS/SVG в фирменном стиле (не реальный скрин).
// Анимации маршрута/машины — в globals.css (.mockup-*), на offset-path (плавно, GPU).
export function PhoneMockup() {
  const { lang } = useLang();
  const reduce = useReducedMotion();

  // 3D-тилт телефона за курсором (элитная микро-интеракция)
  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const spring = { stiffness: 150, damping: 18 };
  const rotateX = useSpring(useTransform(my, [-0.5, 0.5], [8, -8]), spring);
  const rotateY = useSpring(useTransform(mx, [-0.5, 0.5], [-10, 10]), spring);
  const onMove = (e: React.MouseEvent) => {
    const r = e.currentTarget.getBoundingClientRect();
    mx.set((e.clientX - r.left) / r.width - 0.5);
    my.set((e.clientY - r.top) / r.height - 0.5);
  };
  const onLeave = () => {
    mx.set(0);
    my.set(0);
  };

  const L = {
    eta_min: "12 мин",
    eta_km: "· 8 км",
    driver: lang === "ru" ? "Айгуль · 4.9 ★" : "Айгөл · 4.9 ★",
    verified: lang === "ru" ? "Проверена" : "Тикшерелгән",
    from: lang === "ru" ? "Уфа, Гагарина" : "Өфө, Гагарин",
    to: lang === "ru" ? "Стерлитамак" : "Стәрлетамаҡ",
    price: lang === "ru" ? "350 ₽" : "350 ₽",
    go: lang === "ru" ? "Поехали" : "Киттек",
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 40 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 1, delay: 0.3, ease: [0.21, 0.47, 0.32, 0.98] }}
      style={{ perspective: 1200 }}
      onMouseMove={reduce ? undefined : onMove}
      onMouseLeave={reduce ? undefined : onLeave}
      className="relative"
    >
      <motion.div
        style={reduce ? undefined : { rotateX, rotateY, transformStyle: "preserve-3d" }}
      >
      <motion.div
        animate={{ y: [0, -14, 0] }}
        transition={{ duration: 6, repeat: Infinity, ease: "easeInOut" }}
        className="relative mx-auto h-[600px] w-[296px] rounded-[44px] border-[10px] border-[#1c2722] bg-forest shadow-card"
      >
        {/* блик-свечение под телефоном */}
        <div className="absolute -inset-6 -z-10 rounded-[60px] bg-green-bright/20 blur-3xl" />

        {/* динамический «островок» */}
        <div className="absolute left-1/2 top-3 z-20 h-6 w-24 -translate-x-1/2 rounded-full bg-black/80" />

        <div className="relative h-full w-full overflow-hidden rounded-[34px] bg-[#0e1714]">
          {/* ===== Карта ===== */}
          <div className="relative h-[58%] w-full">
            <svg
              viewBox="0 0 276 336"
              preserveAspectRatio="xMidYMid slice"
              className="absolute inset-0 h-full w-full"
              aria-hidden="true"
            >
              <defs>
                <radialGradient id="mapBg" cx="32%" cy="12%" r="110%">
                  <stop offset="0%" stopColor="#17291f" />
                  <stop offset="60%" stopColor="#0e1714" />
                  <stop offset="100%" stopColor="#0a1310" />
                </radialGradient>
                <linearGradient id="routeGrad" x1="0" y1="1" x2="1" y2="0">
                  <stop offset="0%" stopColor="#0E8247" />
                  <stop offset="60%" stopColor="#2FB36E" />
                  <stop offset="100%" stopColor="#7FE3AB" />
                </linearGradient>
                <filter id="routeGlow" x="-40%" y="-40%" width="180%" height="180%">
                  <feGaussianBlur stdDeviation="5" />
                </filter>
              </defs>

              {/* фон */}
              <rect width="276" height="336" fill="url(#mapBg)" />

              {/* кварталы (низкоконтрастные блоки) */}
              <g fill="#13201a" opacity="0.7">
                <rect x="18" y="24" width="58" height="46" rx="7" />
                <rect x="92" y="16" width="50" height="38" rx="7" />
                <rect x="160" y="30" width="46" height="40" rx="7" />
                <rect x="214" y="44" width="50" height="52" rx="7" />
                <rect x="14" y="88" width="44" height="56" rx="7" />
                <rect x="80" y="78" width="56" height="46" rx="7" />
                <rect x="200" y="118" width="60" height="48" rx="7" />
                <rect x="22" y="170" width="54" height="50" rx="7" />
                <rect x="150" y="182" width="52" height="46" rx="7" />
                <rect x="92" y="210" width="48" height="44" rx="7" />
                <rect x="206" y="206" width="56" height="58" rx="7" />
                <rect x="20" y="248" width="50" height="56" rx="7" />
                <rect x="150" y="262" width="58" height="56" rx="7" />
                <rect x="90" y="280" width="46" height="44" rx="7" />
              </g>

              {/* парк */}
              <ellipse cx="118" cy="120" rx="40" ry="30" fill="#2FB36E" opacity="0.07" />
              {/* река */}
              <path
                d="M-10 250 C 60 235, 90 285, 150 268 S 250 230, 290 250"
                fill="none"
                stroke="#5FA8C8"
                strokeWidth="16"
                strokeLinecap="round"
                opacity="0.08"
              />

              {/* дороги — обводка (casing) + полотно */}
              <g fill="none" strokeLinecap="round">
                <g stroke="#0b1410" strokeWidth="11">
                  <path d="M-10 84 L120 110 L200 64 L290 100" />
                  <path d="M40 -10 L66 130 L34 300" />
                  <path d="M-10 224 L140 244 L250 206 L290 232" />
                  <path d="M196 -10 L222 150 L286 296" />
                </g>
                <g stroke="#22332b" strokeWidth="6">
                  <path d="M-10 84 L120 110 L200 64 L290 100" />
                  <path d="M40 -10 L66 130 L34 300" />
                  <path d="M-10 224 L140 244 L250 206 L290 232" />
                  <path d="M196 -10 L222 150 L286 296" />
                </g>
              </g>

              {/* маршрут: свечение + линия */}
              <path
                d="M66 290 C 110 245 120 175 168 145 S 224 90 220 64"
                fill="none"
                stroke="#2FB36E"
                strokeWidth="6"
                strokeLinecap="round"
                opacity="0.4"
                filter="url(#routeGlow)"
              />
              <path
                className="mockup-route"
                d="M66 290 C 110 245 120 175 168 145 S 224 90 220 64"
                fill="none"
                stroke="url(#routeGrad)"
                strokeWidth="5"
                strokeLinecap="round"
              />

              {/* старт (золотая точка + пульс) */}
              <circle className="mockup-ping" cx="66" cy="290" r="7" fill="#D89B12" />
              <circle cx="66" cy="290" r="7" fill="#D89B12" stroke="#0a1310" strokeWidth="3" />

              {/* финиш (пин выпадает) */}
              <g className="mockup-pin">
                <circle cx="220" cy="64" r="12" fill="#D89B12" />
                <path
                  d="M220 56 c-2.6 0-4.7 2.1-4.7 4.7 0 3.5 4.7 8.3 4.7 8.3s4.7-4.8 4.7-8.3c0-2.6-2.1-4.7-4.7-4.7z"
                  fill="#0a1310"
                />
                <circle cx="220" cy="60.7" r="1.7" fill="#D89B12" />
              </g>

              {/* машина едет по маршруту (стрелка-пак) */}
              <g className="mockup-car">
                <circle r="9" fill="#2FB36E" stroke="#0a1310" strokeWidth="3" />
                <path d="M-2.5 -3.2 L4 0 L-2.5 3.2 Z" fill="#0a1310" />
              </g>
            </svg>

            {/* верхний скрим — читаемость над картой */}
            <div className="pointer-events-none absolute inset-x-0 top-0 z-[5] h-20 bg-gradient-to-b from-black/45 to-transparent" />

            {/* ETA — статус ниже островка (не пересекается с ним) */}
            <div className="glass absolute left-3 top-12 z-10 flex items-center gap-2 rounded-full py-1.5 pl-2 pr-3.5">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-green-bright/20 text-green-glow">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7v5l3 2" />
                </svg>
              </span>
              <span className="text-xs font-semibold leading-none text-white">
                {L.eta_min}
                <span className="ml-1 font-medium text-white/45">{L.eta_km}</span>
              </span>
            </div>

            {/* кнопка «моё местоположение» — внизу справа */}
            <button className="glass absolute bottom-3 right-3 z-10 flex h-9 w-9 items-center justify-center rounded-full text-green-glow transition-transform hover:scale-105 active:scale-95">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="3" />
                <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
              </svg>
            </button>
          </div>

          {/* ===== Нижняя карточка поездки ===== */}
          <div className="absolute inset-x-0 bottom-0 rounded-t-[26px] bg-[#141d19] p-4 pb-6">
            <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-white/15" />

            <div className="mb-4 flex items-center gap-3">
              <div className="relative">
                <div className="flex h-11 w-11 items-center justify-center rounded-full bg-gradient-to-br from-green-bright/30 to-green-deep/40 font-display text-base font-extrabold text-green-glow ring-1 ring-green-bright/20">
                  {L.driver.charAt(0)}
                </div>
                <span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full bg-green-bright ring-2 ring-[#141d19]" />
              </div>
              <div className="flex-1">
                <div className="text-sm font-bold text-white">{L.driver}</div>
                <div className="mt-0.5 inline-flex items-center gap-1 rounded-full bg-green-bright/15 px-2 py-0.5 text-[10px] font-semibold text-green-glow">
                  <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"><path d="m5 13 4 4L19 7" /></svg>
                  {L.verified}
                </div>
              </div>
              <div className="text-right">
                <div className="font-display text-lg font-extrabold text-gold-light">{L.price}</div>
              </div>
            </div>

            {/* маршрут A → B с соединителем */}
            <div className="relative mb-4">
              {/* полоса ровно по центру кругов (центр круга = 5px от левого края) */}
              <div className="absolute left-1/2 top-[13px] h-[22px] w-0.5 -translate-x-1/2 bg-gradient-to-b from-gold to-green-bright" style={{ left: "5px" }} />
              <div className="mb-3 flex items-center gap-3 text-xs text-white/85">
                <span className="h-2.5 w-2.5 shrink-0 rounded-full border-2 border-gold bg-[#141d19]" />
                {L.from}
              </div>
              <div className="flex items-center gap-3 text-xs text-white/85">
                <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-green-bright" />
                {L.to}
              </div>
            </div>

            <div className="rounded-full bg-gradient-to-r from-green-bright to-green py-2.5 text-center text-sm font-bold text-night shadow-[0_8px_24px_-8px_rgba(47,179,110,0.6)]">
              {L.go}
            </div>
          </div>
        </div>
      </motion.div>
      </motion.div>
    </motion.div>
  );
}
