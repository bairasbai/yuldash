// ================================================================
//  «Мой Юлдаш» — личная статистика попутчика (GET /me/stats).
//  Км / поездки / сэкономлено ₽ / CO₂ + звание и прогресс.
//  Кнопка «Поделиться» (Web Share API, фолбэк — копирование).
//  Новичок → честные нули без «ложных наград». RequireAuth.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchMyStats, fetchAchievements, type MyStats, type MyAchievements } from "../api/stats";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconShare, IconCheck, IconHeart } from "../components/Icons";

import { pluralRu } from "../utils/format";
type Status = "loading" | "error" | "ready";

export default function MyStatsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [stats, setStats] = useState<MyStats | null>(null);
  const [shared, setShared] = useState(false);
  // Тёплые бейджи. На заказы не влияют — украшение и повод вернуться. null = ручки нет.
  const [badges, setBadges] = useState<MyAchievements | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMyStats(signal)
      .then((s) => {
        setStats(s);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Эндпоинт может ещё не быть на проде — не краш, а состояние ошибки с «Повторить».
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Достижения — отдельным запросом: их отсутствие не должно ломать статистику.
  useEffect(() => {
    const ac = new AbortController();
    fetchAchievements(ac.signal)
      .then(setBadges)
      .catch(() => setBadges(null)); // 404 до деплоя → блок скрыт
    return () => ac.abort();
  }, []);

  async function share() {
    if (!stats) return;
    const rank = appText(stats.rank.title_ru, stats.rank.title_ba);
    const text = appText(
      `Мой Юлдаш: ${stats.trips} ${pluralRu(stats.trips, "поездка", "поездки", "поездок")}, ${stats.km} км вместе, сэкономил ${stats.saved_rub.toLocaleString("ru-RU")} ₽ и ${stats.co2_saved_kg} кг CO₂. Звание: ${rank}. Попутки между своими 🌿`,
      `Минең Юлдаш: ${stats.trips} сәфәр, ${stats.km} км бергә, ${stats.saved_rub.toLocaleString("ru-RU")} ₽ һәм ${stats.co2_saved_kg} кг CO₂ янға ҡалдырҙым. Исем: ${rank}. Үҙебеҙ араһында юлдаштар 🌿`
    );
    try {
      if (navigator.share) {
        await navigator.share({ text });
      } else {
        await navigator.clipboard.writeText(text);
      }
      setShared(true);
      setTimeout(() => setShared(false), 2200);
    } catch {
      /* пользователь отменил шаринг — молча */
    }
  }

  const tiles = (s: MyStats) => [
    { v: s.km.toLocaleString("ru-RU"), label: appText("км вместе", "км бергә") },
    { v: String(s.trips), label: appText("поездок", "сәфәр") },
    { v: `${s.saved_rub.toLocaleString("ru-RU")} ₽`, label: appText("сэкономлено", "янға ҡалды") },
    { v: `${s.co2_saved_kg} кг`, label: appText("меньше CO₂", "кәм CO₂") },
  ];

  return (
    <>
      <SubHeader
        title={appText("Мой Юлдаш", "Минең Юлдаш")}
        subtitle={appText("Сколько вы проехали вместе", "Бергә күпме юл үттегеҙ")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && stats && (
        <>
          {/* Звание + прогресс (переиспользуем стиль trust-hero) */}
          <div className="trust-hero">
            <div className="trust-hero__badge" style={{ fontSize: "var(--font-title)" }}>
              <IconHeart size={34} />
            </div>
            <div className="trust-hero__level">
              {appText(stats.rank.title_ru, stats.rank.title_ba)}
            </div>
            {stats.rank.next_title_ru && stats.rank.next_at != null ? (
              <>
                <div className="trust-progress">
                  {Array.from({ length: 5 }).map((_, i) => (
                    <span
                      key={i}
                      className={
                        i < Math.min(5, Math.round(((stats.rank.next_at! - stats.rank.to_next) / stats.rank.next_at!) * 5))
                          ? "on"
                          : ""
                      }
                    />
                  ))}
                </div>
                <p className="trust-hero__next">
                  {appText(
                    `Ещё ${stats.rank.to_next} ${pluralRu(stats.rank.to_next, "поездка", "поездки", "поездок")} до звания «${stats.rank.next_title_ru}»`,
                    `«${stats.rank.next_title_ba}» исеменә тиклем тағы ${stats.rank.to_next} сәфәр`
                  )}
                </p>
              </>
            ) : (
              <p className="trust-hero__next">
                {appText("Высшее звание попутчика. Спасибо! 🙌", "Юлдаштың иң юғары исеме. Рәхмәт! 🙌")}
              </p>
            )}
          </div>

          {/* Плитки метрик */}
          <div className="stat-grid">
            {tiles(stats).map((t, i) => (
              <div key={i} className="stat-tile">
                <b>{t.v}</b>
                <span>{t.label}</span>
              </div>
            ))}
          </div>

          {stats.trips === 0 && (
            <p className="stat-newbie">
              {appText(
                "Ты только начинаешь. Первая поездка — и цифры оживут 🚗",
                "Һин яңы башлайһың. Беренсе сәфәр — һандар терелер 🚗"
              )}
            </p>
          )}

          {/* Достижения: полученные — и сколько осталось до следующих */}
          {badges && badges.achievements.length > 0 && (
            <>
              <h2 className="section-title">
                {appText("Достижения", "Ҡаҙаныштар")}
                {badges.earned_count > 0 && (
                  <span className="badge badge--mint" style={{ marginLeft: 8 }}>
                    {appText(`${badges.earned_count} получено`, `${badges.earned_count} алынған`)}
                  </span>
                )}
              </h2>
              <div className="list">
                {badges.achievements.map((b) => (
                  <div key={b.code} className="list-row">
                    <span className="list-row__icon">
                      {b.earned ? <IconCheck size={20} /> : <IconHeart size={20} />}
                    </span>
                    <div className="list-row__main">
                      <div className="list-row__title">{appText(b.ru, b.ba)}</div>
                      <div className="list-row__sub">
                        {b.earned
                          ? appText("Получено", "Алынған")
                          : appText(
                              `${Math.min(b.value, b.goal)} из ${b.goal}`,
                              `${b.goal}-нән ${Math.min(b.value, b.goal)}`
                            )}
                      </div>
                      {!b.earned && (
                        <div className="trust-progress" style={{ marginTop: 6 }}>
                          <div
                            className="earn-bar__fill"
                            style={{
                              width: `${Math.max(4, Math.min(100, Math.round((b.value / Math.max(1, b.goal)) * 100)))}%`,
                              height: 6,
                            }}
                          />
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
              <p className="demand__quiet">
                {appText(
                  "Значки — просто приятно. На заказы и цену они не влияют.",
                  "Билдәләр — күңелле генә. Заказға һәм хаҡҡа тәьҫир итмәй."
                )}
              </p>
            </>
          )}

          <button type="button" className="btn-primary submit-btn" onClick={share} style={{ marginTop: 18 }}>
            {shared ? (
              <>
                <IconCheck size={18} /> {appText("Готово", "Әҙер")}
              </>
            ) : (
              <>
                <IconShare size={18} /> {appText("Поделиться", "Бүлешеү")}
              </>
            )}
          </button>

          <p className="receipt__foot">
            {appText(
              "Экономия и CO₂ — примерная оценка в сравнении с поездкой на такси.",
              "Янға ҡалыу һәм CO₂ — таксиға ҡарата яҡынса иҫәп."
            )}
          </p>
        </>
      )}
    </>
  );
}
