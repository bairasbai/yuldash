// ================================================================
//  «Доверенные контакты» — кому Юлдаш шлёт SMS при SOS / статусах поездки.
//  GET/POST /trusted-contacts (есть на бэке), DELETE — best-effort.
//  RequireAuth. Все состояния, двуязычно, мягкая деградация.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  addTrustedContact,
  deleteTrustedContact,
  fetchTrustedContacts,
  type TrustedContact,
} from "../api/family";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconTrash, IconCheck, IconShield, IconUsers } from "../components/Icons";

type Status = "loading" | "error" | "ready";

export default function TrustedContactsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [contacts, setContacts] = useState<TrustedContact[]>([]);

  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [relation, setRelation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchTrustedContacts(signal)
      .then((rows) => {
        setContacts(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setContacts([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function add() {
    const n = name.trim();
    const p = phone.trim();
    if (!n || !p || busy) return;
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const created = await addTrustedContact({ name: n, phone: p, relation: relation.trim() });
      setContacts((c) => [...c, created]);
      setName("");
      setPhone("");
      setRelation("");
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 400
          ? appText("Проверь номер телефона.", "Телефон һанын тикшер.")
          : e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось добавить. Попробуй снова.", "Өҫтәп булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    const prev = contacts;
    setContacts((c) => c.filter((x) => x.id !== id)); // оптимистично
    setNote(null);
    try {
      await deleteTrustedContact(id);
    } catch (e) {
      // Ручки удаления может ещё не быть на проде — честно вернём контакт и подскажем.
      setContacts(prev);
      if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
        setNote(appText("Удаление появится чуть позже.", "Юйыу бер аҙҙан эшләй башлар."));
      } else {
        setNote(appText("Не удалось удалить. Попробуй снова.", "Юйып булманы. Ҡабат ҡара."));
      }
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Доверенные контакты", "Ышаныслы контакттар")}
        subtitle={appText("Кому сообщить, если что-то пойдёт не так", "Ниҙер булһа, кемгә хәбәр итергә")}
        onBack={() => navigate(-1)}
      />

      <div className="safe-note safe-note--tight">
        <IconShield size={20} />
        <p>
          {appText(
            "Этим людям Юлдаш пришлёт SMS при SOS и статусах поездки. Добавляй только своих.",
            "Был кешеләргә Юлдаш SOS һәм сәфәр хәлдәрендә SMS ебәрер. Тик үҙеңдекеләрҙе өҫтә."
          )}
        </p>
      </div>

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && (
        <>
          {contacts.length === 0 ? (
            <div className="state" style={{ paddingBottom: 20 }}>
              <div className="state__icon" aria-hidden><IconUsers size={34} /></div>
              <h2>{appText("Пока никого нет", "Әле бер кем дә юҡ")}</h2>
              <p>{appText("Добавь близкого — он будет знать, что ты в пути.", "Яҡыныңды өҫтә — ул һинең юлда икәнеңде белер.")}</p>
            </div>
          ) : (
            <div className="list">
              {contacts.map((c) => (
                <div key={c.id} className="list-row">
                  <span className="list-row__icon">{avatarInitial(c.name)}</span>
                  <div className="list-row__main">
                    <div className="list-row__title">{c.name}</div>
                    <div className="list-row__sub">
                      {[c.relation, c.phone].filter(Boolean).join(" · ")}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={() => remove(c.id)}
                    aria-label={appText("Удалить", "Юйыу")}
                  >
                    <IconTrash size={20} />
                  </button>
                </div>
              ))}
            </div>
          )}

          {note && <div className="soft-note">{note}</div>}

          <h2 className="section-title">{appText("Добавить контакт", "Контакт өҫтәргә")}</h2>
          <div className="form">
            <label className="field">
              <span className="field__label">{appText("Имя", "Исем")}</span>
              <input
                className="field__input"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={appText("Например: Мама", "Мәҫәлән: Әсәй")}
                autoComplete="off"
              />
            </label>
            <label className="field">
              <span className="field__label">{appText("Телефон", "Телефон")}</span>
              <input
                className="field__input"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="+7 999 000-00-00"
                inputMode="tel"
                autoComplete="off"
              />
            </label>
            <label className="field">
              <span className="field__label">{appText("Кто это (необязательно)", "Кем ул (мотлаҡ түгел)")}</span>
              <input
                className="field__input"
                value={relation}
                onChange={(e) => setRelation(e.target.value)}
                placeholder={appText("Сын, жена, друг…", "Ул, ҡатын, дуҫ…")}
                autoComplete="off"
              />
            </label>

            {error && <div className="auth__error">{error}</div>}

            <button
              type="button"
              className="btn-primary submit-btn"
              onClick={add}
              disabled={!name.trim() || !phone.trim() || busy}
            >
              {busy ? appText("Добавляем…", "Өҫтәйбеҙ…") : (
                <><IconCheck size={18} /> {appText("Добавить", "Өҫтәргә")}</>
              )}
            </button>
          </div>
        </>
      )}
    </>
  );
}

function avatarInitial(name: string) {
  const c = name.trim()[0];
  return <span aria-hidden>{c ? c.toUpperCase() : "?"}</span>;
}
