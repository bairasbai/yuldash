import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class OnboardingPage extends StatefulWidget {
  const OnboardingPage({super.key});

  @override
  State<OnboardingPage> createState() => _OnboardingPageState();
}

class _OnboardingPageState extends State<OnboardingPage> {
  static const _green = Color(0xFF0D7F49);
  static const _darkGreen = Color(0xFF075236);
  static const _amber = Color(0xFFE2AA27);

  final _controller = PageController();
  int _index = 0;
  _RideRole _role = _RideRole.passenger;

  bool get _isLast => _index == _slides.length - 1;

  static final _slides = <_OnboardingSlide>[
    _OnboardingSlide(
      eyebrow: 'Поездки между своими',
      title: 'Юлдаш помогает ехать спокойнее',
      body:
          'Ищите поездку, создавайте заявку или публикуйте маршрут. Важные детали остаются внутри приложения.',
      hero: _HeroKind.route,
      items: const [
        _OnboardingItem(Icons.phone_locked, 'Скрытый номер', 'Телефон не раскрывается до подтверждения поездки.'),
        _OnboardingItem(Icons.pin, 'Код посадки', 'Встреча с водителем подтверждается уникальным кодом.'),
        _OnboardingItem(Icons.verified_user, 'Проверка водителя', 'Профиль водителя и машина проходят проверку.'),
      ],
    ),
    _OnboardingSlide(
      eyebrow: 'Безопасность в каждой поездке',
      title: 'Защита включена с первого шага',
      body: 'Подтверждённые участники, скрытые контакты и SOS помогают держать поездку под контролем.',
      hero: _HeroKind.security,
      items: const [
        _OnboardingItem(Icons.admin_panel_settings, 'Подтверждённые участники', 'Меньше случайных контактов в заявках и откликах.'),
        _OnboardingItem(Icons.visibility_off, 'Номер не виден сразу', 'Контакты открываются после подтверждения поездки.'),
        _OnboardingItem(Icons.sos, 'SOS и поддержка', 'Экстренная помощь доступна прямо из приложения.'),
      ],
    ),
    _OnboardingSlide(
      eyebrow: 'Всё просто и понятно',
      title: 'Как это работает',
      body: 'Выберите маршрут, найдите подходящую поездку или создайте заявку, если варианта ещё нет.',
      hero: _HeroKind.steps,
      items: const [
        _OnboardingItem(Icons.search, 'Найдите поездку', 'Выберите маршрут и посмотрите ближайшие варианты.'),
        _OnboardingItem(Icons.add_road, 'Создайте заявку', 'Укажите маршрут, время и условия поездки.'),
        _OnboardingItem(Icons.chat_bubble_outline, 'Договоритесь в чате', 'После отклика можно обсудить детали и подтвердить поездку.'),
      ],
      note: 'Телефон откроется только после подтверждения поездки',
    ),
    _OnboardingSlide(
      eyebrow: 'Готово к первой поездке',
      title: 'Начнём?',
      body: 'Выберите удобный сценарий и войдите по номеру телефона.',
      hero: _HeroKind.start,
      items: const [],
    ),
  ];

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: PageView.builder(
                controller: _controller,
                itemCount: _slides.length,
                onPageChanged: (value) => setState(() => _index = value),
                itemBuilder: (context, index) {
                  final item = _slides[index];
                  return ListView(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
                    children: [
                      _HeroPanel(slide: item),
                      const SizedBox(height: 28),
                      Text(
                        item.title,
                        style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                              fontWeight: FontWeight.w900,
                              height: 1.05,
                              color: const Color(0xFF142019),
                            ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        item.body,
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(
                              height: 1.35,
                              color: Colors.black.withOpacity(0.58),
                            ),
                      ),
                      const SizedBox(height: 24),
                      if (index == _slides.length - 1)
                        _RoleChooser(
                          selected: _role,
                          onChanged: (value) => setState(() => _role = value),
                        )
                      else
                        _FeatureList(items: item.items),
                      if (item.note != null) ...[
                        const SizedBox(height: 16),
                        _SafetyNote(text: item.note!),
                      ],
                    ],
                  );
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
              child: Column(
                children: [
                  Row(
                    children: [
                      if (_index > 0)
                        TextButton(
                          onPressed: _previous,
                          child: const Text('Назад'),
                        )
                      else
                        const SizedBox(width: 76),
                      Expanded(child: _PageDots(count: _slides.length, index: _index)),
                      TextButton(
                        onPressed: _finish,
                        child: const Text('Пропустить'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  FilledButton(
                    onPressed: _isLast ? _finish : _next,
                    style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(56),
                      backgroundColor: _green,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                    ),
                    child: Text(_isLast ? 'Войти по телефону' : 'Далее'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _next() {
    _controller.nextPage(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutCubic,
    );
  }

  void _previous() {
    _controller.previousPage(
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
    );
  }

  void _finish() {
    context.go('/auth');
  }
}

class _HeroPanel extends StatelessWidget {
  const _HeroPanel({required this.slide});

  final _OnboardingSlide slide;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 1.58,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(28),
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              _OnboardingPageState._darkGreen,
              _OnboardingPageState._green,
              _OnboardingPageState._amber,
            ],
            stops: [0, 0.56, 1],
          ),
          boxShadow: const [
            BoxShadow(
              color: Color(0x260D7F49),
              blurRadius: 26,
              offset: Offset(0, 14),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: Stack(
            children: [
              Positioned.fill(child: CustomPaint(painter: _HeroRoutePainter(slide.hero))),
              Positioned(
                right: -20,
                top: -34,
                child: Container(
                  width: 132,
                  height: 132,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.white.withOpacity(0.14),
                  ),
                ),
              ),
              Positioned(
                left: 22,
                top: 22,
                child: Row(
                  children: [
                    Container(
                      width: 52,
                      height: 52,
                      padding: const EdgeInsets.all(7),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(18),
                        boxShadow: const [
                          BoxShadow(color: Color(0x24000000), blurRadius: 16, offset: Offset(0, 8)),
                        ],
                      ),
                      child: Image.asset('assets/brand/yuldash_logo.png', fit: BoxFit.contain),
                    ),
                    const SizedBox(width: 12),
                    const Text(
                      'Юлдаш',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 42,
                        height: 1,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ],
                ),
              ),
              Positioned(
                left: 24,
                top: 88,
                right: 24,
                child: Text(
                  slide.eyebrow,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    height: 1.25,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Positioned(
                right: 30,
                bottom: 32,
                child: _HeroIcon(kind: slide.hero),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HeroIcon extends StatelessWidget {
  const _HeroIcon({required this.kind});

  final _HeroKind kind;

  @override
  Widget build(BuildContext context) {
    final icon = switch (kind) {
      _HeroKind.route => Icons.directions_car_filled,
      _HeroKind.security => Icons.shield,
      _HeroKind.steps => Icons.alt_route,
      _HeroKind.start => Icons.location_on,
    };

    return Container(
      width: 96,
      height: 96,
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.18),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, color: Colors.white, size: 58),
    );
  }
}

class _FeatureList extends StatelessWidget {
  const _FeatureList({required this.items});

  final List<_OnboardingItem> items;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (var index = 0; index < items.length; index++) ...[
          _FeatureTile(item: items[index]),
          if (index != items.length - 1) const SizedBox(height: 12),
        ],
      ],
    );
  }
}

class _FeatureTile extends StatelessWidget {
  const _FeatureTile({required this.item});

  final _OnboardingItem item;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            _IconBubble(icon: item.icon),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w800,
                          height: 1.15,
                        ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    item.body,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Colors.black.withOpacity(0.58),
                          height: 1.3,
                        ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RoleChooser extends StatelessWidget {
  const _RoleChooser({
    required this.selected,
    required this.onChanged,
  });

  final _RideRole selected;
  final ValueChanged<_RideRole> onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _RoleTile(
          icon: Icons.person,
          title: 'Я пассажир',
          body: 'Ищу поездки, создаю заявки и общаюсь с водителями.',
          selected: selected == _RideRole.passenger,
          onTap: () => onChanged(_RideRole.passenger),
        ),
        const SizedBox(height: 12),
        _RoleTile(
          icon: Icons.directions_car,
          title: 'Я водитель',
          body: 'Публикую поездки, откликаюсь на заявки и прохожу проверку.',
          selected: selected == _RideRole.driver,
          onTap: () => onChanged(_RideRole.driver),
        ),
        const SizedBox(height: 16),
        const _TrustStrip(),
      ],
    );
  }
}

class _RoleTile extends StatelessWidget {
  const _RoleTile({
    required this.icon,
    required this.title,
    required this.body,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String body;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.primary;

    return Card(
      color: selected ? color.withOpacity(0.08) : Colors.white,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              _IconBubble(icon: icon),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w800,
                          ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      body,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: Colors.black.withOpacity(0.58),
                            height: 1.3,
                          ),
                    ),
                  ],
                ),
              ),
              Icon(
                selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                color: color,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TrustStrip extends StatelessWidget {
  const _TrustStrip();

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: const [
            Expanded(child: _MiniTrust(icon: Icons.phone_locked, label: 'Скрытый\nномер')),
            _Divider(),
            Expanded(child: _MiniTrust(icon: Icons.pin, label: 'Код\nпосадки')),
            _Divider(),
            Expanded(child: _MiniTrust(icon: Icons.verified, label: 'Проверка\nводителя')),
          ],
        ),
      ),
    );
  }
}

class _MiniTrust extends StatelessWidget {
  const _MiniTrust({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: Theme.of(context).colorScheme.primary),
        const SizedBox(height: 6),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, height: 1.05),
        ),
      ],
    );
  }
}

class _Divider extends StatelessWidget {
  const _Divider();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 1,
      height: 34,
      color: Colors.black.withOpacity(0.1),
      margin: const EdgeInsets.symmetric(horizontal: 8),
    );
  }
}

class _IconBubble extends StatelessWidget {
  const _IconBubble({required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.primary;

    return Container(
      width: 58,
      height: 58,
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, color: color, size: 30),
    );
  }
}

class _SafetyNote extends StatelessWidget {
  const _SafetyNote({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primary.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(Icons.lock, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                text,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w800,
                  height: 1.2,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PageDots extends StatelessWidget {
  const _PageDots({required this.count, required this.index});

  final int count;
  final int index;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(
        count,
        (dotIndex) {
          final active = dotIndex == index;
          return AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            width: active ? 20 : 8,
            height: 8,
            margin: const EdgeInsets.symmetric(horizontal: 4),
            decoration: BoxDecoration(
              color: active ? Theme.of(context).colorScheme.primary : Colors.black.withOpacity(0.2),
              borderRadius: BorderRadius.circular(999),
            ),
          );
        },
      ),
    );
  }
}

class _HeroRoutePainter extends CustomPainter {
  const _HeroRoutePainter(this.kind);

  final _HeroKind kind;

  @override
  void paint(Canvas canvas, Size size) {
    final glowPaint = Paint()
      ..color = Colors.white.withOpacity(0.22)
      ..strokeWidth = 13
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final roadPaint = Paint()
      ..color = Colors.white.withOpacity(0.9)
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final accentPaint = Paint()
      ..color = Colors.white.withOpacity(0.16)
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;

    final path = Path()
      ..moveTo(size.width * 0.08, size.height * 0.72)
      ..cubicTo(
        size.width * 0.32,
        size.height * 0.42,
        size.width * 0.52,
        size.height * 0.96,
        size.width * 0.92,
        size.height * 0.54,
      );

    canvas.drawPath(path, glowPaint);
    canvas.drawPath(path, roadPaint);

    for (final offset in [0.18, 0.42, 0.66]) {
      canvas.drawArc(
        Rect.fromCenter(
          center: Offset(size.width * offset, size.height * (kind == _HeroKind.start ? 0.72 : 0.62)),
          width: 78,
          height: 40,
        ),
        3.8,
        0.7,
        false,
        accentPaint,
      );
    }

    final pointPaint = Paint()..color = Colors.white;
    canvas.drawCircle(Offset(size.width * 0.08, size.height * 0.72), 8, pointPaint);
    canvas.drawCircle(Offset(size.width * 0.92, size.height * 0.54), 6, pointPaint);
  }

  @override
  bool shouldRepaint(covariant _HeroRoutePainter oldDelegate) => oldDelegate.kind != kind;
}

enum _RideRole { passenger, driver }

enum _HeroKind { route, security, steps, start }

class _OnboardingSlide {
  const _OnboardingSlide({
    required this.eyebrow,
    required this.title,
    required this.body,
    required this.hero,
    required this.items,
    this.note,
  });

  final String eyebrow;
  final String title;
  final String body;
  final _HeroKind hero;
  final List<_OnboardingItem> items;
  final String? note;
}

class _OnboardingItem {
  const _OnboardingItem(this.icon, this.title, this.body);

  final IconData icon;
  final String title;
  final String body;
}
