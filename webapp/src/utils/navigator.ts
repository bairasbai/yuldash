// ================================================================
//  Внешний навигатор — зеркало Android openNavigator(ctx, lat, lng):
//  там Яндекс Навигатор → Яндекс Карты → любое geo:-приложение. В вебе одна
//  ссылка на маршрут в Яндекс Картах: на телефоне она открывает приложение,
//  на компьютере — сайт. Точка «откуда» не передаём — навигатор возьмёт GPS.
// ================================================================
export function navigatorUrl(lat: number, lng: number): string {
  return `https://yandex.ru/maps/?rtext=~${lat.toFixed(6)},${lng.toFixed(6)}&rtt=auto`;
}

export function openNavigator(lat: number | null | undefined, lng: number | null | undefined): void {
  if (lat == null || lng == null || (lat === 0 && lng === 0)) return;
  try {
    window.open(navigatorUrl(lat, lng), "_blank", "noopener");
  } catch {
    /* всплывашка заблокирована — кнопка остаётся, водитель нажмёт ещё раз */
  }
}
