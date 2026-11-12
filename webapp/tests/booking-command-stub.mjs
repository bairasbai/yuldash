// Existing screen checks exercise form and navigation, with the command adapter replaced at its boundary.
export function bookingCommandStub(createBooking) {
  return createBooking + `
    export class BookingCommandProblem extends Error {
      constructor(kind, command) { super(kind); this.kind=kind; this.command=command; }
    }
    export const bookingCommandOwner = () => null;
    export const readBookingCommand = async () => undefined;
    export const reconcileBookingCommand = async () => { throw new Error("Unexpected command reconciliation"); };
  `;
}

export const passengerDecisionStoreStub = `
  export const bookingCommandOwner = (user, generation) => user ? {userId:user.id,generation,namespace:String(user.id)} : null;
  export const isCurrentBookingOwner = () => true;
  export const readDriverDecisions = async () => [];
`;

export function passengerDecisionStub(submit) {
  return `
    export class BookingDecisionProblem extends Error { constructor(kind) { super(kind); this.kind=kind; } }
    export const preparePassengerCancellation = async (owner,bookingId,status) =>
      ({owner,bookingId,status,consequence:"early",termsKey:"a".repeat(64)});
    export const checkDriverDecision = async () => { throw new Error("Unexpected cancellation reconciliation"); };
    export const repeatDriverDecision = checkDriverDecision;
    const submit = ${submit};
    export const cancelPassengerBooking = async (owner,terms,reason) => {
      try { return {state:"applied",booking:await submit(terms.bookingId,reason,terms.termsKey)}; }
      catch { throw new BookingDecisionProblem("pending"); }
    };
  `;
}
