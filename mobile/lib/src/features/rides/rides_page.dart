import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../data/app_scope.dart';
import 'widgets/ride_card.dart';

class RidesPage extends StatefulWidget {
  const RidesPage({super.key});

  @override
  State<RidesPage> createState() => _RidesPageState();
}

class _RidesPageState extends State<RidesPage> {
  final _fromController = TextEditingController();
  final _toController = TextEditingController();

  @override
  void dispose() {
    _fromController.dispose();
    _toController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Поездки'),
        actions: [
          IconButton(
            tooltip: 'Обновить',
            onPressed: state.loadingRides ? null : () => state.refreshRides(),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => state.refreshRides(
          fromCity: _fromController.text,
          toCity: _toController.text,
        ),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _Filters(
              fromController: _fromController,
              toController: _toController,
              loading: state.loadingRides,
              onSearch: () => state.refreshRides(
                fromCity: _fromController.text,
                toCity: _toController.text,
              ),
            ),
            const SizedBox(height: 16),
            if (state.loadingRides)
              const Padding(
                padding: EdgeInsets.only(top: 28),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (state.error != null)
              _StateMessage(
                icon: Icons.cloud_off,
                title: 'Не удалось загрузить поездки',
                text: state.error!,
                action: 'Повторить',
                onPressed: () => state.refreshRides(),
              )
            else if (state.rides.isEmpty)
              _StateMessage(
                icon: Icons.route,
                title: 'Поездок пока нет',
                text: 'Создайте первую поездку или измените фильтры.',
                action: 'Создать поездку',
                onPressed: () => context.push('/rides/create'),
              )
            else
              ...state.rides.map(
                (ride) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: RideCard(
                    ride: ride,
                    onBook: () async {
                      if (!state.isLoggedIn) {
                        context.push('/auth');
                        return;
                      }
                      try {
                        await state.bookRide(ride);
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Бронь создана.')),
                          );
                        }
                      } catch (e) {
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(e.toString())),
                          );
                        }
                      }
                    },
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _Filters extends StatelessWidget {
  const _Filters({
    required this.fromController,
    required this.toController,
    required this.loading,
    required this.onSearch,
  });

  final TextEditingController fromController;
  final TextEditingController toController;
  final bool loading;
  final VoidCallback onSearch;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: fromController,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'Откуда'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: toController,
                    textInputAction: TextInputAction.search,
                    onSubmitted: (_) => onSearch(),
                    decoration: const InputDecoration(labelText: 'Куда'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: loading ? null : onSearch,
                icon: const Icon(Icons.search),
                label: const Text('Найти'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StateMessage extends StatelessWidget {
  const _StateMessage({
    required this.icon,
    required this.title,
    required this.text,
    required this.action,
    required this.onPressed,
  });

  final IconData icon;
  final String title;
  final String text;
  final String action;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          children: [
            Icon(icon, size: 38),
            const SizedBox(height: 10),
            Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            Text(text, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onPressed, child: Text(action)),
          ],
        ),
      ),
    );
  }
}
