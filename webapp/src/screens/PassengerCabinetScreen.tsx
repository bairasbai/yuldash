// ================================================================
//  Кабинет пассажира — зеркало Android PassengerCabinetScreen (ProfileScreen.kt):
//  «Твои поездки и заявки», ограничения, зелёная карточка «Быстрый заказ»,
//  три метрики (активные · заявки · рейтинг), «Мои поездки», ближайшая бронь
//  карточкой MyTripCard, группа переходов (найти поездку, предзаказы, такси,
//  заявка, адреса, кошелёк, безопасность). RequireAuth.
//  Данные: GET /bookings/mine, /requests/mine, /me/restrictions, рейтинг — из /me.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchMyBookings, type MyBooking } from "../api/bookings";
import { fetchMyRequests } from "../api/requests";
import { fetchMyRestrictions, type Restriction } from "../api/safety";
import { useAuth } from "../auth/AuthProvider";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, EmptyStateCard } from "../components/States";
import MyTripCard, { bookingStatusAllowsActiveTrip } from "../components/MyTripCard";
import { CabinetMetric, RestrictionsCard, SettingsGroup, SettingsNavRow } from "../components/cabinetUi";
import {
  IconBell,
  IconCar,
  IconChevron,
  IconClockCal,
  IconHome,
  IconReceipt,
  IconRides,
  IconRoute,
  IconSearch,
  IconShield,
  IconTrend,
  IconWallet,
} from "../components/Icons";

type Status = "loading" | "error" | "ready";

const ACTIVE = new Set(["pending", "confirmed", "onboard"]);

export default function PassengerCabinetScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { user } = useAuth();

  const [status, setStatus] = useState<Status>("loading");
  const [bookings, setBookings] = useState<MyBooking[]>([]);
  const [requestCount, setRequestCount] = useState<number | null>(null);
  const [restrictions, setRestrictions] = useState<{ items: Restriction[]; supportRu?: string; supportBa?: string } | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMyBookings(signal)
      .then((rows) => {
        setBookings(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus("error");
      });
    // Заявки и ограничения — не критичны: их сбой не ломает кабинет.
    fetchMyRequests(signal)
      .then((rows) => setRequestCount(rows.length))
      .catch(() => {});
    fetchMyRestrictions(signal)
      .then((r) => setRestrictions({ items: r.items ?? r.restrictions ?? [], supportRu: r.support_ru, supportBa: r.support_ba }))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const active = useMemo(() => bookings.filter((b) => ACTIVE.has(b.status)), [bookings]);
  const nearest = active[0] ?? null;
  const ratingText = user?.rating != null ? user.rating.toFixed(1) : "—";

  // Куда ведёт бронь по статусу: подтверждение → детали, активная → поездка.
  function openBooking(b: MyBooking) {
    if (bookingStatusAllowsActiveTrip(b.status)) navigate(`/trip/${b.id}`);
    else navigate(`/booking/${b.id}`);
  }

  const loading = status === "loading";

  return (
    <>
      <SubHeader title={appText("Кабинет пассажира", "Пассажир кабинеты")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        <div className="cabinet__intro">
          <h2>{appText("Твои поездки и заявки", "Һинең сәфәрҙәр һәм заявкалар")}</h2>
          <p>{appText("Быстрый доступ к бронированиям, заявкам и защите поездки.", "Брондәргә, заявкаларға һәм хәүефһеҙлеккә тиҙ инеү.")}</p>
        </div>

        {restrictions && restrictions.items.length > 0 && (
          <RestrictionsCard items={restrictions.items} supportRu={restrictions.supportRu} supportBa={restrictions.supportBa} />
        )}

        {/* Быстрый заказ такси: зелёная карточка CanonGreen2 с кругом и стрелкой. */}
        <button type="button" className="quick-order" onClick={() => navigate("/taxi")}>
          <span className="quick-order__icon" aria-hidden><IconCar size={24} /></span>
          <span className="quick-order__text">
            <strong>{appText("Быстрый заказ", "Тиҙ заказ")}</strong>
            <small>{appText("Вызвать машину сейчас — цену видно заранее", "Хәҙер машина саҡырыу — хаҡ алдан күренә")}</small>
          </span>
          <span className="quick-order__chev" aria-hidden><IconChevron size={22} /></span>
        </button>

        <div className="cab-metrics">
          <CabinetMetric label={appText("Активные", "Актив")} value={loading ? "—" : String(active.length)} />
          <CabinetMetric label={appText("Заявки", "Заявкалар")} value={loading || requestCount == null ? "—" : String(requestCount)} />
          <CabinetMetric label={appText("Рейтинг", "Рейтинг")} value={ratingText} />
        </div>

        <SettingsGroup>
          <SettingsNavRow
            icon={<IconRides size={24} />}
            title={appText("Мои поездки", "Минең сәфәрҙәр")}
            subtitle={appText("Активные брони, история и чат по поездке", "Актив брондәр, тарих һәм сәфәр чаты")}
            onClick={() => navigate("/rides")}
          />
        </SettingsGroup>

        {loading && <LoadingList count={2} />}
        {status === "error" && (
          <EmptyStateCard
            icon={<IconRoute size={30} />}
            title={appText("Не удалось загрузить поездки", "Сәфәрҙәрҙе йөкләп булманы")}
            text={appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}
            action={appText("Повторить", "Ҡабатлау")}
            onAction={() => load()}
          />
        )}
        {status === "ready" && !nearest && (
          <EmptyStateCard
            icon={<IconRides size={30} />}
            title={appText("Активных поездок нет", "Актив сәфәрҙәр юҡ")}
            text={appText(
              "Найди попутку рядом или оставь заявку — водители увидят её и откликнутся.",
              "Яҡындағы юлдашты тап йәки заявка ҡалдыр — йөрөтөүселәр күреп яуап бирер."
            )}
            action={appText("Смотреть попутки рядом", "Яҡындағы юлдаштарҙы ҡарау")}
            onAction={() => navigate("/rides/feed")}
          />
        )}
        {status === "ready" && nearest && (
          <MyTripCard
            booking={nearest}
            index={0}
            primaryAction={bookingStatusAllowsActiveTrip(nearest.status) ? appText("Открыть поездку", "Сәфәрҙе асыу") : appText("Подробнее", "Ентекле")}
            secondaryAction={appText("Все поездки", "Бөтә сәфәрҙәр")}
            onPrimary={() => openBooking(nearest)}
            onSecondary={() => navigate("/rides")}
          />
        )}

        <SettingsGroup>
          <SettingsNavRow
            icon={<IconSearch size={24} />}
            title={appText("Найти поездку", "Сәфәр табыу")}
            subtitle={appText("Открыть список ближайших маршрутов", "Яҡындағы маршруттарҙы асыу")}
            onClick={() => navigate("/rides/feed")}
          />
          <SettingsNavRow
            icon={<IconClockCal size={24} />}
            title={appText("Мои предзаказы", "Минең алдан заказдар")}
            subtitle={appText("Такси «на время»: обратный отсчёт и поиск", "«Ваҡытҡа» такси: кире иҫәп һәм эҙләү")}
            onClick={() => navigate("/scheduled")}
          />
          <SettingsNavRow
            icon={<IconReceipt size={24} />}
            title={appText("Мои поездки на такси", "Такситағы сәфәрҙәрем")}
            subtitle={appText("История и чек за каждую поездку", "Тарих һәм һәр сәфәр өсөн чек")}
            onClick={() => navigate("/my-taxi")}
          />
          <SettingsNavRow
            icon={<IconRoute size={24} />}
            title={appText("Создать заявку", "Заявка булдырыу")}
            subtitle={appText("Если готовой поездки нет", "Әҙер сәфәр булмаһа")}
            onClick={() => navigate("/request")}
          />
          <SettingsNavRow
            icon={<IconHome size={24} />}
            title={appText("Мои адреса", "Минең адрестар")}
            subtitle={appText("Дом, работа и любимые места", "Өй, эш һәм яратҡан урындар")}
            onClick={() => navigate("/places")}
          />
          <SettingsNavRow
            icon={<IconWallet size={24} />}
            title={appText("Кошелёк", "Янсыҡ")}
            subtitle={appText("Баланс и история операций", "Баланс һәм операциялар тарихы")}
            onClick={() => navigate("/wallet")}
          />
          <SettingsNavRow
            icon={<IconShield size={24} />}
            title={appText("Безопасность поездки", "Сәфәр хәүефһеҙлеге")}
            subtitle={appText("SOS, скрытый номер и доверенные контакты", "SOS, йәшерен номер һәм ышаныслы контакттар")}
            onClick={() => navigate("/safety")}
          />
        </SettingsGroup>

        {/* Есть только в вебе: повтор маршрута, «Мой Юлдаш», подписки на маршруты. Держим
            той же группой, чтобы дороги к ним не пропали при выравнивании с приложением. */}
        <SettingsGroup>
          <SettingsNavRow
            icon={<IconRoute size={24} />}
            title={appText("Повторить маршрут", "Маршрутты ҡабатлау")}
            subtitle={appText("Снова туда же, в один тап", "Тағы шунда уҡ, бер баҫыуҙа")}
            onClick={() => navigate("/repeat")}
          />
          <SettingsNavRow
            icon={<IconTrend size={24} />}
            title={appText("Мой Юлдаш", "Минең Юлдаш")}
            subtitle={appText("Сколько проехал и сэкономил", "Күпме йөрөнөң һәм һаҡланың")}
            onClick={() => navigate("/stats")}
          />
          <SettingsNavRow
            icon={<IconBell size={24} />}
            title={appText("Подписки", "Яҙылыуҙар")}
            subtitle={appText("Сообщим, когда появится нужный маршрут", "Кәрәкле маршрут сыҡҡас хәбәр итәбеҙ")}
            onClick={() => navigate("/route-watches")}
          />
        </SettingsGroup>
      </div>
    </>
  );
}
