// ================================================================
//  Помощь / FAQ → /help (публично).
//  Бэк-эндпоинта FAQ нет — двуязычный статичный список частых вопросов
//  с поиском (аккордеон). Крупная кнопка «Не нашёл ответ?» → /support
//  (или /login для гостя), Telegram — доп.вариантом.
// ================================================================
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";
import { IconChevron, IconTelegram, IconSearch } from "../components/Icons";

interface Faq {
  q: [string, string];
  a: [string, string];
}

// DRAFT — башкирские строки требуют проверки носителем (см. BASHKIR_DRAFT.md).
const FAQS: Faq[] = [
  {
    q: ["Что такое Юлдаш?", "Юлдаш нимә ул?"],
    a: [
      "Юлдаш — попутки «между своими» по Башкортостану. Находишь поездку или зовёшь водителя, договариваешься и едешь с проверенными людьми.",
      "Юлдаш — Башҡортостан буйлап «үҙебеҙ араһында» юлдаш. Сәфәр табаһың йәки водитель саҡыраһың, килешеп, тикшерелгән кешеләр менән бараһың.",
    ],
  },
  {
    q: ["Как найти поездку?", "Сәфәрҙе нисек табырға?"],
    a: [
      "Открой карту или ленту поездок, выбери попутку по маршруту и времени и оставь заявку. Водитель подтвердит — и вы спишетесь в чате.",
      "Картаны йәки сәфәр таҫмаһын ас, маршрут һәм ваҡыт буйынса юлдаш һайла һәм заявка ҡалдыр. Водитель раҫлай — чатта яҙышаһығыҙ.",
    ],
  },
  {
    q: ["Как стать водителем?", "Нисек водитель булырға?"],
    a: [
      "В профиле открой «Я водитель», пройди простую проверку и опубликуй поездку. Проверка нужна для доверия — это и есть суть «между своими».",
      "Профилдә «Мин водитель»ды ас, ябай тикшереүҙе үт һәм сәфәр баҫтыр. Тикшереү ышаныс өсөн кәрәк — «үҙебеҙ араһында» шуның асылы.",
    ],
  },
  {
    q: ["Как оплатить поездку?", "Сәфәрҙе нисек түләргә?"],
    a: [
      "Оплата — наличными или переводом по СБП напрямую водителю, по договорённости. Юлдаш не берёт комиссию с попуток.",
      "Түләү — аҡса менән йәки СБП аша тура водителгә, килешеү буйынса. Юлдаш юлдаштарҙан комиссия алмай.",
    ],
  },
  {
    q: ["Это безопасно?", "Был именме?"],
    a: [
      "Мы за доверие: бейдж «проверен», рейтинги, история поездок, кнопка SOS и «поделиться поездкой с близким». Личные данные показываем по минимуму.",
      "Беҙ ышаныс яҡлы: «тикшерелгән» билдәһе, рейтингтар, сәфәр тарихы, SOS төймәһе һәм «сәфәрҙе яҡын кеше менән уртаҡлашыу». Шәхси мәғлүмәт минимум.",
    ],
  },
  {
    q: ["Как отменить или изменить поездку?", "Сәфәрҙе нисек кире алырға йәки үҙгәртергә?"],
    a: [
      "Зайди в поездку в разделе «Мои поездки» и отмени её. Предупреди попутчика в чате заранее — так по-соседски.",
      "«Сәфәрҙәрем» бүлегендә сәфәргә ин һәм уны кире ал. Юлдашыңды чатта алдан иҫкәрт — шулай күршеләрсә.",
    ],
  },
  {
    q: ["Приложение на двух языках?", "Ҡушымта ике телдәме?"],
    a: [
      "Да, Юлдаш полностью на русском и башкирском. Язык переключается в профиле и настройках в любой момент.",
      "Эйе, Юлдаш тулыһынса рус һәм башҡорт телендә. Тел профилдә һәм көйләүҙәрҙә теләһә ҡасан алмашына.",
    ],
  },
];

export default function HelpScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<number | null>(null);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return FAQS.map((f, i) => ({ f, i }));
    return FAQS.map((f, i) => ({ f, i })).filter(({ f }) => {
      const hay = (f.q[0] + f.q[1] + f.a[0] + f.a[1]).toLowerCase();
      return hay.includes(q);
    });
  }, [query]);

  return (
    <>
      <SubHeader
        title={appText("Помощь", "Ярҙам")}
        subtitle={appText("Ответы на частые вопросы", "Йыш бирелгән һорауҙарға яуап")}
        onBack={() => navigate(-1)}
      />

      <label className="field help-search">
        <input
          className="field__input"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={appText("Поиск по вопросам…", "Һорауҙар буйынса эҙләү…")}
          aria-label={appText("Поиск по вопросам", "Һорауҙар буйынса эҙләү")}
        />
      </label>

      {shown.length === 0 ? (
        <div className="state">
          <div className="state__icon"><IconSearch size={34} /></div>
          <h2>{appText("Ничего не нашли", "Бер нәмә лә табылманы")}</h2>
          <p>{appText("Попробуй другие слова или напиши в поддержку.", "Башҡа һүҙҙәр менән ҡара йәки ярҙамға яҙ.")}</p>
        </div>
      ) : (
        <div className="faq-list">
          {shown.map(({ f, i }) => {
            const isOpen = open === i;
            return (
              <div key={i} className={"faq-item" + (isOpen ? " faq-item--open" : "")}>
                <button
                  type="button"
                  className="faq-q"
                  aria-expanded={isOpen}
                  onClick={() => setOpen(isOpen ? null : i)}
                >
                  <span>{ru ? f.q[0] : f.q[1]}</span>
                  <span className="faq-q__chev" aria-hidden>
                    <IconChevron size={20} />
                  </span>
                </button>
                {isOpen && <div className="faq-a">{ru ? f.a[0] : f.a[1]}</div>}
              </div>
            );
          })}
        </div>
      )}

      <div className="help-cta">
        <p className="help-cta__lead">
          {appText("Не нашёл ответ?", "Яуап тапманыңмы?")}
        </p>
        <button
          type="button"
          className="btn-primary btn-lg"
          onClick={() => navigate(isAuthed ? "/support" : "/login", { state: { from: "/support" } })}
        >
          {appText("Написать в поддержку", "Ярҙамға яҙырға")}
        </button>
        <a className="help-tg" href="https://t.me/yulbash" target="_blank" rel="noreferrer">
          <IconTelegram size={20} />
          {appText("Мы в Telegram", "Беҙ Telegram-да")}
        </a>
      </div>
    </>
  );
}
