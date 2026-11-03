import type { TripReceipt } from '../api/bookings';
import { dayMonthLong, hhmm, priceLabel, payMethodLabel } from './format';
import { serverDate } from './serverTime';

// Explicit whitelist: never spread a server response into an export.
export function receiptPdfContent(receipt: TripReceipt, ru: boolean) {
  const departure = serverDate(receipt.depart_at);
  if (!departure) throw new Error('Invalid receipt date');
  const offset = -departure.getTimezoneOffset();
  const zone = `UTC${offset < 0 ? '-' : '+'}${String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0')}:${String(Math.abs(offset) % 60).padStart(2, '0')}`;
  return {
    title: ru ? 'Юлдаш · Квитанция поездки' : 'Юлдаш · Сәфәр квитанцияһы',
    body: [
      `${receipt.from_city} - ${receipt.to_city}`,
      `${dayMonthLong(departure, ru)} ${departure.getFullYear()}, ${hhmm(departure)} (${zone})`,
      `${ru ? 'Мест' : 'Урын'}: ${receipt.seats}`,
      `${ru ? 'Способ оплаты' : 'Түләү ысулы'}: ${payMethodLabel(receipt.pay_method, ru)}`,
      `${ru ? 'Статус оплаты' : 'Түләү хәле'}: ${receipt.paid ? (ru ? 'Оплачено' : 'Түләнгән') : (ru ? 'По договорённости' : 'Килешеү буйынса')}`,
      `${ru ? 'Итого' : 'Барлығы'}: ${priceLabel(receipt.amount, ru)}`,
    ].join('\n\n'),
  };
}
