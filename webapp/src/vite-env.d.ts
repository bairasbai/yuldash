/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  /** Имя Telegram-бота для входа (без @). Пусто → вход через Telegram показывает заглушку. */
  readonly VITE_TELEGRAM_BOT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// beforeinstallprompt (Android/Chrome) — не в стандартных lib.dom
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[];
  readonly userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
  prompt(): Promise<void>;
}
