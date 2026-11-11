// Existing screen checks exercise form and navigation, with the command adapter replaced at its boundary.
export function bookingCommandStub(createBooking) {
  return createBooking + `
    export class BookingCommandProblem extends Error {}
    export const bookingCommandOwner = () => null;
    export const readBookingCommand = async () => undefined;
    export const reconcileBookingCommand = async () => { throw new Error("Unexpected command reconciliation"); };
  `;
}
