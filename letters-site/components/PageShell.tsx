import Link from "next/link";
import Sky from "./Sky";

/**
 * Общая рама внутренних страниц: небо, шапка с возвратом,
 * одинаковые отступы под чёлку и нижнюю панель телефона.
 */
export default function PageShell({
  progress,
  title,
  subtitle,
  back = "/",
  children,
}: {
  progress: number;
  title: string;
  subtitle?: string;
  back?: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <Sky progress={progress} />

      <main className="relative mx-auto flex min-h-dvh w-full max-w-[38rem] flex-col px-5 pb-[max(3rem,env(safe-area-inset-bottom))] pt-[max(2rem,env(safe-area-inset-top))]">
        <Link
          href={back}
          className="mb-10 inline-flex w-fit items-center gap-2 font-sans text-[0.66rem] uppercase tracking-[0.22em] text-sky-ink-soft transition-colors hover:text-sky-ink"
        >
          <span aria-hidden>←</span> назад
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
