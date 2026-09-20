// ================================================================
//  Помеченные тексты → /admin/text-flags (RequireAdmin).
//  Зеркало Android AdminTextFlagsScreen.kt: вводная строка, чипы-фильтры по виду
//  (повторное нажатие снимает фильтр), карточки «вид · место · кто · сколько пометок».
//
//  Модерация стала видимой: система помечает фишинг, увод контакта
//  и мат — но раньше эти пометки никто не видел, и «поймали» значило
//  «положили в базу». Здесь админ видит, кто и за что помечен, и
//  сколько пометок у человека всего: разовое ≠ система.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchTextFlags, type AdminTextFlag } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { RideCardSkeleton } from "../components/States";
import { AdminIntro, ListedEmpty, ListedError, NearbyChip } from "../components/adminUi";
import { IconBlock, IconLock, IconWarn } from "../components/Icons";
import { formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";

/** Виды меток для фильтра. Порядок — по опасности: деньги → увод → грубость. */
const FLAG_KINDS: { key: string; ru: string; ba: string }[] = [
  { key: "warn", ru: "Обман с деньгами", ba: "Аҡса алдауы" },
  { key: "contact", ru: "Телефон в тексте", ba: "Текстта телефон" },
  { key: "abuse", ru: "Грубость", ba: "Тупаҫлыҡ" },
];

function flagIcon(kind: string, size: number) {
  if (kind === "warn") return <IconWarn size={size} />;
  if (kind === "contact") return <IconLock size={size} />;
  return <IconBlock size={size} />;
}

export default function AdminTextFlagsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [kind, setKind] = useState("");
  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminTextFlag[]>([]);

  const load = useCallback((k: string, signal?: AbortSignal) => {
    setState("loading");
    fetchTextFlags(k || undefined, signal)
      .then((list) => {
        setRows(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setRows([]);
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(kind, ac.signal);
    return () => ac.abort();
  }, [load, kind]);

  /** Подпись вида — по-человечески, без терминов. Фишинг — красным (деньги), остальное спокойное. */
  function flagLabel(k: string): string {
    if (k === "warn") return appText("Обман — уводят деньги", "Алдау — аҡса урлай");
    if (k === "contact") return appText("Телефон / увод из приложения", "Телефон / ҡушымтанан сығарыу");
    return appText("Грубость — мат в тексте", "Тупаҫлыҡ — текстта әрләү");
  }

  return (
    <>
      <SubHeader title={appText("Помеченные тексты", "Билдәләнгән текстар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Здесь видно, кто и за что помечен. Ничего не заблокировано — текст дошёл до получателя. Решение за тобой.",
            "Бында кем һәм ни өсөн билдәләнгәне күренә. Бер нәмә лә быуылмаған — текст барып еткән. Ҡарар һинеке."
          )}
        </AdminIntro>

        <div className="afilter-row" role="group" aria-label={appText("Вид пометки", "Билдә төрө")}>
          {FLAG_KINDS.map((k) => (
            <NearbyChip
              key={k.key}
              icon={flagIcon(k.key, 15)}
              label={appText(k.ru, k.ba)}
              active={kind === k.key}
              onClick={() => setKind(kind === k.key ? "" : k.key)}
            />
          ))}
        </div>

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && (
          <ListedError message={appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер.")} onRetry={() => load(kind)} />
        )}

        {state === "ready" && rows.length === 0 && (
          <ListedEmpty
            title={appText("Помеченных текстов нет", "Билдәләнгән текст юҡ")}
            subtitle={appText(
              "Это хорошая новость: никто не писал телефоны и грубости.",
              "Был яҡшы хәбәр: бер кем дә телефон да, тупаҫлыҡ та яҙмаған."
            )}
          />
        )}

        {state === "ready" &&
          rows.map((f) => (
            <article key={f.id} className="acard">
              <div className={"acard__row tflag" + (f.kind === "warn" ? " tflag--warn" : "")}>
                {flagIcon(f.kind, 20)}
                <strong>{flagLabel(f.kind)}</strong>
              </div>
              <span className="acard__body">
                {f.place_label}
                {f.ref_id != null && ` · ${appText(`запись № ${f.ref_id}`, `яҙма № ${f.ref_id}`)}`}
              </span>
              <span className="acard__sub">{[f.user_name, f.user_phone].filter(Boolean).join(" · ")}</span>
              {/* Разовое срабатывание бывает у любого — важна повторяемость: показываем её прямо тут. */}
              {f.user_flags_total > 1 && (
                <span className="acard__label">
                  {appText(`У этого человека пометок: ${f.user_flags_total}`, `Был кешелә билдә: ${f.user_flags_total}`)}
                </span>
              )}
              {f.created_at && <small className="acard__date">{formatWhen(f.created_at, ru)}</small>}
            </article>
          ))}
      </div>
    </>
  );
}
