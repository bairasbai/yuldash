// ================================================================
//  Одна очередь модерации → /admin/moderation (RequireAdmin).
//  Зеркало Android AdminModerationScreen.kt.
//
//  Смысл: «что я ещё не смотрел» одним списком — бизнесы на проверке
//  и купоны без решения. Порядок задаёт сервер: задержанные
//  автопроверкой (их не видят люди) → с жалобами → просто новые.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  approveCoupon,
  approvePartner,
  blockCoupon,
  fetchModerationQueue,
  rejectPartner,
  type AdminCoupon,
  type AdminPartner,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconStore, IconTicket, IconWarn } from "../components/Icons";

type State = "loading" | "error" | "ready";

export default function AdminModerationScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [partners, setPartners] = useState<AdminPartner[]>([]);
  const [coupons, setCoupons] = useState<AdminCoupon[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectFor, setRejectFor] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchModerationQueue(100, signal)
      .then((q) => {
        setPartners(q.partners);
        setCoupons(q.coupons);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setPartners([]);
          setCoupons([]);
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function act(key: string, fn: () => Promise<unknown>) {
    setBusyId(key);
    try {
      await fn();
      setRejectFor(null);
      setReason("");
      load();
    } finally {
      setBusyId(null);
    }
  }

  const total = partners.length + coupons.length;

  return (
    <>
      <SubHeader
        title={appText("Очередь модерации", "Модерация сираты")}
        subtitle={
          total > 0
            ? appText(`Не посмотрено: ${total}`, `Ҡаралмаған: ${total}`)
            : appText("Бизнесы и купоны", "Бизнестар һәм купондар")
        }
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && total === 0 && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Очередь пуста", "Сират буш")}</h2>
          <p>
            {appText(
              "Всё просмотрено. Новые заявки и купоны появятся здесь сами.",
              "Барыһы ла ҡаралған. Яңы заявкалар һәм купондар бында үҙе күренәсәк."
            )}
          </p>
        </div>
      )}

      {state === "ready" && partners.length > 0 && (
        <>
          <h2 className="section-title">{appText("Бизнесы на проверке", "Тикшереүҙәге бизнестар")}</h2>
          <div className="admin-cards">
            {partners.map((p) => {
              const key = `p${p.id}`;
              return (
                <div key={key} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title">
                      <IconStore size={16} /> {p.name}
                    </div>
                    <span className="badge badge--gold">{appText("Новый", "Яңы")}</span>
                  </div>
                  <div className="admin-card__sub">
                    {p.category} · {p.city}
                    {p.address && (
                      <>
                        <br />
                        {p.address}
                      </>
                    )}
                  </div>
                  {p.description && <div className="admin-card__reason">{p.description}</div>}

                  {rejectFor === key ? (
                    <>
                      <label className="field" style={{ marginTop: 10 }}>
                        <span className="field__label">
                          {appText("Причина отказа (её увидит владелец)", "Кире ҡағыу сәбәбе (хужа күрәсәк)")}
                        </span>
                        <input
                          className="field__input"
                          value={reason}
                          onChange={(e) => setReason(e.target.value)}
                          placeholder={appText("Коротко и по делу", "Ҡыҫҡа һәм эш буйынса")}
                        />
                      </label>
                      <div className="act-card__actions" style={{ marginTop: 10 }}>
                        <button
                          type="button"
                          className="btn-danger"
                          onClick={() => act(key, () => rejectPartner(p.id, reason.trim()))}
                          disabled={busyId === key || !reason.trim()}
                        >
                          {appText("Отклонить", "Кире ҡағыу")}
                        </button>
                        <button
                          type="button"
                          className="btn-ghost"
                          onClick={() => {
                            setRejectFor(null);
                            setReason("");
                          }}
                        >
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    </>
                  ) : (
                    <div className="act-card__actions" style={{ marginTop: 10 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => act(key, () => approvePartner(p.id))}
                        disabled={busyId === key}
                      >
                        <IconCheck size={18} /> {appText("Одобрить", "Раҫлау")}
                      </button>
                      <button
                        type="button"
                        className="btn-soft"
                        onClick={() => {
                          setRejectFor(key);
                          setReason("");
                        }}
                      >
                        {appText("Отклонить", "Кире ҡағыу")}
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {state === "ready" && coupons.length > 0 && (
        <>
          <h2 className="section-title">{appText("Купоны без решения", "Ҡарарһыҙ купондар")}</h2>
          <div className="admin-cards">
            {coupons.map((c) => {
              const key = `c${c.id}`;
              const held = c.review === "held";
              return (
                <div key={key} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title">
                      <IconTicket size={16} /> {c.title}
                    </div>
                    <span className={"badge " + (held ? "badge--danger" : "badge--gold")}>
                      {held
                        ? appText("Скрыт до проверки", "Тикшергәнгә тиклем йәшерен")
                        : appText("Не просмотрен", "Ҡаралмаған")}
                    </span>
                  </div>
                  <div className="admin-card__sub">
                    {c.partner_name} · {c.city} · {c.discount_text}
                  </div>
                  {c.description && <div className="admin-card__reason">{c.description}</div>}

                  <div className="money-row__foot" style={{ marginTop: 8 }}>
                    {c.reports_count > 0 && (
                      <span className="badge badge--danger">
                        <IconWarn size={12} />{" "}
                        {appText(`Жалоб: ${c.reports_count}`, `Зарланыу: ${c.reports_count}`)}
                      </span>
                    )}
                    {c.review_flag && <span className="badge badge--gold">{c.review_flag}</span>}
                    <span className={"badge " + (c.visible ? "badge--mint" : "badge--muted")}>
                      {c.visible
                        ? appText("Виден людям", "Кешеләргә күренә")
                        : appText("Не виден", "Күренмәй")}
                    </span>
                  </div>

                  {rejectFor === key ? (
                    <>
                      <label className="field" style={{ marginTop: 10 }}>
                        <span className="field__label">
                          {appText("Причина блокировки", "Блоклау сәбәбе")}
                        </span>
                        <input
                          className="field__input"
                          value={reason}
                          onChange={(e) => setReason(e.target.value)}
                          placeholder={appText("Что не так с купоном", "Купон менән нимә дөрөҫ түгел")}
                        />
                      </label>
                      <div className="act-card__actions" style={{ marginTop: 10 }}>
                        <button
                          type="button"
                          className="btn-danger"
                          onClick={() => act(key, () => blockCoupon(c.id, reason.trim()))}
                          disabled={busyId === key || !reason.trim()}
                        >
                          {appText("Заблокировать", "Блоклау")}
                        </button>
                        <button
                          type="button"
                          className="btn-ghost"
                          onClick={() => {
                            setRejectFor(null);
                            setReason("");
                          }}
                        >
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    </>
                  ) : (
                    <div className="act-card__actions" style={{ marginTop: 10 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => act(key, () => approveCoupon(c.id))}
                        disabled={busyId === key}
                      >
                        <IconCheck size={18} /> {appText("Всё в порядке", "Бөтәһе лә тәртиптә")}
                      </button>
                      <button
                        type="button"
                        className="btn-soft"
                        onClick={() => {
                          setRejectFor(key);
                          setReason("");
                        }}
                      >
                        {appText("Заблокировать", "Блоклау")}
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}
    </>
  );
}
