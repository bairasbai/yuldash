"use client";

import { motion, useMotionValue, useSpring, useTransform } from "motion/react";
import WaxSeal from "./WaxSeal";

/**
 * Конверт с сургучной печатью.
 *
 * Три состояния: лежит запечатанный → вскрывается → пустой уходит.
 * Клапан настоящий: откидывается назад по оси X в перспективе,
 * печать при этом разламывается пополам и падает.
 */

type Props = {
  n: number;
  dateLabel: string;
  state: "sealed" | "opening" | "gone";
  onOpen: () => void;
};

export default function Envelope({ n, dateLabel, state, onOpen }: Props) {
  const opening = state !== "sealed";

  // Конверт слегка поворачивается вслед за курсором — будто лежит
  // на ладони и ты его чуть наклоняешь, чтобы разглядеть.
  const px = useMotionValue(0);
  const py = useMotionValue(0);
  const spring = { stiffness: 110, damping: 16, mass: 0.6 };
  const tiltX = useSpring(useTransform(py, [-0.5, 0.5], [8, -8]), spring);
  const tiltY = useSpring(useTransform(px, [-0.5, 0.5], [-10, 10]), spring);

  const follow = (e: React.MouseEvent<HTMLElement>) => {
    if (opening) return;
    const r = e.currentTarget.getBoundingClientRect();
    px.set((e.clientX - r.left) / r.width - 0.5);
    py.set((e.clientY - r.top) / r.height - 0.5);
  };

  const release = () => {
    px.set(0);
    py.set(0);
  };

  return (
    <motion.button
      type="button"
      onClick={state === "sealed" ? onOpen : undefined}
      onMouseMove={follow}
      onMouseLeave={release}
      aria-label={`Открыть письмо номер ${n}`}
      className="group relative block cursor-pointer focus:outline-none"
      style={{ perspective: 1400 }}
      initial={{ opacity: 0, y: 26, scale: 0.96 }}
      animate={
        state === "gone"
          ? { opacity: 0, y: 40, scale: 0.94 }
          : { opacity: 1, y: 0, scale: 1 }
      }
      transition={{ duration: 1.1, ease: [0.22, 1, 0.36, 1] }}
    >
      {/* Лёгкое покачивание, пока конверт ждёт. Как будто лежит на весу. */}
      <motion.div
        className="relative"
        animate={
          state === "sealed"
            ? { y: [0, -6, 0], rotateZ: [-0.6, 0.6, -0.6] }
            : { y: 0, rotateZ: 0 }
        }
        transition={
          state === "sealed"
            ? { duration: 7, repeat: Infinity, ease: "easeInOut" }
            : { duration: 0.6 }
        }
        style={{
          transformStyle: "preserve-3d",
          rotateX: opening ? 0 : tiltX,
          rotateY: opening ? 0 : tiltY,
        }}
      >
        <div className="relative h-[15.5rem] w-[22rem] max-w-[86vw] sm:h-[16.5rem] sm:w-[24rem]">
          {/* Тень под конвертом */}
          <div
            className="absolute -bottom-6 left-1/2 h-8 w-[78%] -translate-x-1/2 rounded-[50%] blur-xl transition-opacity duration-700"
            style={{
              background: "rgb(0 0 0 / 0.5)",
              opacity: opening ? 0.2 : 0.45,
            }}
          />

          {/* Задняя стенка. Внутри темнее — иначе конверт выглядит плоским. */}
          <div
            className="absolute inset-0 rounded-[14px]"
            style={{
              background:
                "linear-gradient(160deg, var(--color-paper-deep) 0%, #d7c6ab 100%)",
              // Три тени вместо одной: контактная у самой бумаги, средняя
              // и дальняя мягкая. Так предмет ложится на плоскость,
              // а не висит над ней.
              boxShadow:
                "0 2px 4px rgb(0 0 0 / 0.28), 0 12px 24px -8px rgb(0 0 0 / 0.4), 0 36px 70px -24px rgb(0 0 0 / 0.6), inset 0 8px 18px rgb(0 0 0 / 0.18)",
            }}
          />

          {/* Письмо. Выезжает из конверта — белее его и с собственной тенью,
              иначе на бежевом фоне лист просто не читается. */}
          <motion.div
            className="absolute inset-x-[6%] rounded-t-[5px]"
            style={{
              background:
                "linear-gradient(180deg, #fffdf8 0%, #fbf5e9 60%, var(--color-paper) 100%)",
              height: "82%",
              top: "8%",
              zIndex: 1,
              boxShadow:
                "0 -12px 26px rgb(0 0 0 / 0.32), 0 0 0 1px rgb(0 0 0 / 0.05)",
            }}
            initial={false}
            animate={
              opening
                ? { y: -132, scale: 1.02, opacity: 1 }
                : { y: 0, scale: 1, opacity: 0 }
            }
            transition={{ duration: 1, delay: 0.46, ease: [0.22, 1, 0.36, 1] }}
          >
            {/* Намёк на строчки — письмо выезжает исписанной стороной */}
            <div className="space-y-2.5 px-6 pt-7 opacity-25">
              {[92, 78, 86, 64].map((w, i) => (
                <div
                  key={i}
                  className="h-[3px] rounded-full bg-ink"
                  style={{ width: `${w}%` }}
                />
              ))}
            </div>
          </motion.div>

          {/* Передняя стенка с диагональными сгибами */}
          <div
            className="paper-grain absolute inset-0 overflow-hidden rounded-[14px]"
            style={{ zIndex: 2 }}
          >
            <div
              className="absolute inset-x-0 bottom-0 top-[38%]"
              style={{
                background:
                  "linear-gradient(175deg, var(--color-paper-deep) 0%, var(--color-paper) 55%, var(--color-paper-deep) 100%)",
                clipPath: "polygon(0 0, 50% 34%, 100% 0, 100% 100%, 0 100%)",
                boxShadow: "inset 0 2px 6px rgb(0 0 0 / 0.06)",
              }}
            />
            {/* Боковые скосы — то, что делает прямоугольник конвертом */}
            <div
              className="absolute inset-0"
              style={{
                background:
                  "linear-gradient(90deg, rgb(0 0 0 / 0.06), transparent 22%, transparent 78%, rgb(0 0 0 / 0.06))",
              }}
            />
            {/* Тиснение: светлая фаска сверху, тень снизу, тонкий кант */}
            <div
              className="absolute inset-0 rounded-[14px]"
              style={{
                boxShadow:
                  "inset 0 1px 0 rgb(255 255 255 / 0.55), inset 0 -1px 0 rgb(0 0 0 / 0.08), inset 0 0 0 1px var(--color-paper-edge)",
              }}
            />
            <div
              className="absolute inset-[7px] rounded-[9px]"
              style={{
                boxShadow:
                  "inset 0 0 0 1px rgb(0 0 0 / 0.045), 0 0 0 1px rgb(255 255 255 / 0.3)",
              }}
            />
          </div>

          {/* Клапан. Откидывается назад — вот ради этого вся перспектива. */}
          <motion.div
            className="absolute inset-x-0 top-0 origin-top"
            style={{
              height: "48%",
              transformStyle: "preserve-3d",
              zIndex: opening ? 0 : 3,
            }}
            initial={false}
            animate={{ rotateX: opening ? 172 : 0 }}
            transition={{ duration: 0.95, delay: 0.22, ease: [0.65, 0, 0.35, 1] }}
          >
            {/* Лицевая сторона клапана */}
            <div
              className="absolute inset-0"
              style={{
                background:
                  "linear-gradient(185deg, var(--color-paper) 0%, var(--color-paper-deep) 100%)",
                clipPath: "polygon(0 0, 100% 0, 50% 100%)",
                borderRadius: "14px 14px 0 0",
                filter: "drop-shadow(0 6px 10px rgb(0 0 0 / 0.18))",
                backfaceVisibility: "hidden",
              }}
            />
            {/* Изнанка — она темнее, и именно она видна у откинутого клапана */}
            <div
              className="absolute inset-0"
              style={{
                background:
                  "linear-gradient(185deg, #cdbb9e 0%, #bda98a 100%)",
                clipPath: "polygon(0 0, 100% 0, 50% 100%)",
                borderRadius: "14px 14px 0 0",
                transform: "rotateX(180deg)",
                backfaceVisibility: "hidden",
              }}
            />
          </motion.div>

          {/* Сургучная печать. Разламывается на две половины и падает. */}
          <div
            className="pointer-events-none absolute left-1/2 -translate-x-1/2"
            style={{ top: "40%", zIndex: 4 }}
          >
            {/*
              Целая печать и половинки — разные элементы. Пока конверт
              запечатан, печать одна: две половины, как бы точно их ни
              подгонял, оставляют по стыку тонкий шов.
            */}
            {!opening ? (
              <div className="absolute left-1/2 top-1/2 flex h-14 w-14 -translate-x-1/2 -translate-y-1/2 items-center justify-center">
                <WaxSeal size={56} />
              </div>
            ) : (
              [-1, 1].map((side) => (
                <motion.div
                  key={side}
                  className="absolute left-1/2 top-1/2 h-14 w-14 -translate-x-1/2 -translate-y-1/2"
                  style={{
                    clipPath:
                      side === -1 ? "inset(0 50% 0 0)" : "inset(0 0 0 50%)",
                  }}
                  initial={{ x: 0, y: 0, rotate: 0, opacity: 1 }}
                  animate={{
                    x: side * 46,
                    y: 74,
                    rotate: side * 38,
                    opacity: 0,
                  }}
                  transition={{ duration: 0.8, ease: [0.36, 0, 0.66, -0.56] }}
                >
                  <div className="flex h-14 w-14 items-center justify-center">
                    <WaxSeal size={56} />
                  </div>
                </motion.div>
              ))
            )}
          </div>

          {/* Надпись на конверте */}
          <motion.div
            className="absolute inset-x-0 bottom-7 text-center"
            style={{ zIndex: 3 }}
            initial={false}
            animate={{ opacity: opening ? 0 : 1 }}
            transition={{ duration: 0.4 }}
          >
            <div className="font-hand text-2xl text-ink/80">Илизе</div>
            <div className="mt-1 font-sans text-[0.68rem] uppercase tracking-[0.22em] text-ink-faint">
              письмо {n} · {dateLabel}
            </div>
          </motion.div>
        </div>
      </motion.div>

      {/* Подсказка — только пока конверт запечатан */}
      <motion.div
        className="mt-9 text-center font-sans text-[0.72rem] uppercase tracking-[0.28em] text-sky-ink-soft"
        initial={false}
        animate={{ opacity: state === "sealed" ? 1 : 0 }}
        transition={{ duration: 0.4 }}
      >
        <span className="inline-block transition-transform duration-500 group-hover:scale-105">
          нажми, чтобы открыть
        </span>
      </motion.div>
    </motion.button>
  );
}
