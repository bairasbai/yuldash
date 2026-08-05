import { notFound, redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import LetterSheet from "@/components/LetterSheet";
import ReplyBox from "@/components/ReplyBox";
import RepliesList from "@/components/RepliesList";
import { readSession } from "@/lib/session";
import { readLetter } from "@/lib/letters";
import { repliesFor } from "@/lib/store";
import { todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function LetterPage({
  params,
}: {
  params: Promise<{ n: string }>;
}) {
  if (!(await readSession())) redirect("/gate");

  const { n } = await params;
  const number = Number(n);
  if (!Number.isInteger(number)) notFound();

  const today = todayInHerCity();
  const letter = readLetter(number, today);

  // Не наступило или ещё не написано — снаружи это выглядит одинаково,
  // и правильно: подсказки о будущих письмах наружу не уходят.
  if (!letter) notFound();

  const replies = await repliesFor(letter.n);

  return (
    <PageShell
      title={`Письмо ${letter.n}`}
      subtitle={letter.dateLabel}
      back="/letters"
    >
      <LetterSheet
        letter={letter}
        footer={
          <>
            <RepliesList replies={replies} />
            <ReplyBox n={letter.n} />
          </>
        }
      />
    </PageShell>
  );
}
