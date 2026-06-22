import 'package:flutter/material.dart';

class SupportPage extends StatelessWidget {
  const SupportPage({super.key});

  @override
  Widget build(BuildContext context) {
    const amounts = [10, 30, 50, 100];

    return Scaffold(
      appBar: AppBar(title: const Text('Поддержать Юлдаш')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            'Добровольная поддержка',
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          const Text('Помогает оплачивать серверы, карты, SMS и поддержку приложения.'),
          const SizedBox(height: 18),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              for (final amount in amounts)
                ChoiceChip(
                  selected: amount == 30,
                  label: Text('$amount ₽'),
                  onSelected: (_) {},
                ),
              ChoiceChip(
                selected: false,
                label: const Text('Своя сумма'),
                onSelected: (_) {},
              ),
            ],
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            onPressed: () {},
            icon: const Icon(Icons.payments),
            label: const Text('Поддержать'),
          ),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Не сейчас'),
          ),
        ],
      ),
    );
  }
}

