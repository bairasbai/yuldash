import type { Config } from "tailwindcss";

// Бренд Юлдаш — цвета синхронизированы с Android (Theme.kt)
const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#0B1F14", // тёмный текст / база
        night: "#0A1410", // самый тёмный фон героя
        forest: "#0F1613", // тёмный фон (dark surface)
        green: {
          deep: "#0B6B3A",
          DEFAULT: "#0E8247",
          bright: "#2FB36E",
          glow: "#7FE3AB",
        },
        gold: {
          DEFAULT: "#D89B12",
          light: "#E8C36B",
          soft: "#FFE3A1",
        },
        cream: "#FAFAF6",
      },
      fontFamily: {
        sans: ["var(--font-sans)", "system-ui", "sans-serif"],
        display: ["var(--font-display)", "var(--font-sans)", "sans-serif"],
      },
      boxShadow: {
        glow: "0 0 60px -12px rgba(47,179,110,0.45)",
        gold: "0 0 50px -14px rgba(216,155,18,0.5)",
        card: "0 24px 60px -20px rgba(0,0,0,0.5)",
      },
      borderRadius: {
        canon: "22px",
      },
      keyframes: {
        shimmer: {
          "0%": { backgroundPosition: "0% 50%" },
          "100%": { backgroundPosition: "200% 50%" },
        },
      },
      animation: {
        shimmer: "shimmer var(--loop-shine) linear infinite",
      },
      // Длительности переходов — по именам шкалы, чтобы в разметке не было чисел.
      // Значения живут в одном месте (app/globals.css) и совпадают с приложением.
      transitionDuration: {
        quick: "var(--motion-quick)",
        normal: "var(--motion-normal)",
        slow: "var(--motion-slow)",
        entry: "var(--motion-entry)",
        count: "var(--motion-count)",
        scene: "var(--motion-scene)",
        tick: "var(--motion-tick)",
        pulse: "var(--motion-pulse)",
        cinema: "var(--motion-cinema)",
      },
      transitionDelay: {
        quick: "var(--motion-quick)",
        normal: "var(--motion-normal)",
        slow: "var(--motion-slow)",
        entry: "var(--motion-entry)",
      },
    },
  },
  plugins: [],
};

export default config;
