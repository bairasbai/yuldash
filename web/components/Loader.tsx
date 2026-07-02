"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { KuraiBloom } from "./Ornament";

// Модульный флаг — переживает двойной монтаж React StrictMode (dev).
let started = false;

// Брендовый загрузчик. Видим С ПЕРВОГО КАДРА (рендерится в статичном HTML) —
// поэтому контент сайта не «мигает» под ним. JS прячет шторку после загрузки;
// если сессию уже видели или включён reduced-motion — убираем сразу.
export function Loader() {
  const reduce = useReducedMotion();
  const [show, setShow] = useState(true);

  useEffect(() => {
    if (started) return;
    started = true;
    let seen = false;
    try {
      seen = !!sessionStorage.getItem("yuldash_loaded");
    } catch {}
    if (reduce || seen) {
      setTimeout(() => setShow(false), 0);
      return;
    }
    try {
      sessionStorage.setItem("yuldash_loaded", "1");
    } catch {}
    // намеренно НЕ чистим таймер в cleanup — иначе StrictMode-cleanup гасит его и шторка зависает
    setTimeout(() => setShow(false), 1200);
  }, [reduce]);

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-night"
          initial={{ opacity: 1 }}
          exit={{ opacity: 0, transition: { duration: 0.6, ease: "easeInOut" } }}
        >
          <motion.div
            className="flex flex-col items-center gap-5 text-gold-light"
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ scale: 1.15, opacity: 0 }}
            transition={{ duration: 0.7, ease: [0.21, 0.47, 0.32, 0.98] }}
          >
            <motion.div
              animate={{ rotate: [0, 8, -8, 0] }}
              transition={{ duration: 1.4, ease: "easeInOut", repeat: Infinity }}
            >
              <KuraiBloom size={72} />
            </motion.div>
            <motion.span
              className="font-display text-2xl font-extrabold text-white"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.25 }}
            >
              Юлдаш
            </motion.span>
            {/* прогресс-линия */}
            <motion.span
              className="h-0.5 w-24 origin-left rounded-full bg-green-bright"
              initial={{ scaleX: 0 }}
              animate={{ scaleX: 1 }}
              transition={{ duration: 1.1, ease: "easeInOut" }}
            />
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
