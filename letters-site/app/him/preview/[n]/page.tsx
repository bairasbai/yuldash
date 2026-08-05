import { notFound, redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import LetterSheet from "@/components/LetterSheet";
import { readSession } from "@/lib/session";
import { previewLetter } from "@/lib/letters";

export const dynamic = "force-dynamic";

/** Как письмо будет выглядеть у неё. Доступно только Байрасу. */
export default async function PreviewPage({
  params,
}: {
  params: Promise<{ n: string }>;
}) {
  const who = await readSession();
  if (!who) redirect("/gate");
  if (who !== "him") redirect("/");

  const { n } = await params;
  const letter = previewLetter(Number(n));
  if (!letter) notFound();

  return (
    <PageShell
      title={`Письмо ${letter.n}`}
      subtitle={`предпросмотр · ${letter.dateLabel}`}
      back="/him"
    >
      <LetterSheet letter={letter} />
    </PageShell>
  );
}
