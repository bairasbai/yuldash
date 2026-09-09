/** Ставка именно этого заказа; отсутствие поля не означает нулевую комиссию. */
export function commissionLabel(percent: number | null | undefined): { ru: string; ba: string } {
  if (percent == null || !Number.isFinite(percent) || percent < 0) {
    return { ru: "Комиссия по поездке — в чеке.", ba: "Сәфәр өсөн комиссия — чекта." };
  }
  return {
    ru: `Комиссия по этой поездке — ${percent}%. Начисленная сумма — в чеке.`,
    ba: `Был сәфәр өсөн комиссия — ${percent}%. Иҫәпләнгән сумма — чекта.`,
  };
}
