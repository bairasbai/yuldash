"use client";

import { LazyMotion } from "framer-motion";
import type { ReactNode } from "react";

// Диета бандла: с обычным `motion.*` framer тянет ВЕСЬ набор фич (~34КБ min+gz)
// синхронно в критический путь. Мы используем лёгкие `m.*`-компоненты + LazyMotion,
// а сами фичи (`domMax` — анимации + layout + жесты, нужен для layoutId-слайдов)
// подгружаем ЛЕНИВО отдельным чанком после гидрации. Контент виден сразу (SSG,
// без opacity:0), анимации доезжают чуть позже — на загрузку это не влияет.
// strict → случайный `motion.*` кинет ошибку на сборке, не проскочит.
const loadFeatures = () => import("framer-motion").then((mod) => mod.domMax);

export function MotionProvider({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={loadFeatures} strict>
      {children}
    </LazyMotion>
  );
}
