import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { flags, type ConsentKind, type Consents } from "../flags";
import { fetchMyConsents, grantConsent } from "../api/trust";
import { IconChevron } from "../components/Icons";
import { formatWhen } from "../utils/format";
import { useAuth } from "../auth/AuthProvider";
import { getSessionGeneration } from "../api/client";

/**
 * Согласия по 152-ФЗ (оферта / политика конфиденциальности / геолокация).
 *
 * Раньше отметка жила ТОЛЬКО на устройстве, в localStorage, — и в коде честно стояло
 * «у бэкенда пока нет эндпоинта согласий». Он есть с самого начала, и приложение
 * пишет именно туда. Разница не косметическая: согласие по 152-ФЗ — это доказательство,
 * а доказательство, которое стирается вместе с кешем браузера и не переносится на
 * второй телефон, доказательством не является (сверка с Android, 2026-08-30).
 *
 * Теперь так: отметил → уходит на сервер с отметкой времени. Время ПЕРВОГО согласия
 * сервер не перезаписывает. Локальный флаг оставляем как зеркало для офлайна — по нему
 * экран рисуется сразу, не дожидаясь сети.
 *
 * Снять согласие галочкой нельзя: у отзыва согласия свой порядок (обращение в
 * поддержку и удаление аккаунта), и делать вид, что это тумблер, — обманывать.
 */
export default function ConsentsScreen() {
  useAuth(); // Публичный экран должен обновляться вместе с аккаунтом.
  const generation = getSessionGeneration();
  return <ConsentsForm key={generation} generation={generation} />;
}

function ConsentsForm({ generation }: { generation: string }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const [state, setState] = useState<Consents>(() => flags.consents(generation));
  /** Когда согласие зафиксировано на сервере. Пусто = сервер о нём ещё не знает. */
  const [granted, setGranted] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<ConsentKind | null>(null);
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    if (generation !== getSessionGeneration()) return;
    fetchMyConsents(signal)
      .then((rows) => {
        if (signal?.aborted || generation !== getSessionGeneration()) return;
        const map: Record<string, string> = {};
        rows.forEach((c) => {
          map[String(c.kind)] = c.granted_at;
        });
        setGranted(map);
        // Сервер — источник правды: он помнит согласие и после переустановки браузера.
        let next = flags.consents(generation);
        (Object.keys(map) as ConsentKind[]).forEach((k) => {
          if (!next[k]) next = flags.setConsent(k, true, generation);
        });
        setState(next);
      })
      .catch(() => {
        /* нет сети / нет ручки — остаёмся на локальной отметке, экран работает */
      });
  }, [generation]);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function toggle(kind: ConsentKind) {
    if (generation !== getSessionGeneration()) return;
    if (busy) return;
    // Уже зафиксировано на сервере — снимать нечего: см. пояснение выше.
    if (granted[kind]) return;
    setBusy(kind);
    setNote("");
    // Согласие считается записанным только после подтверждения сервера.
    try {
      const c = await grantConsent(kind as "offer" | "privacy" | "geo" | "age18");
      if (generation !== getSessionGeneration()) return;
      setState(flags.setConsent(kind, true, generation));
      setGranted((prev) => ({ ...prev, [kind]: c.granted_at }));
    } catch {
      if (generation !== getSessionGeneration()) return;
      setNote(
        appText(
          "Согласие пока не записалось на сервере. Попробуй ещё раз.",
          "Ризалыҡ серверҙа әле яҙылманы. Ҡабатлап ҡара."
        )
      );
    } finally {
      if (generation === getSessionGeneration()) setBusy(null);
    }
  }

  const items: {
    kind: ConsentKind;
    title: string;
    body: string;
  }[] = [
    {
      kind: "offer",
      title: appText("Оферта (условия сервиса)", "Оферта (хеҙмәт шарттары)"),
      body: appText(
        "Правила пользования Юлдашем: как устроены поездки, оплата и ответственность.",
        "Юлдашты ҡулланыу ҡағиҙәләре: сәфәр, түләү һәм яуаплылыҡ нисек ойошторолған."
      ),
    },
    {
      kind: "privacy",
      title: appText("Политика конфиденциальности", "Ҡупшылыҡ сәйәсәте"),
      body: appText(
        "Какие данные мы храним, зачем и как защищаем. Данные — по минимуму.",
        "Ниндәй мәғлүмәт һаҡлайбыҙ, ни өсөн һәм нисек яҡлайбыҙ. Мәғлүмәт — минимум."
      ),
    },
    {
      kind: "geo",
      title: appText("Геолокация", "Геолокация"),
      body: appText(
        "Разрешение на геопозицию — только для поездки и только когда нужно.",
        "Геопозицияға рөхсәт — тик сәфәр өсөн һәм тик кәрәк саҡта."
      ),
    },
  ];

  const allDone = items.every((i) => !!granted[i.kind]);

  return (
    <>
      <SubHeader
        title={appText("Согласия", "Ризалыҡтар")}
        subtitle={appText("152-ФЗ · твои данные под защитой", "152-ФЗ · мәғлүмәтең яҡлауҙа")}
        onBack={() => navigate(-1)}
      />

      <div className="list">
        {items.map((it) => (
          <label key={it.kind} className="list-row list-row--check">
            <div className="list-row__main">
              <div className="list-row__title">{it.title}</div>
              <div className="list-row__sub">{it.body}</div>
              {/* Дата — это и есть доказательство. Показываем её человеку, а не прячем. */}
              {granted[it.kind] && (
                <div className="list-row__sub">
                  {appText("Записано ", "Яҙылған ")}
                  {formatWhen(granted[it.kind], ru)}
                </div>
              )}
            </div>
            <input
              type="checkbox"
              className="checkbox"
              checked={state[it.kind]}
              onChange={() => void toggle(it.kind)}
              disabled={busy === it.kind || !!granted[it.kind]}
              aria-label={it.title}
            />
          </label>
        ))}
      </div>

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}

      <div className={"consents__status" + (allDone ? " ok" : "")}>
        {allDone
          ? appText("Спасибо! Все согласия отмечены.", "Рәхмәт! Барлыҡ ризалыҡтар билдәләнгән.")
          : appText("Отметь согласия, чтобы пользоваться всеми возможностями.", "Бар мөмкинлектәр өсөн ризалыҡтарҙы билдәлә.")}
      </div>
    </>
  );
}

/** Компактная шапка вложенного экрана с кнопкой «назад». */
export function SubHeader({
  title,
  subtitle,
  onBack,
}: {
  title: string;
  subtitle?: string;
  onBack: () => void;
}) {
  const { appText } = useLang();
  return (
    <header className="screen-header screen-header--sub">
      <div className="screen-header__row">
        <button
          type="button"
          className="subheader__back"
          onClick={onBack}
          aria-label={appText("Назад", "Артҡа")}
        >
          <span style={{ display: "inline-flex", transform: "rotate(180deg)" }}>
            <IconChevron size={22} />
          </span>
        </button>
        <div style={{ flex: 1 }}>
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </div>
      </div>
    </header>
  );
}
