// Synthetic stand accounts (Telegram ids and phones are fake; never real people).
export const ACC = {
  pwaDriver: { telegramId: 9300101, firstName: "Ильдар", phone: "+79990301101", profile: "pwa-d1", role: "driver" },
  pwaPassenger: { telegramId: 9300102, firstName: "Гульназ", phone: "+79990301102", profile: "pwa-p1", role: "passenger" },
  andPassenger: { telegramId: 9300103, firstName: "Айгуль", phone: "+79990301103" },
  andDriver: { telegramId: 9300104, firstName: "Рустам", phone: "+79990301104" },
  pwaPassenger2: { telegramId: 9300105, firstName: "Зарина", phone: "+79990301105", profile: "pwa-p2", role: "passenger" },
  pwaStranger: { telegramId: 9300106, firstName: "Чужой", phone: "+79990301106", profile: "pwa-x1", role: "passenger" },
};
export const userId = (q, acc) => q("SELECT id FROM user WHERE telegram_id = ?", String(acc.telegramId))[0]?.id;
