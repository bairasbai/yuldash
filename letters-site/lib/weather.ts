/**
 * Погода в двух городах. Open-Meteo — без ключей и регистрации,
 * поэтому в переменных окружения ничего заводить не нужно.
 */

export type WeatherKind =
  | "clear"
  | "mostly"
  | "cloud"
  | "fog"
  | "drizzle"
  | "rain"
  | "snow"
  | "storm";

export type Weather = { temp: number; label: string; kind: WeatherKind } | null;

const PLACES = {
  ufa: { lat: 54.7388, lon: 55.9721 },
  moscow: { lat: 55.7558, lon: 37.6173 },
} as const;

/** Коды WMO — сведены к тому, что человек скажет, посмотрев в окно. */
function describe(code: number): { label: string; kind: WeatherKind } {
  if (code === 0) return { label: "ясно", kind: "clear" };
  if (code <= 2) return { label: "почти ясно", kind: "mostly" };
  if (code === 3) return { label: "пасмурно", kind: "cloud" };
  if (code <= 48) return { label: "туман", kind: "fog" };
  if (code <= 57) return { label: "морось", kind: "drizzle" };
  if (code <= 67) return { label: "дождь", kind: "rain" };
  if (code <= 77) return { label: "снег", kind: "snow" };
  if (code <= 82) return { label: "ливень", kind: "rain" };
  if (code <= 86) return { label: "снегопад", kind: "snow" };
  return { label: "гроза", kind: "storm" };
}

export async function weatherIn(place: keyof typeof PLACES): Promise<Weather> {
  const { lat, lon } = PLACES[place];
  try {
    const res = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,weather_code`,
      // Обновляем раз в полчаса: погода не меняется чаще, чем её смотрят
      { next: { revalidate: 1800 } },
    );
    if (!res.ok) return null;

    const data = (await res.json()) as {
      current?: { temperature_2m?: number; weather_code?: number };
    };
    const temp = data.current?.temperature_2m;
    const code = data.current?.weather_code;
    if (temp == null || code == null) return null;

    return { temp: Math.round(temp), ...describe(code) };
  } catch {
    // Нет сети — страница просто покажет часы без погоды
    return null;
  }
}
