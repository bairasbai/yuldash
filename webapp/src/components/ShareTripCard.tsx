// ================================================================
//  «Поделиться поездкой с близким» — общая карточка для попутки
//  и такси (зеркало Android: family.py + экраны поездки).
//
//  Близкий получает SMS со ссылкой и следит за поездкой в браузере —
//  приложение ему не нужно. Ссылку можно отозвать: строка удаляется,
//  токен «сгорает» (важно, когда ошиблись номером).
//
//  Плюс статусы для близких: «Села в машину» / «Доехала» / «Поездка
//  завершена». SMS уходит только при реальной смене статуса.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchBookingShares,
  fetchOrderShares,
  fetchTrustedContacts,
  revokeBookingShare,
  revokeOrderShare,
  setTripStatus,
  shareBooking,
  shareOrder,
  type TripShare,
  type TrustedContact,
  type TripStatus,
} from "../api/family";
import { IconCheck, IconShare, IconTrash, IconUsers } from "./Icons";

export default function ShareTripCard({
  bookingId,
  orderId,
  showStatuses = false,
}: {
  /** Ровно одно из двух: бронь попутки или такси-заказ. */
  bookingId?: number;
  orderId?: number;
  /** Показывать ли кнопки статуса для близких (только у пассажира попутки). */
  showStatuses?: boolean;
}) {
  const { appText } = useLang();

  const [contacts, setContacts] = useState<TrustedContact[]>([]);
  const [shares, setShares] = useState<TripShare[] | null>(null); // null = блок скрыт
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [open, setOpen] = useState(false);

  const load = useCallback(
    (signal?: AbortSignal) => {
      const req = bookingId
        ? fetchBookingShares(bookingId, signal)
        : orderId
          ? fetchOrderShares(orderId, signal)
          : null;
      if (!req) return;
      req
        .then(setShares)
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          // 404/405 = ручки ещё нет на проде → блок просто не показываем.
          setShares(e instanceof ApiError && (e.status === 404 || e.status === 405) ? null : []);
        });
    },
    [bookingId, orderId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    fetchTrustedContacts(ac.signal)
      .then(setContacts)
      .catch(() => setContacts([]));
    return () => ac.abort();
  }, [load]);

  if (shares === null) return null;

  async function share(contactId: number) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      if (bookingId) await shareBooking(bookingId, contactId);
      else if (orderId) await shareOrder(orderId, contactId);
      setOpen(false);
      load();
      setNote(appText("Ссылка отправлена близкому", "Һылтанма яҡын кешегә ебәрелде"));
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось поделиться.", "Уртаҡлашып булманы.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function revoke(shareId: number) {
    const prev = shares ?? [];
    setShares(prev.filter((s) => s.id !== shareId)); // оптимистично
    try {
      if (bookingId) await revokeBookingShare(bookingId, shareId);
      else if (orderId) await revokeOrderShare(orderId, shareId);
    } catch {
      setShares(prev); // откат
      setNote(appText("Не получилось отозвать доступ.", "Рөхсәтте кире алып булманы."));
    }
  }

  async function sendStatus(status: TripStatus) {
    if (!bookingId || busy) return;
    setBusy(true);
    try {
      const updated = await setTripStatus(bookingId, status);
      setShares(updated);
      setNote(appText("Близкие получили сообщение", "Яҡындарға хәбәр китте"));
    } catch {
      setNote(appText("Не получилось отправить статус.", "Хәлде ебәреп булманы."));
    } finally {
      setBusy(false);
    }
  }

  /** Имя контакта по id — в списке ссылок показываем «кому открыто». */
  function contactName(id: number | null): string {
    const c = contacts.find((x) => x.id === id);
    return c?.name || appText("Близкий", "Яҡын кеше");
  }

  const withoutContacts = contacts.length === 0;

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconUsers size={18} /> {appText("Поделиться поездкой", "Сәфәр менән бүлешеү")}
      </div>
      <p className="act-card__text">
        {appText(
          "Близкий увидит поездку на карте в браузере — приложение ему не нужно.",
          "Яҡын кеше сәфәрҙе браузерҙа картала күрер — уға ҡушымта кәрәкмәй."
        )}
      </p>

      {/* Кому уже открыто + отзыв */}
      {shares.length > 0 && (
        <div className="list" style={{ marginTop: 0, marginBottom: 12 }}>
          {shares.map((s) => (
            <div key={s.id} className="list-row">
              <span className="list-row__icon">
                <IconCheck size={20} />
              </span>
              <div className="list-row__main">
                <div className="list-row__title">{contactName(s.contact_id)}</div>
                <div className="list-row__sub">
                  {appText("Ссылка активна", "Һылтанма әүҙем")}
                </div>
              </div>
              <button
                type="button"
                className="icon-btn"
                onClick={() => revoke(s.id)}
                aria-label={appText("Отозвать доступ", "Рөхсәтте кире алыу")}
              >
                <IconTrash size={20} />
              </button>
            </div>
          ))}
        </div>
      )}

      {withoutContacts ? (
        <Link className="btn-soft" to="/trusted" style={{ display: "block", textAlign: "center" }}>
          {appText("Добавить близкого", "Яҡын кешене өҫтәү")}
        </Link>
      ) : open ? (
        <div className="list" style={{ marginTop: 0 }}>
          {contacts.map((c) => (
            <button
              key={c.id}
              type="button"
              className="list-row list-row--link"
              onClick={() => share(c.id)}
              disabled={busy}
            >
              <span className="list-row__icon">
                <IconUsers size={20} />
              </span>
              <div className="list-row__main">
                <div className="list-row__title">{c.name}</div>
                {c.relation && <div className="list-row__sub">{c.relation}</div>}
              </div>
            </button>
          ))}
          <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
            {appText("Отмена", "Кире алыу")}
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="btn-soft"
          style={{ width: "100%" }}
          onClick={() => setOpen(true)}
          disabled={busy}
        >
          <IconShare size={18} />{" "}
          {shares.length > 0
            ? appText("Поделиться ещё с кем-то", "Тағы кемгәлер ебәреү")
            : appText("Поделиться поездкой с близким", "Яҡын кешегә ебәреү")}
        </button>
      )}

      {/* Статусы для близких — только если ссылка кому-то открыта */}
      {showStatuses && shares.length > 0 && (
        <>
          <p className="act-card__text" style={{ margin: "14px 0 8px" }}>
            {appText(
              "Сообщи близким, как идёт поездка:",
              "Яҡындарға сәфәр нисек барғанын хәбәр ит:"
            )}
          </p>
          <div className="chips">
            <button type="button" className="chip" onClick={() => sendStatus("sat")} disabled={busy}>
              {appText("Села в машину", "Машинаға ултырҙым")}
            </button>
            <button type="button" className="chip" onClick={() => sendStatus("arrived")} disabled={busy}>
              {appText("Доехала", "Барып еттем")}
            </button>
            <button type="button" className="chip" onClick={() => sendStatus("done")} disabled={busy}>
              {appText("Поездка завершена", "Сәфәр тамамланды")}
            </button>
          </div>
        </>
      )}

      {note && <p className="demand__quiet">{note}</p>}
    </div>
  );
}
