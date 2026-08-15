// ================================================================
//  Помеченные тексты → /admin/text-flags (RequireAdmin).
//  Зеркало Android AdminTextFlagsScreen.kt.
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
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconFlag, IconPhone } from "../components/Icons";
import { formatRelative } from "../utils/format";

type State = "loading" | "error" | "ready";

const KINDS: { key: string; ru: string; ba: string }[] = [
  { key: "", ru: "Все", ba: "Барыһы" },
  { key: "warn", ru: "Фишинг", ba: "Фишинг" },
  { key: "contact", ru: "Увод контакта", ba: "Контакт алыу" },
  { key: "abuse", ru: "Мат", ba: "Әшәке һүҙ" },
];

/** Вид пометки — словами и цветом. */
function kindBadge(kind: string): { cls: string; ru: string; ba: string } {
  switch (kind) {
    case "warn":
      return { cls: "badge--danger", ru: "Похоже на обман", ba: "Алдау һымаҡ" };
    case "contact":
      return { cls: "badge--gold", ru: "Уводит из приложения", ba: "Ҡушымтанан алып китә" };
    case "abuse":
      return { cls: "badge--gold", ru: "Грубость", ba: "Тупаҫлыҡ" };
    default:
      return { cls: "badge--muted", ru: kind, ba: kind };
  }
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

  return (
    <>
      <SubHeader
        title={appText("Помеченные тексты", "Билдәләнгән текстар")}
        subtitle={appText("Что система поймала и у кого", "Система нимә тотто һәм кемдә")}
        onBack={() => navigate(-1)}
      />

      <div className="chips" style={{ marginTop: 10 }}>
        {KINDS.map((k) => (
          <button
            key={k.key || "all"}
            type="button"
            className={"chip" + (kind === k.key ? " chip--on" : "")}
            onClick={() => setKind(k.key)}
          >
            {appText(k.ru, k.ba)}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load(kind)} />}

      {state === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 32 }}>
            <div className="state__icon">
              <IconCheck size={34} />
            </div>
            <h2>{appText("Пометок нет", "Билдәләр юҡ")}</h2>
            <p>
              {appText(
                "Люди пишут по-человечески. Как только система что-то заметит — покажем здесь.",
                "Кешеләр кешесә яҙа. Система берәй нәмә һиҙһә — бында күрһәтәбеҙ."
              )}
            </p>
          </div>
        ) : (
          <div className="admin-cards">
            {rows.map((f) => {
              const badge = kindBadge(f.kind);
              const repeat = f.user_flags_total > 1;
              return (
                <div key={f.id} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title">
                      <IconFlag size={16} /> {f.user_name}
                    </div>
                    <span className={"badge " + badge.cls}>{appText(badge.ru, badge.ba)}</span>
                  </div>

                  <div className="admin-card__sub">
                    {f.place_label} · {formatRelative(f.created_at, ru)}
                    {f.ref_id != null && (
                      <>
                        {" · "}
                        {appText(`запись № ${f.ref_id}`, `яҙма № ${f.ref_id}`)}
                      </>
                    )}
                  </div>

                  {/* Разовое ≠ система: одна пометка — случайность, десять — привычка. */}
                  <div className="money-row__foot" style={{ marginTop: 8 }}>
                    <span className={"badge " + (repeat ? "badge--gold" : "badge--muted")}>
                      {appText(
                        `Всего пометок: ${f.user_flags_total}`,
                        `Барлыҡ билдә: ${f.user_flags_total}`
                      )}
                    </span>
                    {repeat && (
                      <span className="badge badge--muted">
                        {appText("Не первый раз", "Беренсе тапҡыр түгел")}
                      </span>
                    )}
                  </div>

                  {f.user_phone && (
                    <a className="admin-card__phone" href={`tel:${f.user_phone}`}>
                      <IconPhone size={18} /> {f.user_phone}
                    </a>
                  )}
                </div>
              );
            })}
          </div>
        ))}

      {state === "ready" && rows.length > 0 && (
        <p className="receipt__foot">
          {appText(
            "Пометка — повод посмотреть, а не приговор. Решение всегда за человеком.",
            "Билдә — ҡарау сәбәбе, хөкөм түгел. Ҡарар һәр саҡ кеше ҡулында."
          )}
        </p>
      )}
    </>
  );
}
