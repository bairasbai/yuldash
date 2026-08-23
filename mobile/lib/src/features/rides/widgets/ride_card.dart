import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/ride.dart';

class RideCard extends StatelessWidget {
  const RideCard({
    super.key,
    required this.ride,
    this.compact = false,
    this.onBook,
  });

  final Ride ride;
  final bool compact;
  final VoidCallback? onBook;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${ride.from} → ${ride.to}',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                  ),
                ),
                if (ride.isBoosted)
                  Chip(
                    label: const Text('Вверху'),
                    visualDensity: VisualDensity.compact,
                    avatar: Icon(Icons.trending_up, size: 16, color: Theme.of(context).colorScheme.primary),
                  ),
              ],
            ),
            const SizedBox(height: 6),
            Text(ride.departureLabel),
            if (ride.pickup.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                ride.pickup,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 10),
            Row(
              children: [
                CircleAvatar(
                  child: Text(ride.driverName.isEmpty ? '?' : ride.driverName[0]),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(child: Text(ride.driverName, style: const TextStyle(fontWeight: FontWeight.w700))),
                          if (ride.isVerified) ...[
                            const SizedBox(width: 4),
                            const Icon(Icons.verified, size: 16, color: Color(0xFF1D7A46)),
                          ],
                        ],
                      ),
                      Text(ride.car, maxLines: 1, overflow: TextOverflow.ellipsis),
                    ],
                  ),
                ),
                Text('★ ${ride.rating.toStringAsFixed(1)}'),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                _Metric(icon: Icons.payments, text: '${ride.priceRub} ₽'),
                const SizedBox(width: 12),
                _Metric(icon: Icons.event_seat, text: '${ride.freeSeats} места'),
              ],
            ),
            if (!compact) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: onBook,
                      icon: const Icon(Icons.lock_open),
                      label: const Text('Забронировать'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  IconButton.outlined(
                    tooltip: 'Поделиться',
                    onPressed: () => _copyShareText(context),
                    icon: const Icon(Icons.ios_share),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _copyShareText(BuildContext context) async {
    final text = '${ride.from} → ${ride.to}, ${ride.departureLabel}. '
        '${ride.priceRub} ₽, свободных мест: ${ride.freeSeats}. Юлдаш: https://yulbash.ru';
    await Clipboard.setData(ClipboardData(text: text));
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Текст поездки скопирован.')),
      );
    }
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18),
        const SizedBox(width: 5),
        Text(text, style: const TextStyle(fontWeight: FontWeight.w700)),
      ],
    );
  }
}
