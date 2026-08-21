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
    this.pickup = '',
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
  final String pickup;

  factory Ride.fromJson(Map<String, dynamic> json) {
    return Ride(
      id: '${json['id'] ?? ''}',
      from: '${json['from_city'] ?? ''}',
      to: '${json['to_city'] ?? ''}',
      departureLabel: _formatDeparture('${json['depart_at'] ?? ''}'),
      driverName: '${json['driver_name'] ?? 'Водитель'}',
      car: '${json['driver_car'] ?? ''}',
      priceRub: _asInt(json['price']),
      freeSeats: _asInt(json['seats_left']),
      rating: _asDouble(json['driver_rating'], fallback: 5),
      isVerified: json['driver_verified'] == true,
      isBoosted: json['boosted'] == true,
      pickup: '${json['pickup'] ?? ''}',
    );
  }
}

int _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse('$value') ?? 0;
}

double _asDouble(Object? value, {double fallback = 0}) {
  if (value is double) return value;
  if (value is num) return value.toDouble();
  return double.tryParse('$value') ?? fallback;
}

String _formatDeparture(String raw) {
  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw;
  final local = parsed.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  return '$day.$month, $hour:$minute';
}
