import 'package:flutter/material.dart';

import '../../data/mock_rides.dart';
import '../rides/widgets/ride_card.dart';

class MapPage extends StatelessWidget {
  const MapPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Юлдаш'),
        actions: [
          IconButton(
            tooltip: 'SOS',
            onPressed: () {},
            icon: const Icon(Icons.sos),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
              child: _MapPreview(),
            ),
          ),
          SizedBox(
            height: 188,
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              scrollDirection: Axis.horizontal,
              itemBuilder: (context, index) {
                return SizedBox(
                  width: 320,
                  child: RideCard(ride: mockRides[index], compact: true),
                );
              },
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemCount: mockRides.length,
            ),
          ),
        ],
      ),
    );
  }
}

class _MapPreview extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFDDE8DD),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.black.withOpacity(0.08)),
      ),
      child: Stack(
        children: [
          Positioned.fill(
            child: CustomPaint(painter: _RoutePainter()),
          ),
          const Positioned(
            left: 28,
            top: 44,
            child: _MapPin(label: 'Баймаҡ'),
          ),
          const Positioned(
            right: 42,
            bottom: 86,
            child: _MapPin(label: 'Сибай'),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 16,
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: const [
                    Icon(Icons.visibility_off),
                    SizedBox(width: 10),
                    Expanded(
                      child: Text('Точная геопозиция видна только подтверждённым участникам.'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MapPin extends StatelessWidget {
  const _MapPin({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(8),
            boxShadow: const [BoxShadow(blurRadius: 10, color: Color(0x22000000))],
          ),
          child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        ),
        Icon(Icons.location_on, color: Theme.of(context).colorScheme.primary, size: 34),
      ],
    );
  }
}

class _RoutePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final roadPaint = Paint()
      ..color = Colors.white
      ..strokeWidth = 10
      ..strokeCap = StrokeCap.round;
    final routePaint = Paint()
      ..color = const Color(0xFF1D7A46)
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round;

    final path = Path()
      ..moveTo(size.width * 0.16, size.height * 0.26)
      ..cubicTo(
        size.width * 0.36,
        size.height * 0.18,
        size.width * 0.54,
        size.height * 0.62,
        size.width * 0.84,
        size.height * 0.68,
      );

    canvas.drawPath(path, roadPaint);
    canvas.drawPath(path, routePaint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
