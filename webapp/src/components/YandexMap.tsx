// ================================================================
//  Переиспользуемая карта Юлдаша на Яндекс Картах (JS API 2.1).
//  Грузит скрипт по ключу VITE_YANDEX_MAPS_JS_KEY (один раз на страницу).
//  Рисует: маршрут A→B (зелёная линия), точку назначения (золотая),
//  «моё место» (зелёная), произвольные маркеры (заявки/поездки на витрине).
//
//  Без ключа — аккуратный брендовый плейсхолдер «Карта подключится с ключом».
//  Экран НЕ ломается: карта всегда деградирует мягко (нет ключа / нет сети / ошибка).
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { IconPin } from "./Icons";

const YMAPS_KEY = (import.meta.env.VITE_YANDEX_MAPS_JS_KEY ?? "").trim();

// Бренд Canon (совпадает с index.css --canon-*). Карта — единственное место,
// где цвета задаём напрямую: Яндекс рисует на canvas, CSS-токены туда не доходят.
const GREEN = "#0b6b3a";
const GOLD = "#f5b301";

export interface GeoPoint {
  lat: number;
  lng: number;
}

export interface MapMarker extends GeoPoint {
  id: string | number;
  /** regular — обычная заявка/поездка, urgent — срочно, hospital — клиника, me — я. */
  kind?: "regular" | "urgent" | "hospital" | "me" | "destination";
  label?: string;
  onClick?: () => void;
}

interface YandexMapProps {
  /** Точка А (откуда) — рисуется как «моё место»/старт. */
  from?: GeoPoint | null;
  /** Точка Б (куда) — золотой маркер назначения. */
  to?: GeoPoint | null;
  /** Нарисовать зелёную линию A→B (прямую, без роутера — экономим квоту). */
  route?: boolean;
  /** Моё текущее место (зелёная точка), если отличается от from. */
  me?: GeoPoint | null;
  /** Свободные маркеры (витрина: ближайшие заявки/поездки). */
  markers?: MapMarker[];
  /** Высота карты (по умолчанию на всю доступную площадь родителя). */
  height?: number | string;
  /** Центр по умолчанию, если нет точек. Уфа. */
  center?: GeoPoint;
  zoom?: number;
  className?: string;
}

// ---- Одноразовая загрузка скрипта Яндекс.Карт ----
let ymapsPromise: Promise<any> | null = null;
function loadYmaps(): Promise<any> {
  if (!YMAPS_KEY) return Promise.reject(new Error("no-key"));
  const w = window as any;
  if (w.ymaps && w.ymaps.Map) return Promise.resolve(w.ymaps);
  if (ymapsPromise) return ymapsPromise;

  ymapsPromise = new Promise((resolve, reject) => {
    const existing = document.getElementById("ymaps-script") as HTMLScriptElement | null;
    const s = existing ?? document.createElement("script");
    let settled = false;
    const timer = window.setTimeout(() => fail(), 15000);
    const fail = () => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      s.remove();
      reject(new Error("ymaps-load"));
    };
    const onReady = () => {
      if (w.ymaps?.ready) w.ymaps.ready(() => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timer);
        resolve(w.ymaps);
      });
      else fail();
    };
    if (existing) {
      existing.addEventListener("load", onReady);
      existing.addEventListener("error", fail);
      // Скрипт мог уже загрузиться до навешивания слушателя.
      if (w.ymaps) onReady();
      return;
    }
    s.id = "ymaps-script";
    s.async = true;
    s.src = `https://api-maps.yandex.ru/2.1/?apikey=${encodeURIComponent(
      YMAPS_KEY
    )}&lang=ru_RU`;
    s.onload = onReady;
    s.onerror = fail;
    document.head.appendChild(s);
  }).catch((error) => {
    ymapsPromise = null;
    throw error;
  });
  return ymapsPromise;
}

export const YANDEX_MAPS_ENABLED = YMAPS_KEY.length > 0;

export default function YandexMap({
  from,
  to,
  route,
  me,
  markers,
  height = "100%",
  center = { lat: 54.7388, lng: 55.9721 }, // Уфа
  zoom = 11,
  className,
}: YandexMapProps) {
  const { appText } = useLang();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<"idle" | "ready" | "nokey" | "error">(
    YANDEX_MAPS_ENABLED ? "idle" : "nokey"
  );

  // Инициализация карты один раз.
  useEffect(() => {
    if (!YANDEX_MAPS_ENABLED) return;
    setStatus("idle");
    let cancelled = false;
    loadYmaps()
      .then((ymaps) => {
        if (cancelled || !boxRef.current || mapRef.current) return;
        const c = from ?? me ?? to ?? center;
        mapRef.current = new ymaps.Map(
          boxRef.current,
          {
            center: [c.lat, c.lng],
            zoom,
            controls: ["zoomControl", "geolocationControl"],
          },
          { suppressMapOpenBlock: true, yandexMapDisablePoiInteractivity: true }
        );
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });
    return () => {
      cancelled = true;
      if (mapRef.current) {
        try {
          mapRef.current.destroy();
        } catch {
          /* карта уже уничтожена */
        }
        mapRef.current = null;
      }
    };
    // Инициализация одноразовая; данные обновляем в отдельном эффекте.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  // Перерисовка объектов (маршрут, точки, маркеры) при смене данных.
  useEffect(() => {
    const map = mapRef.current;
    const w = window as any;
    if (!map || !w.ymaps) return;
    const ymaps = w.ymaps;
    map.geoObjects.removeAll();

    const bounds: [number, number][] = [];

    const dot = (
      p: GeoPoint,
      color: string,
      label?: string,
      onClick?: () => void
    ) => {
      const pm = new ymaps.Placemark(
        [p.lat, p.lng],
        { balloonContent: label, hintContent: label },
        {
          preset: "islands#circleIcon",
          iconColor: color,
        }
      );
      if (onClick) pm.events.add("click", onClick);
      map.geoObjects.add(pm);
      bounds.push([p.lat, p.lng]);
    };

    if (route && from && to) {
      const line = new ymaps.Polyline(
        [
          [from.lat, from.lng],
          [to.lat, to.lng],
        ],
        {},
        { strokeColor: GREEN, strokeWidth: 4, strokeOpacity: 0.9 }
      );
      map.geoObjects.add(line);
    }

    if (from) dot(from, GREEN, undefined);
    if (me) dot(me, GREEN);
    if (to) dot(to, GOLD);

    (markers ?? []).forEach((mk) => {
      const color = mk.kind === "urgent" ? GOLD : mk.kind === "me" ? GREEN : GREEN;
      dot(mk, color, mk.label, mk.onClick);
    });

    if (bounds.length >= 2) {
      try {
        map.setBounds(
          [
            [
              Math.min(...bounds.map((b) => b[0])),
              Math.min(...bounds.map((b) => b[1])),
            ],
            [
              Math.max(...bounds.map((b) => b[0])),
              Math.max(...bounds.map((b) => b[1])),
            ],
          ],
          { checkZoomRange: true, zoomMargin: 48 }
        );
      } catch {
        /* setBounds может ругаться на вырожденный прямоугольник — игнор */
      }
    } else if (bounds.length === 1) {
      map.setCenter(bounds[0], Math.max(zoom, 13));
    }
  }, [status, from, to, route, me, markers, zoom]);

  const heightStyle = typeof height === "number" ? `${height}px` : height;

  if (status === "nokey" || status === "error") {
    return (
      <div className={"map-placeholder" + (className ? ` ${className}` : "")}
        style={{ height: heightStyle }}>
        <div className="map-placeholder__glow" aria-hidden />
        <div className="map-placeholder__pin" aria-hidden><IconPin size={28} /></div>
        <div className="map-placeholder__title">
          {status === "error"
            ? appText("Карта временно недоступна", "Карта ваҡытлыса юҡ") /* DRAFT */
            : appText("Карта подключится с ключом", "Карта асҡыс менән тоташа") /* DRAFT */}
        </div>
        <div className="map-placeholder__sub">
          {status === "error"
            ? appText("Проверь связь — попробуем снова.", "Бәйләнеште тикшер — ҡабат ҡарайбыҙ.") /* DRAFT */
            : appText(
                "Маршрут и точки появятся, как только добавим ключ карт.",
                "Маршрут һәм нөктәләр карта асҡысы ҡушылғас күренәсәк."
              ) /* DRAFT */}
        </div>
        {status === "error" && (
          <button type="button" className="btn-soft" onClick={() => { setStatus("idle"); setAttempt((n) => n + 1); }}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        )}
      </div>
    );
  }

  return (
    <div
      className={"map-box" + (className ? ` ${className}` : "")}
      style={{ height: heightStyle }}
    >
      <div ref={boxRef} className="map-box__canvas" />
      {status === "idle" && (
        <div className="map-box__loading" role="status" aria-live="polite">
          <div className="spinner" aria-hidden />
        </div>
      )}
    </div>
  );
}
