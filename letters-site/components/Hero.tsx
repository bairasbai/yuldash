import Sky from "./Sky";

/**
 * Шапка с небом.
 *
 * Раньше небо занимало весь экран и всё лежало на тёмном. Теперь оно
 * живёт только сверху и внизу растворяется в кремовом — так же, как
 * в макете, где под фотографией начинается светлая страница.
 *
 * Небо по-прежнему считается из даты: в начале ожидания оно ночное,
 * к двадцать первому августа рассветает.
 */
export default function Hero({
  progress,
  height = "44vh",
  children,
}: {
  progress: number;
  height?: string;
  children: React.ReactNode;
}) {
  /*
    Шапка не бывает совсем ночной. Если брать прогресс как есть, весь
    первый месяц сверху будет чёрное небо — а нужно тёплое утро.
    Поэтому шкалу поджимаем: в начале ожидания это уже заря,
    к двадцать первому августа — полный рассвет.
  */
  const heroProgress = 0.88 + Math.min(1, Math.max(0, progress)) * 0.12;

  return (
    <header className="relative w-full overflow-hidden" style={{ height }}>
      <Sky progress={heroProgress} inset />

      {/* Растворение неба в фоне страницы */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 bottom-0 h-28"
        style={{
          background:
            "linear-gradient(to bottom, transparent 0%, color-mix(in srgb, var(--color-cream) 55%, transparent) 55%, var(--color-cream) 100%)",
        }}
      />

      <div className="relative z-10 mx-auto flex h-full w-full max-w-[38rem] flex-col justify-end px-5 pb-12">
        {children}
      </div>
    </header>
  );
}
