import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../data/api_client.dart';
import '../../data/app_scope.dart';

class CreateRidePage extends StatefulWidget {
  const CreateRidePage({super.key});

  @override
  State<CreateRidePage> createState() => _CreateRidePageState();
}

class _CreateRidePageState extends State<CreateRidePage> {
  final _formKey = GlobalKey<FormState>();
  final _fromController = TextEditingController();
  final _toController = TextEditingController();
  final _seatsController = TextEditingController(text: '1');
  final _priceController = TextEditingController(text: '0');
  final _commentController = TextEditingController();

  DateTime _departAt = DateTime.now().add(const Duration(hours: 2));
  bool _submitting = false;

  @override
  void dispose() {
    _fromController.dispose();
    _toController.dispose();
    _seatsController.dispose();
    _priceController.dispose();
    _commentController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Создать поездку')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (!state.isLoggedIn) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Нужен вход',
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
                      ),
                      const SizedBox(height: 6),
                      const Text('Чтобы опубликовать поездку, войдите по телефону.'),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: () => context.push('/auth'),
                        child: const Text('Войти'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],
            TextFormField(
              controller: _fromController,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(labelText: 'Откуда'),
              validator: _required,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _toController,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(labelText: 'Куда'),
              validator: _required,
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _pickDateTime,
              icon: const Icon(Icons.calendar_month),
              label: Text(_formatDateTime(_departAt)),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: _seatsController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Мест'),
                    validator: (value) => _positiveInt(value, min: 1, max: 8),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextFormField(
                    controller: _priceController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Цена, ₽'),
                    validator: (value) => _positiveInt(value, min: 0, max: 100000),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _commentController,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: 'Комментарий',
                hintText: 'Например: могу взять посылку, заеду через Темясово',
              ),
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Платное поднятие',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 6),
                    const Text('После публикации поднятие оплачивается переводом по СБП.'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: state.isLoggedIn && !_submitting ? _submit : null,
              icon: _submitting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.check),
              label: const Text('Опубликовать'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickDateTime() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _departAt,
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 180)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(_departAt),
    );
    if (time == null) return;
    setState(() {
      _departAt = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _submitting = true);
    try {
      final state = AppScope.of(context);
      final rideId = await state.createRide(
        fromCity: _fromController.text.trim(),
        toCity: _toController.text.trim(),
        departAt: _departAt,
        seats: int.parse(_seatsController.text.trim()),
        price: int.parse(_priceController.text.trim()),
        comment: _commentController.text.trim(),
      );
      if (!mounted) return;
      final boost = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Поездка опубликована'),
          content: const Text('Можно поднять поездку в списке. Оплата сейчас работает переводом по СБП.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Закрыть'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Поднять'),
            ),
          ],
        ),
      );
      if (boost == true && mounted && rideId.isNotEmpty) {
        await showModalBottomSheet<void>(
          context: context,
          isScrollControlled: true,
          builder: (_) => _BoostSheet(rideId: rideId),
        );
      }
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}

String? _required(String? value) {
  if (value == null || value.trim().isEmpty) return 'Заполните поле';
  return null;
}

String? _positiveInt(String? value, {required int min, required int max}) {
  final parsed = int.tryParse(value?.trim() ?? '');
  if (parsed == null || parsed < min || parsed > max) return 'От $min до $max';
  return null;
}

String _formatDateTime(DateTime value) {
  final day = value.day.toString().padLeft(2, '0');
  final month = value.month.toString().padLeft(2, '0');
  final hour = value.hour.toString().padLeft(2, '0');
  final minute = value.minute.toString().padLeft(2, '0');
  return '$day.$month, $hour:$minute';
}

class _BoostSheet extends StatefulWidget {
  const _BoostSheet({required this.rideId});

  final String rideId;

  @override
  State<_BoostSheet> createState() => _BoostSheetState();
}

class _BoostSheetState extends State<_BoostSheet> {
  Future<List<BoostPlan>>? _plansFuture;
  BoostResult? _result;
  bool _creating = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _plansFuture ??= AppScope.of(context).getBoostPlans();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          16,
          16,
          16,
          16 + MediaQuery.of(context).viewInsets.bottom,
        ),
        child: _result == null ? _buildPlans(context) : _buildPayment(context, _result!),
      ),
    );
  }

  Widget _buildPlans(BuildContext context) {
    return FutureBuilder<List<BoostPlan>>(
      future: _plansFuture,
      builder: (context, snapshot) {
        final plans = snapshot.data ?? const <BoostPlan>[];
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Поднять поездку',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 8),
            const Text('Выберите тариф. Backend вернёт реквизиты СБП для ручного перевода.'),
            const SizedBox(height: 14),
            if (snapshot.connectionState == ConnectionState.waiting)
              const Center(child: CircularProgressIndicator())
            else if (snapshot.hasError)
              Text(snapshot.error.toString())
            else
              for (final plan in plans)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: ListTile(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                      side: BorderSide(color: Theme.of(context).dividerColor),
                    ),
                    title: Text(plan.title),
                    subtitle: Text('${plan.hours} ч'),
                    trailing: Text('${plan.price} ₽'),
                    onTap: _creating ? null : () => _createBoost(plan),
                  ),
                ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Не сейчас'),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildPayment(BuildContext context, BoostResult result) {
    final phone = result.payeePhone ?? '';
    final bank = result.payeeBank ?? '';
    final name = result.payeeName ?? '';
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Оплата по СБП',
          style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        const Text('Откройте банк и переведите сумму по номеру телефона. Поднятие подтвердит администратор.'),
        const SizedBox(height: 14),
        _PaymentLine(label: 'Сумма', value: '${result.amount} ₽'),
        _PaymentLine(label: 'Номер', value: phone),
        if (bank.isNotEmpty) _PaymentLine(label: 'Банк', value: bank),
        if (name.isNotEmpty) _PaymentLine(label: 'Получатель', value: name),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: phone.isEmpty
                ? null
                : () async {
                    await Clipboard.setData(ClipboardData(text: phone));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Номер СБП скопирован.')),
                      );
                    }
                  },
            icon: const Icon(Icons.copy),
            label: const Text('Скопировать номер'),
          ),
        ),
        const SizedBox(height: 10),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Готово'),
          ),
        ),
      ],
    );
  }

  Future<void> _createBoost(BoostPlan plan) async {
    setState(() => _creating = true);
    try {
      final result = await AppScope.of(context).createBoost(rideId: widget.rideId, tier: plan.tier);
      if (mounted) setState(() => _result = result);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _creating = false);
    }
  }
}

class _PaymentLine extends StatelessWidget {
  const _PaymentLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          SizedBox(width: 100, child: Text(label, style: Theme.of(context).textTheme.bodySmall)),
          Expanded(child: Text(value, style: const TextStyle(fontWeight: FontWeight.w800))),
        ],
      ),
    );
  }
}
