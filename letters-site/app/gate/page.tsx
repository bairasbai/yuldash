import { redirect } from "next/navigation";
import GateForm from "@/components/GateForm";
import { readSession } from "@/lib/session";
import { GATE_HINT } from "@/lib/config";

export const dynamic = "force-dynamic";

/**
 * Дверь. Ни имён, ни объяснений — случайный человек не поймёт,
 * куда попал, а она поймёт с первой строчки.
 */
export default async function Gate() {
  if (await readSession()) redirect("/");

  return (
    <>

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
    </>
  );
}
