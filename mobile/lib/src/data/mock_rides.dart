import '../models/ride.dart';

const mockRides = [
  Ride(
    id: 'ride-1',
    from: 'Баймаҡ',
    to: 'Сибай',
    departureLabel: 'Сегодня, 17:30',
    driverName: 'Ильдар',
    car: 'Lada Vesta, белая',
    priceRub: 250,
    freeSeats: 3,
    rating: 4.8,
    isVerified: true,
    isBoosted: true,
  ),
  Ride(
    id: 'ride-2',
    from: 'Темясово',
    to: 'Уфа',
    departureLabel: 'Завтра, 06:00',
    driverName: 'Айгуль',
    car: 'Hyundai Solaris, серебро',
    priceRub: 1400,
    freeSeats: 2,
    rating: 4.9,
    isVerified: true,
    isBoosted: false,
  ),
  Ride(
    id: 'ride-3',
    from: 'Сибай',
    to: 'Баймак',
    departureLabel: 'Пятница, 13:20',
    driverName: 'Рустам',
    car: 'Renault Logan, синий',
    priceRub: 300,
    freeSeats: 1,
    rating: 4.6,
    isVerified: false,
    isBoosted: false,
  ),
];

