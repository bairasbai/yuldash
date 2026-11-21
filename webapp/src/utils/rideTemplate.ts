import type { ReturnRideTemplate } from "../api/driver";

export const RIDE_DRAFT = "create-ride";

export function returnRideDraft(template: ReturnRideTemplate) {
  return {
    from: template.from_city,
    to: template.to_city,
    when: "",
    seats: template.seats_total,
    price: String(template.price),
    category: template.category,
    amen: {
      baggage: template.baggage,
      child_seat: template.child_seat,
      pets_allowed: template.pets_allowed,
      women_only: template.women_only,
      non_smoking: !template.smoking,
      air_conditioner: template.air_conditioner,
      quiet: template.quiet,
      no_minors: template.no_minors,
    },
    onlyTrusted: template.only_trusted,
    recurrence: "none" as const,
    comment: "",
    pickup: "",
    pickupPointId: null,
    receiverName: "",
    parcelSize: "",
    waypoints: template.waypoints.split("|").map((stop) => stop.trim()).filter(Boolean),
    partnerId: null,
    backMaxTwo: template.back_max_two,
    frontSeatAvailable: template.front_seat_available,
    stopPricesOn: template.stop_prices.length > 0,
    stopPriceValues: template.stop_prices.map(String),
    returnTemplate: { dropped: template.dropped, smoking: template.smoking },
  };
}

export type ReturnRideDraft = ReturnType<typeof returnRideDraft>;
