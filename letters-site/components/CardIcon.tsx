/**
 * Цветная плашка с иконкой слева от заголовка карточки.
 * Она не украшение: по цвету видно, к какой части сайта относится
 * блок, даже когда листаешь быстро.
 */
export default function CardIcon({
  tone = "warm",
  children,
}: {
  tone?: "warm" | "coral" | "deep" | "calm";
  children: React.ReactNode;
}) {
  const tones = {
    warm: { bg: "#fdf1e4", fg: "var(--color-accent)" },
    coral: { bg: "#fbe9e5", fg: "var(--color-coral)" },
    deep: { bg: "#e9edf3", fg: "var(--color-deep)" },
    calm: { bg: "#eef1ea", fg: "#7d8b6f" },
  }[tone];

  return (
    <span
      aria-hidden
      className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[14px]"
      style={{ background: tones.bg, color: tones.fg }}
    >
      {children}
    </span>
  );
}
