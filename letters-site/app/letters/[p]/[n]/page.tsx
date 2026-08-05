import { notFound, redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import LetterSheet from "@/components/LetterSheet";
import ReplyBox from "@/components/ReplyBox";
import RepliesList from "@/components/RepliesList";
import { readSession } from "@/lib/session";
import { readLetter } from "@/lib/letters";
import { partingById } from "@/lib/partings";
import { repliesFor } from "@/lib/store";
import { todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function LetterPage({
  params,
}: {
  params: Promise<{ p: string; n: string }>;
}) {
  if (!(await readSession())) redirect("/gate");

  const { p, n } = await params;
  const partingId = Number(p);
  const number = Number(n);
  if (!Number.isInteger(partingId) || !Number.isInteger(number)) notFound();

  const parting = await partingById(partingId);
  if (!parting) notFound();

  const today = todayInHerCity();
  const letter = await readLetter(parting, number, today);

  // Не наступило или ещё не написано — снаружи это выглядит одинаково,
  // и правильно: подсказки о будущих письмах наружу не уходят.
  if (!letter) notFound();

  const replies = await repliesFor(parting.id, letter.n);

  return (
    <PageShell
      title={`Письмо ${letter.n}`}
      subtitle={letter.dateLabel}
      back={`/letters?p=${parting.id}`}
    >
      <LetterSheet
        letter={letter}
        footer={
          <>
            <RepliesList replies={replies} />
            <ReplyBox n={letter.n} partingId={parting.id} />
          </>
        }
      />
    </PageShell>
  );
}
