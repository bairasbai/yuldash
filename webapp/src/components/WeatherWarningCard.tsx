// ================================================================
//  ❄️ Погода на маршруте: гололёд, метель, туман, мороз — до выезда.
//  Зеркало Android WeatherWarningCard.kt.
//
//  Если на пути ничего опасного — человек не видит НИЧЕГО: карточки
//  просто нет. Не «всё хорошо», а именно ничего: пустая плашка
//  каждый день перестаёт читаться, и в день гололёда её пролистают
//  вместе с остальным.
//
//  Ни одну кнопку карточка не блокирует: ехать решает человек,
//  приложение лишь говорит факт вовремя. Тексты приходят с сервера
//  готовыми на двух языках — пороги живут в одном месте.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchRouteWeather, type RouteWeather, type RouteWeatherQuery } from "../api/weather";
import { IconWarn, IconSignal } from "../components/Icons";
import { YuSun } from "../components/BrandIcons";

/** Иконка по типу предупреждения: лёд/метель → снежинка, туман/ветер → сигнал, мороз → солнце. */
function WeatherIcon({ kind, size = 18 }: { kind: string; size?: number }) {
  if (kind === "fog" || kind === "wind" || kind === "thunder") return <IconSignal size={size} />;
  if (kind === "frost") return <YuSun size={size} />;
  return <IconWarn size={size} />;
}

/** Пауза перед запросом: город человек печатает буквами, а не целиком. */
const TYPING_PAUSE_MS = 700;

/**
 * Загрузка погоды для маршрута. Держит своё состояние и молчит при любой неудаче:
 * нет сети, сервис недоступен, координат нет — карточка просто не появится.
 *
 * Запрос уходит не раньше, чем человек перестал печатать: в форме публикации поездки
 * маршрут набирают руками, и без паузы на «Сибай» ушло бы пять запросов подряд — по
 * одному на букву, причём четыре из них по несуществующим городам.
 */
export function useRouteWeather(q: RouteWeatherQuery, enabled = true): RouteWeather | null {
  const [data, setData] = useState<RouteWeather | null>(null);
  const key = JSON.stringify(q);

  useEffect(() => {
    if (!enabled) {
      setData(null);
      return;
    }
    const query: RouteWeatherQuery = JSON.parse(key);
    const hasRoute =
      (query.fromLat != null && query.fromLng != null) ||
      (query.toLat != null && query.toLng != null) ||
      Boolean(query.fromCity?.trim()) ||
      Boolean(query.toCity?.trim());
    if (!hasRoute) {
      setData(null);
      return;
    }
    const ac = new AbortController();
    const timer = window.setTimeout(() => {
      fetchRouteWeather(query, ac.signal)
        .then(setData)
        .catch(() => setData(null)); // 404 (эндпоинта нет) / нет сети → молчим
    }, TYPING_PAUSE_MS);
    return () => {
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [key, enabled]);

  return data;
}

export default function WeatherWarningCard({ weather }: { weather: RouteWeather | null }) {
  const { appText, lang } = useLang();
  const warnings = weather?.warnings ?? [];
  if (!weather?.available || warnings.length === 0) return null;

  const severe = warnings.some((w) => w.severe);

  return (
    <div className={"weather-warn" + (severe ? " weather-warn--severe" : "")}>
      <div className="weather-warn__head">
        <span className="weather-warn__ic">
          <WeatherIcon kind={warnings[0].kind} size={20} />
        </span>
        <span className="weather-warn__main">
          <span className="weather-warn__title">
            {appText("Погода на маршруте", "Юлдағы һауа торошо")}
          </span>
          {weather.temperature_c != null && (
            <span className="weather-warn__temp">
              {appText(
                `Сейчас ${Math.round(weather.temperature_c)}°`,
                `Хәҙер ${Math.round(weather.temperature_c)}°`
              )}
            </span>
          )}
        </span>
      </div>

      {warnings.map((w, i) => (
        <div
          key={`${w.kind}-${i}`}
          className={"weather-warn__row" + (w.severe ? " weather-warn__row--severe" : "")}
        >
          <WeatherIcon kind={w.kind} />
          <span>{lang === "ba" ? w.ba : w.ru}</span>
        </div>
      ))}

      <p className="weather-warn__foot">
        {appText(
          "Это предупреждение, а не запрет — решай сам.",
          "Был иҫкәртеү, тыйыу түгел — үҙең хәл ит."
        )}
      </p>
    </div>
  );
}
