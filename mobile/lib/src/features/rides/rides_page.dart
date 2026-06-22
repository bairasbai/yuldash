import 'package:flutter/material.dart';

import '../../data/mock_rides.dart';
import 'widgets/ride_card.dart';

class RidesPage extends StatelessWidget {
  const RidesPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Поездки')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const _Filters(),
          const SizedBox(height: 16),
          ...mockRides.map(
            (ride) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: RideCard(ride: ride),
            ),
          ),
        ],
      ),
    );
  }
}

class _Filters extends StatelessWidget {
  const _Filters();

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Row(
              children: const [
                Expanded(child: TextField(decoration: InputDecoration(labelText: 'Откуда'))),
                SizedBox(width: 10),
                Expanded(child: TextField(decoration: InputDecoration(labelText: 'Куда'))),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () {},
                    icon: const Icon(Icons.calendar_month),
                    label: const Text('Дата'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () {},
                    icon: const Icon(Icons.airline_seat_recline_normal),
                    label: const Text('Места'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

