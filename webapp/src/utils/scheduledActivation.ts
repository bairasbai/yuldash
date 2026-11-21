/** A retained scheduled order is not an activation. Expired preserves the existing no-driver flow. */
export function canOpenScheduledActivation(requestedId: number, returnedId: number, status: string): boolean {
  return requestedId > 0 && returnedId === requestedId && [
    "created", "searching", "offered", "accepted", "arriving", "onboard", "expired",
  ].includes(status);
}
