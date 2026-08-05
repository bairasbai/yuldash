import Link from "next/link";
import { ArrowLeft } from "./Icons";

/**
 * Общая рама внутренних страниц: шапка с возвратом и одинаковые
 * отступы под чёлку и нижнюю панель телефона. Небо не здесь —
 * оно одно на всё приложение, в layout.
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
      <main className="relative mx-auto flex min-h-dvh w-full max-w-[38rem] flex-col px-5 pb-[max(3rem,env(safe-area-inset-bottom))] pt-[max(2rem,env(safe-area-inset-top))]">
        <Link
          href={back}
          className="group mb-10 inline-flex w-fit items-center gap-2 font-sans text-[0.66rem] uppercase tracking-[0.22em] text-sky-ink-soft transition-colors hover:text-sky-ink"
        >
          <ArrowLeft
            size={15}
            className="transition-transform duration-500 ease-[var(--ease-soft)] group-hover:-translate-x-1"
          />
          назад
        </Link>

        <header className="rise mb-12 text-center">
          <h1 className="font-serif text-[clamp(2rem,7vw,2.9rem)] font-light leading-tight">
            {title}
          </h1>
          {subtitle && (
            <p className="mt-3 font-sans text-[0.68rem] uppercase tracking-[0.24em] text-sky-ink-soft">
              {subtitle}
            </p>
          )}
          <div className="hairline mx-auto mt-7 w-24" />
        </header>

        {children}
      </main>
    </>
  );
}
