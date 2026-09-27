import Link from "next/link";
import Image from "next/image";

export default function NotFound() {
  return (
    <main id="top" tabIndex={-1} className="relative flex min-h-[100dvh] flex-col items-center justify-center px-6 text-center">
      {/* фон-свечение */}
      <div className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(120%_120%_at_50%_0%,#10241a_0%,#0a1410_60%,#070f0b_100%)]" />
      <div className="pointer-events-none absolute left-1/2 top-1/3 -z-10 h-72 w-72 -translate-x-1/2 rounded-full bg-green-bright/20 blur-[120px]" />

      <Image src="/logo.png" alt="Юлдаш" width={56} height={56} className="mb-6 rounded-xl" />

      <p className="font-display text-7xl font-extrabold text-gradient">404</p>
      <h1 className="mt-4 font-display text-2xl font-bold text-white">Страница не найдена</h1>
      <p className="mt-2 max-w-sm text-white/55">
        Похоже, такой страницы нет. Вернись на главную.
        <br />
        <span className="text-white/40">Бындай бит юҡ. Баш битькә ҡайт.</span>
      </p>

      <Link
        href="/"
        className="mt-8 rounded-canon bg-green-bright px-7 py-3.5 font-bold text-night shadow-glow transition-transform hover:scale-105 active:scale-95"
      >
        На главную · Баш битькә
      </Link>
    </main>
  );
}
