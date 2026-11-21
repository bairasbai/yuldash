/** UI recency, never a financial guarantee: the API can lose its Redis snapshot. */
export type TaxiQuoteStamp = {
  input: string;
  userId: number;
  generation: string;
  wall: number;
  monotonic: number;
  validForMs: number;
};

/** With no server snapshot, recheck the approximate estimate after 30 seconds. */
export function taxiQuoteWindowMs(seconds?: number): number {
  return Number.isFinite(seconds) && seconds! > 0 ? seconds! * 1000 : 30_000;
}

export function taxiQuoteMatches(
  stamp: TaxiQuoteStamp | null,
  input: string,
  owner: { userId: number; generation: string } | null,
): stamp is TaxiQuoteStamp {
  return !!stamp && !!owner && stamp.input === input
    && stamp.userId === owner.userId && stamp.generation === owner.generation;
}

/** Count from request START, include RTT, and fail closed on a clock rollback. */
export function taxiQuoteFresh(stamp: TaxiQuoteStamp, wall: number, monotonic: number): boolean {
  const elapsedWall = wall - stamp.wall;
  const elapsedMono = monotonic - stamp.monotonic;
  return Number.isFinite(elapsedWall) && Number.isFinite(elapsedMono)
    && elapsedWall >= 0 && elapsedMono >= 0
    && Math.max(elapsedWall, elapsedMono) < stamp.validForMs;
}
