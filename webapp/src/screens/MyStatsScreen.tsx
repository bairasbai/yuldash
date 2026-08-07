// ================================================================
//  «Мой Юлдаш» — личная статистика попутчика (GET /me/stats).
//  Км / поездки / сэкономлено ₽ / CO₂ + звание и прогресс.
//  Кнопка «Поделиться» (Web Share API, фолбэк — копирование).
//  Новичок → честные нули без «ложных наград». RequireAuth.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchMyStats, type MyStats } from "../api/stats";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconShare, IconCheck, IconHeart } from "../components/Icons";

type Status = "loading" | "error" | "ready";

export default function MyStatsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [stats, setStats] = useState<MyStats | null>(null);
  const [shared, setShared] = useState(false);

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

  async function share() {
    if (!stats) return;
    const rank = appText(stats.rank.title_ru, stats.rank.title_ba);
    const text = appText(
      `Мой Юлдаш: ${stats.trips} поездок, ${stats.km} км вместе, сэкономил ${stats.saved_rub.toLocaleString("ru-RU")} ₽ и ${stats.co2_saved_kg} кг CO₂. Звание: ${rank}. Попутки между своими 🌿`,
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
            <div className="trust-hero__badge" style={{ fontSize: 28 }}>
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
                    `Ещё ${stats.rank.to_next} поездок до звания «${stats.rank.next_title_ru}»`,
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
              "Экономия и CO₂ — примерная оценка относительно поездки на такси.",
              "Янға ҡалыу һәм CO₂ — таксиға ҡарата яҡынса иҫәп."
            )}
          </p>
        </>
      )}
    </>
  );
}
