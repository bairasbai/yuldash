"use client";

import { useIsMobile } from "./useIsMobile";

// Живой «аврора»-фон: мягко дышащие зелёно-золотые пятна света.
// Дорогой матовый эффект усиливается зернистостью (.grain) поверх.
//
// Перф/перегрев: 3 огромных пятна (700px) с blur(140px) на телефоне душили GPU
// (лаги при скролле, нагрев, разряд). Решение:
//  • Десктоп — пятна как CSS-слои (.aurora-blob), дрейф на компоновщике
//    (@media min-width:768 в globals.css), без JS на кадр.
//  • Телефон — вообще НЕ рисуем blur-слои: тот же вид одним статичным
//    radial-градиентом. Ноль работы на кадр = плавно и холодно.
export function Aurora() {
  const isMobile = useIsMobile();

  return (
    <div className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      {/* базовый тёмный градиент */}
      <div className="absolute inset-0 bg-[radial-gradient(120%_120%_at_50%_-10%,#10241a_0%,#0a1410_55%,#070f0b_100%)]" />

      {isMobile ? (
        // Телефон: статичная «аврора» одним слоем градиентов (дёшево, без blur).
        <div className="absolute inset-0 bg-[radial-gradient(55%_38%_at_18%_6%,rgba(127,227,171,0.16),transparent_60%),radial-gradient(48%_34%_at_88%_20%,rgba(232,195,107,0.12),transparent_62%),radial-gradient(70%_50%_at_42%_104%,rgba(18,74,50,0.30),transparent_66%)]" />
      ) : (
        <>
          <div className="aurora-blob aurora-blob-1 absolute -top-40 left-[12%] h-[44rem] w-[44rem] rounded-full bg-green-bright/25 blur-[140px]" />
          <div className="aurora-blob aurora-blob-2 absolute top-[18%] right-[8%] h-[38rem] w-[38rem] rounded-full bg-gold/20 blur-[150px]" />
          <div className="aurora-blob aurora-blob-3 absolute bottom-[-10%] left-[35%] h-[40rem] w-[40rem] rounded-full bg-green-deep/30 blur-[150px]" />
        </>
      )}

      {/* тонкая сетка-виньетка для глубины */}
      <div className="absolute inset-0 bg-[radial-gradient(100%_60%_at_50%_0%,transparent_60%,rgba(0,0,0,0.55)_100%)]" />
      <div className="grain absolute inset-0" />
    </div>
  );
}
