export const IN_TRANSIT_MARKER_TTL_MS = 6 * 60 * 60 * 1000;

/** The emergency hint outlives the trip briefly, without retaining its private fields. */
export function inTransitHelpAvailable(at: number | undefined, now = Date.now()): boolean {
  return at !== undefined && Number.isFinite(at) && at > 0 && at <= now && now - at <= IN_TRANSIT_MARKER_TTL_MS;
}
