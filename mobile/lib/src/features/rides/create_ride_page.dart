import 'package:flutter/material.dart';

class CreateRidePage extends StatelessWidget {
  const CreateRidePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Создать поездку')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const TextField(decoration: InputDecoration(labelText: 'Откуда')),
          const SizedBox(height: 12),
          const TextField(decoration: InputDecoration(labelText: 'Куда')),
          const SizedBox(height: 12),
          const TextField(decoration: InputDecoration(labelText: 'Дата и время')),
          const SizedBox(height: 12),
          Row(
            children: const [
              Expanded(child: TextField(decoration: InputDecoration(labelText: 'Мест'))),
              SizedBox(width: 12),
              Expanded(child: TextField(decoration: InputDecoration(labelText: 'Цена, ₽'))),
            ],
          ),
          const SizedBox(height: 12),
          const TextField(
            maxLines: 3,
            decoration: InputDecoration(
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
                  const Text('Можно добавить после публикации. Обычные поездки остаются бесплатными.'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => Navigator.of(context).pop(),
            icon: const Icon(Icons.check),
            label: const Text('Опубликовать'),
          ),
        ],
      ),
    );
  }
}

