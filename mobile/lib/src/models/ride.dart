class Ride {
  const Ride({
    required this.id,
    required this.from,
    required this.to,
    required this.departureLabel,
    required this.driverName,
    required this.car,
    required this.priceRub,
    required this.freeSeats,
    required this.rating,
    required this.isVerified,
    required this.isBoosted,
  });

  final String id;
  final String from;
  final String to;
  final String departureLabel;
  final String driverName;
  final String car;
  final int priceRub;
  final int freeSeats;
  final double rating;
  final bool isVerified;
  final bool isBoosted;
}

