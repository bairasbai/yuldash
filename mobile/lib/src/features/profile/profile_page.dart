import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../localization/app_locale_controller.dart';

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Профиль')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: const [
                  CircleAvatar(radius: 28, child: Text('Б')),
                  SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Байрас', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
                        SizedBox(height: 4),
                        Text('Телефон скрывается до подтверждения брони'),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
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
