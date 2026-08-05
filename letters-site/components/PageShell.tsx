import Link from "next/link";
import Hero from "./Hero";
import TabBar from "./TabBar";
import { ArrowLeft } from "./Icons";
import { dawnProgress } from "@/lib/time";

/**
 * Рама внутренних страниц: невысокая шапка с небом, дальше светлая
 * страница с карточками и нижняя панель.
 *
 * Небо считает сама: прогресс зависит только от даты, страницам
 * его знать незачем.
 */
export default function PageShell({
  title,
  subtitle,
  back = "/",
  children,
}: {
  title: string;
  subtitle?: string;
  back?: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <Hero progress={dawnProgress()} height="28vh">
        <Link
          href={back}
          className="group absolute left-5 top-[max(1.25rem,env(safe-area-inset-top))] inline-flex items-center gap-2 font-sans text-[0.64rem] uppercase tracking-[0.2em] text-white/75 transition-colors hover:text-white"
        >
          <ArrowLeft
            size={14}
            className="transition-transform duration-500 ease-[var(--ease-soft)] group-hover:-translate-x-1"
          />
          назад
        </Link>

        <h1
          className="rise font-serif text-[clamp(1.9rem,7vw,2.6rem)] leading-tight text-white"
          style={{ textShadow: "0 2px 24px rgb(0 0 0 / 0.35)" }}
        >
          {title}
        </h1>
        {subtitle && (
          <p
            className="rise mt-2 font-sans text-[0.68rem] uppercase tracking-[0.2em] text-white/75"
            style={{ textShadow: "0 1px 10px rgb(0 0 0 / 0.4)" }}
          >
            {subtitle}
          </p>
        )}
      </Hero>

      {/* Отступ сверху: без него карточка упирается прямо в небо */}
      <main className="relative mx-auto w-full max-w-[38rem] px-5 pb-32 pt-4">
        {children}
      </main>

      <TabBar />
    </>
  );
}
