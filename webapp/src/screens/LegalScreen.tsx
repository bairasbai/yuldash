// ================================================================
//  Общий экран юридического документа (Политика / Соглашение).
//  Публичный. Тело — на русском (юридически значимый язык); заголовок
//  экрана и служебные подписи двуязычны. Полный актуальный текст —
//  на yulbash.ru/<slug>. Контент — src/legal.ts (зеркало web/).
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { API_BASE } from "../api/client";
import { SubHeader } from "./ConsentsScreen";
import type { LegalDoc } from "../legal";

export default function LegalScreen({ doc }: { doc: LegalDoc }) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const webUrl = `${API_BASE}/${doc.slug}`;

  return (
    <>
      <SubHeader
        title={appText(doc.titleRu, doc.titleBa)}
        subtitle={appText(`Обновлено: ${doc.updated}`, `Яңыртылды: ${doc.updated}`)}
        onBack={() => navigate(-1)}
      />

      <article className="legal">
        {/* Служебная плашка: язык документа + ссылка на полный текст */}
        <p className="legal__note">
          {appText(
            "Юридически значимый текст — на русском языке. Полная актуальная версия всегда доступна на сайте.",
            "Юридик яҡтан әһәмиәтле текст — рус телендә. Тулы актуаль версия һәр ваҡыт сайтта."
          )}{" "}
          <a href={webUrl} target="_blank" rel="noreferrer" className="legal__link">
            {webUrl.replace(/^https?:\/\//, "")}
          </a>
        </p>

        {doc.intro.map((p, i) => (
          <p key={`intro-${i}`} className="legal__p legal__p--intro">
            {p}
          </p>
        ))}

        {doc.sections.map((s, i) => (
          <section key={`sec-${i}`} className="legal__section">
            <h2 className="legal__h">{s.h}</h2>
            {s.p.map((p, j) => (
              <p key={`p-${i}-${j}`} className="legal__p">
                {p}
              </p>
            ))}
          </section>
        ))}

        <p className="receipt__foot">
          {appText(
            "Остались вопросы по документу? Напиши нам в поддержку — ответим по-человечески.",
            "Документ буйынса һорауҙар ҡалдымы? Ярҙамға яҙ — кешеләрсә яуап бирәбеҙ."
          )}
        </p>
      </article>
    </>
  );
}
