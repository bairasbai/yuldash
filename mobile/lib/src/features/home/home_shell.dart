import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../profile/profile_page.dart';
import '../rides/rides_page.dart';
import 'map_page.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final pages = const [
      MapPage(),
      RidesPage(),
      ProfilePage(),
    ];

    return Scaffold(
      body: pages[_index],
      floatingActionButton: _index == 2
          ? null
          : FloatingActionButton.extended(
              onPressed: () => context.push('/rides/create'),
              icon: const Icon(Icons.add_road),
              label: const Text('Я еду'),
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (value) => setState(() => _index = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.map), label: 'Карта'),
          NavigationDestination(icon: Icon(Icons.list_alt), label: 'Поездки'),
          NavigationDestination(icon: Icon(Icons.person), label: 'Профиль'),
        ],
      ),
    );
  }
}

