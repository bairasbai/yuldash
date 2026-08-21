import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../data/app_scope.dart';
import '../../localization/app_locale_controller.dart';

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final name = state.userName?.trim().isNotEmpty == true ? state.userName!.trim() : 'Гость';
    final locale = AppLocaleController.localeOf(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Профиль')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  CircleAvatar(radius: 28, child: Text(name.characters.first.toUpperCase())),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
                        const SizedBox(height: 4),
                        Text(
                          state.isLoggedIn
                              ? 'Телефон скрывается до подтверждения брони'
                              : 'Войдите, чтобы создавать и бронировать поездки',
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          ListTile(
            leading: Icon(state.isLoggedIn ? Icons.logout : Icons.login),
            title: Text(state.isLoggedIn ? 'Выйти' : 'Войти'),
            subtitle: Text(state.isLoggedIn ? 'Завершить сеанс на этом устройстве' : 'Вход по телефону'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              if (state.isLoggedIn) {
                await state.logout();
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Вы вышли из профиля.')),
                  );
                }
              } else {
                context.push('/auth');
              }
            },
          ),
          ListTile(
            leading: const Icon(Icons.refresh),
            title: const Text('Обновить поездки'),
            subtitle: const Text('Получить актуальный список с сервера'),
            onTap: () => state.refreshRides(),
          ),
          ListTile(
            leading: const Icon(Icons.language),
            title: const Text('Язык'),
            subtitle: Text(locale.languageCode == 'ru' ? 'Русский' : 'Башҡортса'),
            trailing: const Icon(Icons.swap_horiz),
            onTap: () => AppLocaleController.toggle(context),
          ),
          ListTile(
            leading: const Icon(Icons.volunteer_activism),
            title: const Text('Поддержать Юлдаш'),
            subtitle: const Text('Добровольно, чтобы покрывать расходы сервиса'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/support'),
          ),
          const ListTile(
            leading: Icon(Icons.verified_user),
            title: Text('Проверка водителя'),
            subtitle: Text('Права, машина, фото авто'),
          ),
          const ListTile(
            leading: Icon(Icons.history),
            title: Text('История поездок'),
          ),
        ],
      ),
    );
  }
}
