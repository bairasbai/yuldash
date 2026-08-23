import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../data/app_scope.dart';

class PhoneLoginPage extends StatefulWidget {
  const PhoneLoginPage({super.key});

  @override
  State<PhoneLoginPage> createState() => _PhoneLoginPageState();
}

class _PhoneLoginPageState extends State<PhoneLoginPage> {
  final _phoneController = TextEditingController();
  final _nameController = TextEditingController();
  final _codeController = TextEditingController();

  bool _codeRequested = false;
  bool _loading = false;

  @override
  void dispose() {
    _phoneController.dispose();
    _nameController.dispose();
    _codeController.dispose();
    super.dispose();
  }

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
            TextField(
              controller: _phoneController,
              keyboardType: TextInputType.phone,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Номер телефона',
                hintText: '+79991234567',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _nameController,
              textInputAction: _codeRequested ? TextInputAction.next : TextInputAction.done,
              decoration: const InputDecoration(labelText: 'Имя'),
            ),
            if (_codeRequested) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _codeController,
                keyboardType: TextInputType.number,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(labelText: 'Код из SMS'),
              ),
            ],
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _loading ? null : (_codeRequested ? _verify : _requestCode),
              child: Text(_codeRequested ? 'Войти' : 'Получить код'),
            ),
            const SizedBox(height: 24),
            const _TrustNotes(),
          ],
        ),
      ),
    );
  }

  Future<void> _requestCode() async {
    final phone = _phoneController.text.trim();
    final name = _nameController.text.trim();
    if (phone.isEmpty || name.isEmpty) {
      _show('Введите телефон и имя.');
      return;
    }
    setState(() => _loading = true);
    try {
      final devCode = await AppScope.of(context).requestCode(phone);
      if (devCode != null && devCode.isNotEmpty) {
        _codeController.text = devCode;
      }
      setState(() => _codeRequested = true);
      if (mounted) {
        _show(devCode == null || devCode.isEmpty ? 'Код отправлен.' : 'Тестовый код подставлен.');
      }
    } catch (e) {
      _show(e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _verify() async {
    final phone = _phoneController.text.trim();
    final code = _codeController.text.trim();
    final name = _nameController.text.trim();
    if (phone.isEmpty || code.isEmpty || name.isEmpty) {
      _show('Введите телефон, имя и код.');
      return;
    }
    setState(() => _loading = true);
    try {
      await AppScope.of(context).verifyCode(phone: phone, code: code, name: name);
      if (mounted) context.go('/');
    } catch (e) {
      _show(e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _show(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
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
