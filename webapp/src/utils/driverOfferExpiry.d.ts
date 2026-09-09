export class DriverOfferExpiry {
  readonly orderId: number;
  constructor(orderId: number);
  beginAccept(orderId: number): boolean;
  finishAccept(orderId: number, accepted: boolean): boolean;
  expire(orderId: number): boolean;
  manualDecline(orderId: number): boolean;
}
