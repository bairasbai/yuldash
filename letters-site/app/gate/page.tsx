import { redirect } from "next/navigation";
import Sky from "@/components/Sky";
import GateForm from "@/components/GateForm";
import { readSession } from "@/lib/session";
import { GATE_HINT } from "@/lib/config";
import { dawnProgress } from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Дверь. Ни имён, ни объяснений — случайный человек не поймёт,
 * куда попал, а она поймёт с первой строчки.
 *
 * Единственный тёмный экран на всём сайте: снаружи ночь, внутри утро.
 */
export default async function Gate() {
  if (await readSession()) redirect("/");

  return (
    <div
      className="relative min-h-dvh"
      // Внутри двери текст светлый — перебиваем токены только здесь,
      // чтобы не заводить вторую тему на весь сайт
      style={
        {
          "--color-sky-ink": "#f4f6fc",
          "--color-sky-ink-soft": "#b7c1d8",
          "--panel-border": "rgb(255 255 255 / 0.18)",
        } as React.CSSProperties
      }
    >
      <Sky progress={dawnProgress()} inset />

      <main className="relative flex min-h-dvh flex-col items-center justify-center px-6">
        <div className="mb-12 flex flex-col items-center">
          <span
            className="star star-bright !relative"
            style={
              {
                "--star-size": 5,
                "--star-base": 1,
                "--star-dur": 5,
                "--star-delay": 0,
              } as React.CSSProperties
            }
          />
        </div>

        <GateForm hint={GATE_HINT} />
      </main>
    </div>
  );
}
