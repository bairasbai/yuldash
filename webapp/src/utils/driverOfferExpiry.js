/**
 * Один координатор для кнопки «Взять» и таймера одного предложения.
 * Истечение скрывает карточку, но не отменяет ещё не завершившийся accept.
 */
export class DriverOfferExpiry {
  constructor(orderId) {
    this.orderId = orderId;
    this.accepting = false;
    this.expired = false;
    this.declineIssued = false;
    this.settled = false;
  }

  beginAccept(orderId) {
    if (orderId !== this.orderId || this.accepting || this.declineIssued || this.settled) {
      return false;
    }
    this.accepting = true;
    return true;
  }

  /** accepted=true также используется, когда сервер уже закрыл оффер (409/410). */
  finishAccept(orderId, accepted) {
    if (orderId !== this.orderId || this.settled) return false;
    this.accepting = false;
    if (accepted) {
      this.settled = true;
      return false;
    }
    return this.#takeExpiredDismissal();
  }

  expire(orderId) {
    if (orderId !== this.orderId || this.settled) return false;
    this.expired = true;
    return this.#takeExpiredDismissal();
  }

  manualDecline(orderId) {
    if (
      orderId !== this.orderId ||
      this.accepting ||
      this.declineIssued ||
      this.settled
    ) {
      return false;
    }
    this.declineIssued = true;
    return true;
  }

  finishManualDecline(orderId, terminal) {
    if (orderId !== this.orderId || !this.declineIssued || this.settled) return false;
    this.declineIssued = false;
    if (terminal) {
      this.settled = true;
      return false;
    }
    return this.#takeExpiredDismissal();
  }

  #takeExpiredDismissal() {
    if (!this.expired || this.accepting || this.declineIssued) return false;
    this.settled = true;
    return true;
  }
}
