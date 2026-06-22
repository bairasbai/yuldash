import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class PhoneLoginPage extends StatelessWidget {
  const PhoneLoginPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const SizedBox(height: 28),
            Text(
              'Юлдаш',
              style: Theme.of(context).textTheme.displaySmall?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
            const SizedBox(height: 8),
            Text(
              'Поездки между своими',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 36),
            Text(
              'Войти по телефону',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
            ),
            const SizedBox(height: 8),
            const Text('Номер будет скрыт до подтверждения брони.'),
            const SizedBox(height: 20),
            const TextField(
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                labelText: 'Номер телефона',
                prefixText: '+7 ',
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => context.go('/'),
              child: const Text('Продолжить'),
            ),
            const SizedBox(height: 24),
            const _TrustNotes(),
          ],
        ),
      ),
    );
  }
}

class _TrustNotes extends StatelessWidget {
  const _TrustNotes();

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: const [
            _TrustRow(icon: Icons.phone_locked, text: 'Телефон скрыт до подтверждения'),
            SizedBox(height: 12),
            _TrustRow(icon: Icons.pin, text: 'Код посадки для безопасности'),
            SizedBox(height: 12),
            _TrustRow(icon: Icons.verified_user, text: 'Проверка водителей через админку'),
          ],
        ),
      ),
    );
  }
}

class _TrustRow extends StatelessWidget {
  const _TrustRow({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 20),
        const SizedBox(width: 10),
        Expanded(child: Text(text)),
      ],
    );
  }
}

