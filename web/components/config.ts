// Ссылка на APK. Файл кладётся в web/public/yuldash.apk
// → после статик-экспорта доступен как https://yulbash.ru/yuldash.apk
export const APK_URL = "/yuldash.apk";

// Приложение ещё не вышло. Пока false — кнопки «Скачать» открывают модалку
// «скоро» (ловим спрос через Telegram). Положишь APK → поставь true → реальное скачивание.
export const APP_READY = false;

// Размер для подписи под кнопкой (поправь под реальный вес сборки)
export const APK_SIZE = "~12 МБ";

// Базовый URL сайта (для OG/robots/sitemap). Поменяй, если лендинг на поддомене.
export const SITE_URL = "https://yulbash.ru";

// Соцсети и контакты.
export const SOCIAL = {
  telegram: "https://t.me/bairas_ntv",
  vk: "https://vk.com/bairas_ntv",
};

// Базовые цены рекламных тарифов (₽/мес). Числа — пересчёт со скидкой за период на фронте.
export const PARTNER_PRICES = {
  founder: 1990,
  standard: 4900,
  premium: 9900,
};

// Стартовый оффер для первых партнёров (баннер над тарифами). false = скрыть.
export const PARTNER_INTRO_OFFER = true;

// Периоды размещения и скидка за предоплату (оплата вперёд по СБП).
// Минимум — 1 месяц. Длиннее период = дешевле в месяц + деньги вперёд.
export const PARTNER_PERIODS: { months: number; off: number }[] = [
  { months: 1, off: 0 },
  { months: 3, off: 0.1 },
  { months: 6, off: 0.15 },
  { months: 12, off: 0.2 },
];

// Юр.документы (реальные страницы лендинга).
export const LEGAL = {
  privacy: "/privacy",
  terms: "/terms",
};

// Яндекс.Метрика — вставь ID счётчика (число), и аналитика включится сама.
// Пусто = метрика отключена (на dev/без ID скрипт не грузится).
export const METRIKA_ID = "110209427";

// Живая статистика с сервера (растёт по мере РЕАЛЬНОГО пользования).
// Бэкенд должен отдать JSON: [{ "value": "1 200+", "ru": "...", "ba": "..." }, ...]
// (или { "items": [...] }). Числа форматируй на сервере; фронт сам сделает count-up.
// Пусто → используется LIVE_STATS ниже, а если и он пуст — честные ценностные метрики.
// Пример контракта: GET https://yulbash.ru/api/landing-stats
export const STATS_API = "";

// Живые отзысы о приложении с сервера (бэкенд-эндпоинт /reviews/public — только одобренные).
// Отдаёт JSON-массив: [{ "name": "...", "city": "...", "stars": 5, "text": "..." }, ...]
// Пусто → используется LIVE_TESTIMONIALS ниже; пусто и там → честная заглушка «скоро».
// API на том же домене (nginx: статика + fallback на FastAPI) → относительный путь.
export const TESTIMONIALS_API = "/reviews/public";

// Ручная статистика (если не хочешь эндпоинт — впиши руками после запуска).
// Приоритет: STATS_API > LIVE_STATS > ценностные метрики.
export const LIVE_STATS: { value: string; ru: string; ba: string }[] = [
  // { value: "5 000+", ru: "скачиваний", ba: "йөкләү" },
  // { value: "1 200+", ru: "поездок в месяц", ba: "айына юл" },
  // { value: "300+", ru: "поездок в день", ba: "көнөнә юл" },
  // { value: "4.9 ★", ru: "средний рейтинг", ba: "уртаса рейтинг" },
];

// Реальные отзывы. Пусто = честная заглушка «мы только запускаемся» (без фейк-звёзд).
// После запуска впиши настоящие: имя, город (по желанию), текст RU+BA, число звёзд.
// Никаких выдуманных отзывов — доверие = продукт.
export type Testimonial = {
  quoteRu: string;
  quoteBa: string;
  name: string;
  cityRu?: string;
  cityBa?: string;
  stars?: number; // 1..5
};
export const LIVE_TESTIMONIALS: Testimonial[] = [
  // {
  //   quoteRu: "Доехала до Уфы с соседкой — дешевле автобуса и спокойнее.",
  //   quoteBa: "Күршем менән Өфөгә барып еттем — автобустан арзаныраҡ.",
  //   name: "Гульназ", cityRu: "Сибай", cityBa: "Сибай", stars: 5,
  // },
];

