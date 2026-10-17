// ================================================================
//  «Поделиться поездкой с близким» — общая карточка для попутки
//  и такси (зеркало Android: family.py + экраны поездки).
//
//  Близкий следит за поездкой в браузере по ссылке — приложение ему не
//  нужно. Ссылку можно отозвать: строка удаляется, токен «сгорает»
//  (важно, когда ошиблись номером).
//
//  Честность: SMS на проде выключены, поэтому «отправлено» и «получили»
//  здесь не пишем. После «Поделиться» ссылка показывается на экране, а
//  отправляет её сам человек: «Скопировать» или «Отправить» (системное
//  окно, если браузер его умеет). Как Android: TripLiveLink.kt и
//  BookingActiveTripScreen.kt.
//
//  Плюс отметки для близких: «Доехала» / «Поездка завершена» — они
//  сохраняются на сервере. «Села в машину» близкие получают, когда посадку
//  отметит водитель («Сел», решение 17.10.2026): своего «Я сел» у пассажира нет.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { enqueue } from "../utils/outbox";
import {
  fetchBookingShares,
  fetchOrderShares,
  fetchTrustedContacts,
  revokeBookingShare,
  revokeOrderShare,
  setTripStatus,
  shareBooking,
  shareOrder,
  tripLiveLink,
  type TripShare,
  type TrustedContact,
  type TripStatus,
} from "../api/family";
import { IconCheck, IconCopy, IconShare, IconTrash, IconUsers } from "./Icons";
import { track } from "../analytics";
import type { BookingStatus } from "../api/bookings";

export default function ShareTripCard({
  bookingId,
  orderId,
  showStatuses = false,
  bookingStatus = "confirmed",
  onStatusChange,
}: {
  /** Ровно одно из двух: бронь попутки или такси-заказ. */
  bookingId?: number;
  orderId?: number;
  /** Показывать ли кнопки статуса для близких (только у пассажира попутки). */
  showStatuses?: boolean;
  bookingStatus?: BookingStatus;
  /** После записи статуса перечитать авторитетное состояние брони. */
  onStatusChange?: () => void | Promise<void>;
}) {
  const { appText } = useLang();

  const [contacts, setContacts] = useState<TrustedContact[]>([]);
  const [shares, setShares] = useState<TripShare[] | null>(null); // null = блок скрыт
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [open, setOpen] = useState(false);
  /** Чья ссылка на экране: только что созданная, иначе первая из активных. */
  const [focusId, setFocusId] = useState<number | null>(null);

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

  if (shares === null && !showStatuses) return null;
  const activeShares = shares ?? [];

  async function share(contactId: number) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const created = bookingId
        ? await shareBooking(bookingId, contactId)
        : orderId
          ? await shareOrder(orderId, contactId)
          : null;
      setOpen(false);
      if (created) {
        // Ссылку показываем сразу, не дожидаясь перечитывания списка.
        setShares((prev) => [...(prev ?? []).filter((s) => s.id !== created.id), created]);
        setFocusId(created.id);
      }
      load();
      // Не «отправлена»: SMS близкому может не уйти. Отправляет сам человек.
      setNote(appText("Ссылка готова — отправь её близкому сам", "Һылтанма әҙер — яҡын кешегә үҙең ебәр"));
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
    if (focusId === shareId) setFocusId(null);
    try {
      if (bookingId) await revokeBookingShare(bookingId, shareId);
      else if (orderId) await revokeOrderShare(orderId, shareId);
      track("revoke_share");
    } catch {
      setShares(prev); // откат
      setNote(appText("Не получилось отозвать доступ.", "Рөхсәтте кире алып булманы."));
    }
  }

  async function sendStatus(status: TripStatus) {
    if (!bookingId || busy) return;
    const generation = getSessionGeneration();
    const currentSession = () => generation === getSessionGeneration();
    setBusy(true);
    try {
      const updated = await setTripStatus(bookingId, status);
      if (!currentSession()) return;
      setShares(updated);
      await onStatusChange?.();
      if (!currentSession()) return;
      // Только «сохранено»: дошло ли сообщение близким, мы не знаем (SMS на проде выключены).
      setNote(appText("Сохранено", "Һаҡланды"));
    } catch (e) {
      if (!currentSession()) return;
      // Трасса без связи — это норма, а не сбой. «Доехал» и «завершил» — ровно те отметки,
      // которые человек делает в дороге между сёлами, и терять их нельзя: близкие ждут
      // именно их. Кладём в очередь — уйдёт само, когда сеть вернётся.
      //
      // Отличаем обрыв связи от отказа сервера: `status === 0` — до сервера не достучались.
      // Ответ сервера («поездка уже завершена») повторять бессмысленно, он не изменится.
      if (e instanceof ApiError && e.status === 0) {
        try {
          await enqueue(bookingId, "trip_status", status);
          if (!currentSession()) return;
          setNote(appText("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ"));
        } catch {
          if (currentSession()) setNote(appText("Не получилось отправить статус.", "Хәлде ебәреп булманы."));
        }
      } else {
        setNote(appText("Не получилось отправить статус.", "Хәлде ебәреп булманы."));
      }
    } finally {
      if (currentSession()) setBusy(false);
    }
  }

  /** Имя контакта по id — в списке ссылок показываем «кому открыто». */
  function contactName(id: number | null): string {
    const c = contacts.find((x) => x.id === id);
    return c?.name || appText("Близкий", "Яҡын кеше");
  }

  const withoutContacts = contacts.length === 0;

  // Ссылка, которую человек отправит близкому сам.
  const focusShare = activeShares.find((s) => s.id === focusId) ?? activeShares.find((s) => tripLiveLink(s));
  const liveLink = tripLiveLink(focusShare);
  const canSystemShare = typeof navigator !== "undefined" && typeof navigator.share === "function";
  const shareText = (link: string) =>
    appText("Следи за моей поездкой в Юлдаш: ", "Юлдашта минең сәфәремде күҙәт: ") + link;

  async function copyLink(link: string) {
    try {
      await navigator.clipboard.writeText(link);
      setNote(appText("Ссылка скопирована", "Һылтанма күсерелде"));
    } catch {
      setNote(appText("Не получилось скопировать. Выдели ссылку и скопируй сам.", "Күсереп булманы. Һылтанманы һайлап, үҙең күсер."));
    }
  }

  async function sendLink(link: string) {
    try {
      await navigator.share({ title: appText("Отправить ссылку", "Һылтанманы ебәреү"), text: shareText(link) });
    } catch (e) {
      // Закрыл окно «Отправить» сам — не ошибка. Окно не открылось — хотя бы скопируем.
      if (e instanceof DOMException && e.name === "AbortError") return;
      await copyLink(link);
    }
  }

  return (
    <div className="act-card">
      {shares !== null && (<>
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
      {activeShares.length > 0 && (
        <div className="list" style={{ marginTop: 0, marginBottom: 12 }}>
          {activeShares.map((s) => (
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

      {/* Ссылка на живую поездку: её отправляет человек сам — SMS близкому мы не обещаем. */}
      {liveLink && !open && (
        <div className="share-link">
          <strong className="share-link__title">{appText("Ссылка для близкого", "Яҡын кеше өсөн һылтанма")}</strong>
          <div className="share-link__url">{liveLink}</div>
          <div className="act-card__actions">
            <button type="button" className="btn-soft" onClick={() => copyLink(liveLink)}>
              <IconCopy size={18} /> {appText("Скопировать", "Күсереү")}
            </button>
            {canSystemShare && (
              <button type="button" className="btn-primary" onClick={() => sendLink(liveLink)}>
                <IconShare size={18} /> {appText("Отправить", "Ебәреү")}
              </button>
            )}
          </div>
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
            {appText("Отмена", "Баш тартыу")}
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
          {activeShares.length > 0
            ? appText("Поделиться ещё с кем-то", "Тағы кемгәлер ебәреү")
            : appText("Поделиться поездкой с близким", "Яҡын кешегә ебәреү")}
        </button>
      )}

      </>)}

      {/* «Доехала» и завершение не требуют ссылки или доверенных контактов. Посадку отмечает
          водитель (решение 17.10.2026) — до неё пассажиру отмечать нечего. */}
      {showStatuses && bookingId && bookingStatus === "onboard" && (
        <>
          <p className="act-card__text" style={{ margin: "14px 0 8px" }}>
            {appText(
              "Как идёт поездка?",
              "Сәфәр нисек бара?"
            )}
          </p>
          <div className="chips">
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
