import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SupportPage extends StatefulWidget {
  const SupportPage({super.key});

  @override
  State<SupportPage> createState() => _SupportPageState();
}

class _SupportPageState extends State<SupportPage> {
  static const _phone = '+79991348275';
  static const _phoneDisplay = '+7 (999) 134-82-75';
  static const _bank = 'Сбербанк';
  static const _name = 'Байрас Байбулов';

  final _amounts = const [10, 30, 50, 100];
  final _customAmountController = TextEditingController();
  int _amount = 30;

  @override
  void dispose() {
    _customAmountController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
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
          const Text('Перевод по СБП помогает оплачивать серверы, карты, SMS и поддержку приложения.'),
          const SizedBox(height: 18),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              for (final amount in _amounts)
                ChoiceChip(
                  selected: amount == _amount,
                  label: Text('$amount ₽'),
                  onSelected: (_) => setState(() => _amount = amount),
                ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _customAmountController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Своя сумма',
              suffixText: '₽',
            ),
            onChanged: (value) {
              final parsed = int.tryParse(value.trim());
              if (parsed != null && parsed > 0) setState(() => _amount = parsed);
            },
          ),
          const SizedBox(height: 18),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Реквизиты СБП',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
                  ),
                  const SizedBox(height: 12),
                  _PaymentRow(label: 'Номер', value: _phoneDisplay),
                  _PaymentRow(label: 'Банк', value: _bank),
                  _PaymentRow(label: 'Получатель', value: _name),
                  _PaymentRow(label: 'Сумма', value: '$_amount ₽'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
          FilledButton.icon(
            onPressed: _copyPayment,
            icon: const Icon(Icons.copy),
            label: const Text('Скопировать номер'),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: () => Navigator.of(context).pop(),
            icon: const Icon(Icons.check_circle_outline),
            label: const Text('Я перевёл'),
          ),
          const SizedBox(height: 10),
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Не сейчас'),
          ),
        ],
      ),
    );
  }

  Future<void> _copyPayment() async {
    await Clipboard.setData(const ClipboardData(text: _phone));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Номер для СБП скопирован.')),
    );
  }
}

class _PaymentRow extends StatelessWidget {
  const _PaymentRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          SizedBox(
            width: 100,
            child: Text(label, style: Theme.of(context).textTheme.bodySmall),
          ),
          Expanded(child: Text(value, style: const TextStyle(fontWeight: FontWeight.w700))),
        ],
      ),
    );
  }
}
