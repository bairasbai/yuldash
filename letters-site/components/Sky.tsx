"use client";

import { useEffect, useMemo, useRef } from "react";
import { makeStars, skyPalette } from "@/lib/sky";

/** Облака стоят на фиксированных местах — случайность тут не нужна. */
const CLOUDS = [
  { left: "-12%", top: "58%", width: "58%", height: "4.5%", opacity: 0.32, blur: "20px", duration: 190, drift: "7vw" },
  { left: "48%", top: "63%", width: "64%", height: "5.5%", opacity: 0.26, blur: "24px", duration: 240, drift: "-9vw" },
  { left: "8%", top: "70%", width: "46%", height: "3.5%", opacity: 0.34, blur: "16px", duration: 150, drift: "5vw" },
  { left: "56%", top: "74%", width: "52%", height: "3%", opacity: 0.3, blur: "18px", duration: 210, drift: "-6vw" },
  { left: "-6%", top: "79%", width: "70%", height: "2.6%", opacity: 0.22, blur: "14px", duration: 170, drift: "4vw" },
];

/**
 * Небо над сайтом. Единственный входной параметр — progress (0…1):
 * 0 — ночь в день первого письма, 1 — утро встречи.
 *
 * Всё, что тут происходит, завязано на него: цвет градиента, сколько
 * видно звёзд, вылезло ли солнце из-за хребта.
 */
export default function Sky({
  progress,
  /** true — небо живёт внутри шапки, а не за всей страницей */
  inset = false,
}: {
  progress: number;
  inset?: boolean;
}) {
  const palette = useMemo(() => skyPalette(progress), [progress]);
  const stars = useMemo(() => makeStars(140), []);
  const rootRef = useRef<HTMLDivElement>(null);

  // Мягкий параллакс: небо чуть отстаёт от курсора и наклона телефона.
  // Сдвиг крошечный — его не замечают, но картинка перестаёт быть плоской.
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    let raf = 0;
    let targetX = 0;
    let targetY = 0;
    let currentX = 0;
    let currentY = 0;

    const tick = () => {
      currentX += (targetX - currentX) * 0.06;
      currentY += (targetY - currentY) * 0.06;
      root.style.setProperty("--px", currentX.toFixed(3));
      root.style.setProperty("--py", currentY.toFixed(3));
      raf = requestAnimationFrame(tick);
    };

    const onMouse = (e: MouseEvent) => {
      targetX = (e.clientX / window.innerWidth - 0.5) * 2;
      targetY = (e.clientY / window.innerHeight - 0.5) * 2;
    };

    const onTilt = (e: DeviceOrientationEvent) => {
      if (e.gamma == null || e.beta == null) return;
      targetX = Math.max(-1, Math.min(1, e.gamma / 35));
      targetY = Math.max(-1, Math.min(1, (e.beta - 45) / 35));
    };

    window.addEventListener("mousemove", onMouse, { passive: true });
    window.addEventListener("deviceorientation", onTilt);
    raf = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("mousemove", onMouse);
      window.removeEventListener("deviceorientation", onTilt);
    };
  }, []);

  // Чем светлее небо, тем плотнее должны быть карточки поверх него,
  // иначе полупрозрачные панели растворяются и текст в них плывёт.
  const light = Math.max(0, Math.min(1, (progress - 0.7) / 0.3));
  const panelBg = (0.045 + light * 0.075).toFixed(3);
  const panelBorder = (0.1 + light * 0.09).toFixed(3);
  const scrim = (light * 0.22).toFixed(3);

  return (
    <div
      ref={rootRef}
      aria-hidden
      className={`sky-gradient grain pointer-events-none overflow-hidden transition-[background] duration-[2s] ${
        inset ? "absolute inset-0" : "vignette fixed inset-0 -z-10"
      }`}
      style={
        {
          "--sky-zenith": palette.zenith,
          "--sky-upper": palette.upper,
          "--sky-mid": palette.mid,
          "--sky-horizon": palette.horizon,
          "--sky-glow": palette.glow,
          "--px": 0,
          "--py": 0,
        } as React.CSSProperties
      }
    >
      {/*
        Переменные уезжают в :root, а не на этот блок: карточки и текст
        лежат в другой ветке дерева и должны их видеть.
      */}
      <style>{`:root{--panel-bg:rgb(255 255 255 / ${panelBg});--panel-border:rgb(255 255 255 / ${panelBorder});}`}</style>

      {/* Лёгкая вуаль по центру — страховка читаемости на светлом небе */}
      {Number(scrim) > 0.005 && (
        <div
          className="absolute inset-0"
          style={{
            background: `radial-gradient(70% 50% at 50% 42%, rgb(8 20 40 / ${scrim}) 0%, transparent 72%)`,
          }}
        />
      )}

      {/* Луна. Держится дольше звёзд — как в жизни: небо уже светлеет,
          а она всё ещё висит. */}
      {palette.stars > 0.02 && (
        <div
          className="absolute transition-opacity duration-[3s]"
          style={{
            // Правый верх — единственный угол, который пуст на всех
            // экранах: слева ссылка «назад», по центру заголовок.
            left: "78%",
            top: "8%",
            opacity: Math.min(0.82, palette.stars * 1.1),
            transform:
              "translate3d(calc(var(--px) * -14px), calc(var(--py) * -8px), 0)",
          }}
        >
          {/* Гало держим едва заметным: луна не должна спорить с отсчётом */}
          <div
            className="absolute -inset-5 rounded-full"
            style={{
              background:
                "radial-gradient(circle, rgb(226 236 255 / 0.08) 0%, transparent 62%)",
            }}
          />
          <svg width="44" height="44" viewBox="0 0 54 54" className="relative">
            <defs>
              <mask id="moon-cut">
                <rect width="54" height="54" fill="black" />
                <circle cx="27" cy="27" r="16" fill="white" />
                <circle cx="18.5" cy="22" r="15" fill="black" />
              </mask>
              <radialGradient id="moon-face" cx="0.62" cy="0.42">
                <stop offset="0%" stopColor="#f4f7ff" />
                <stop offset="70%" stopColor="#dbe4f5" />
                <stop offset="100%" stopColor="#b3c1da" />
              </radialGradient>
            </defs>
            <circle
              cx="27"
              cy="27"
              r="16"
              fill="url(#moon-face)"
              mask="url(#moon-cut)"
            />
          </svg>
        </div>
      )}

      {/* Звёзды. К рассвету растворяются сами. */}
      <div
        className="absolute inset-0 transition-opacity duration-[3s]"
        style={{
          opacity: palette.stars,
          transform:
            "translate3d(calc(var(--px) * -10px), calc(var(--py) * -6px), 0)",
        }}
      >
        {stars.map((s, i) => (
          <span
            key={i}
            className="star"
            style={
              {
                left: `${s.x}%`,
                top: `${s.y}%`,
                "--star-size": s.size,
                "--star-base": s.base,
                "--star-dur": s.duration,
                "--star-delay": s.delay,
                "--star-color": s.tint,
              } as React.CSSProperties
            }
          />
        ))}

        {/* Одна звезда крупнее прочих — пусть будет ваша. */}
        <span
          className="star star-bright"
          style={
            {
              left: "24%",
              top: "21%",
              "--star-size": 3.4,
              "--star-base": 1,
              "--star-dur": 6,
              "--star-delay": 0,
            } as React.CSSProperties
          }
        />
      </div>

      {/* Падающие звёзды — редко, чтобы каждая была событием. */}
      {palette.stars > 0.25 && (
        <>
          <span
            className="shooting-star w-24"
            style={
              {
                left: "12%",
                top: "14%",
                rotate: "18deg",
                "--shoot-dur": 23,
                "--shoot-delay": 6,
              } as React.CSSProperties
            }
          />
          <span
            className="shooting-star w-16"
            style={
              {
                left: "58%",
                top: "8%",
                rotate: "24deg",
                "--shoot-dur": 37,
                "--shoot-delay": 19,
              } as React.CSSProperties
            }
          />
        </>
      )}

      {/*
        Облачные полосы. Тело облака холодное, низ подсвечен зарёй —
        от этого у неба появляется глубина, которой не даёт градиент.
      */}
      {CLOUDS.map((c, i) => (
        <div
          key={i}
          className="cloud"
          style={
            {
              left: c.left,
              top: c.top,
              width: c.width,
              height: c.height,
              opacity: c.opacity,
              background: `linear-gradient(to bottom, ${palette.mid} 0%, ${palette.horizon} 62%, ${palette.glow} 100%)`,
              "--cloud-blur": c.blur,
              "--cloud-dur": c.duration,
              "--drift": c.drift,
            } as React.CSSProperties
          }
        />
      ))}

      {/* Зарево над хребтом — медленно дышит. */}
      <div
        className="absolute inset-x-0 bottom-0 h-[46vh]"
        style={{
          background: `radial-gradient(60% 100% at 62% 100%, ${palette.glow} 0%, transparent 70%)`,
          animation: "breathe 11s ease-in-out infinite",
          opacity: 0.6,
        }}
      />

      {/* Солнце. Появляется только в последние дни отсчёта. */}
      {palette.sun > 0.01 && (
        <div
          className="absolute transition-all duration-[2s]"
          style={{
            left: "62%",
            bottom: `${4 + palette.sun * 7}vh`,
            width: 128,
            height: 128,
            marginLeft: -64,
            borderRadius: "9999px",
            background: `radial-gradient(circle, rgba(255,238,200,${palette.sun}) 0%, rgba(255,186,110,${palette.sun * 0.85}) 45%, transparent 70%)`,
            filter: "blur(6px)",
            opacity: Math.min(1, palette.sun * 1.4),
          }}
        />
      )}

      {/*
        Туман в долине между хребтами. К утру густеет — так и бывает
        в горах перед восходом, и именно это отделяет один хребет
        от другого.
      */}
      <div
        className="absolute inset-x-0"
        style={{
          bottom: "13vh",
          height: "9vh",
          background: `linear-gradient(to top, transparent 0%, ${palette.horizon}88 45%, transparent 100%)`,
          filter: "blur(12px)",
          opacity: 0.4 + palette.sun * 0.35,
        }}
      />

      {/*
        Три хребта — это не декор, а глубина: дальний размыт дымкой,
        ближний почти чёрный. Уральские сопки пологие и округлые,
        поэтому никаких острых пиков.
      */}
      <svg
        className="absolute inset-x-0 bottom-0 h-[26vh] w-full"
        viewBox="0 0 1440 200"
        preserveAspectRatio="none"
        style={{ transform: "translate3d(calc(var(--px) * 5px), 0, 0) scale(1.04)" }}
      >
        <path
          d="M0,152 C74,148 128,138 196,132 C268,126 316,110 384,100 C444,91 492,96 548,108 C606,120 652,116 712,102 C776,87 820,72 884,76 C944,80 990,102 1048,114 C1108,126 1156,120 1216,110 C1278,100 1332,108 1388,122 C1408,127 1424,132 1440,136 L1440,200 L0,200 Z"
          fill={palette.ridgeFar}
          opacity="0.55"
        />
      </svg>

      <svg
        className="absolute inset-x-0 bottom-0 h-[19vh] w-full"
        viewBox="0 0 1440 200"
        preserveAspectRatio="none"
        style={{ transform: "translate3d(calc(var(--px) * 9px), 0, 0) scale(1.05)" }}
      >
        <path
          d="M0,170 C68,166 122,156 188,146 C258,135 306,112 372,92 C424,76 468,62 522,58 C584,53 622,80 682,104 C734,125 776,142 838,148 C900,154 950,140 1012,130 C1076,120 1128,128 1188,142 C1250,156 1312,164 1372,170 C1396,172 1418,174 1440,175 L1440,200 L0,200 Z"
          fill={palette.ridgeFar}
        />
      </svg>

      {/* Ближний склон — тот, с которого вы встречали рассвет */}
      <svg
        className="absolute inset-x-0 bottom-0 h-[11vh] w-full"
        viewBox="0 0 1440 200"
        preserveAspectRatio="none"
        style={{ transform: "translate3d(calc(var(--px) * 15px), 0, 0) scale(1.07)" }}
      >
        <path
          d="M0,186 C90,182 156,174 232,162 C312,149 366,130 442,120 C512,111 566,124 638,138 C704,151 752,162 820,164 C892,166 946,154 1018,148 C1090,142 1148,150 1216,160 C1288,171 1352,180 1440,184 L1440,200 L0,200 Z"
          fill={palette.ridgeNear}
        />
      </svg>
    </div>
  );
}
