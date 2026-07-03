import { CSSProperties, ReactNode } from "react";

// Появление секций — на ЧИСТОМ CSS (не framer whileInView).
// Почему: framer прятал контент (opacity:0) до гидрации ~275КБ JS → на LTE/мобиле
// белый «висящий» экран. CSS-анимация стартует на парсе элемента: контент виден
// за ~0.7с из SSG-HTML, без ожидания JS и без IntersectionObserver (который к тому
// же глючил на iOS). Прогрессивное улучшение: нет JS — контент просто виден.
export function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: ReactNode;
  delay?: number;
  y?: number; // сохранено для совместимости вызовов, не используется
  className?: string;
}) {
  const style: CSSProperties | undefined = delay ? { animationDelay: `${delay}s` } : undefined;
  return (
    <div className={`reveal-in ${className}`} style={style}>
      {children}
    </div>
  );
}
