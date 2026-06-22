import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';

import 'features/auth/phone_login_page.dart';
import 'features/home/home_shell.dart';
import 'features/onboarding/onboarding_page.dart';
import 'features/rides/create_ride_page.dart';
import 'features/support/support_page.dart';
import 'localization/app_locale_controller.dart';
import 'theme/app_theme.dart';

final _router = GoRouter(
  initialLocation: '/onboarding',
  routes: [
    GoRoute(
      path: '/onboarding',
      builder: (context, state) => const OnboardingPage(),
    ),
    GoRoute(
      path: '/auth',
      builder: (context, state) => const PhoneLoginPage(),
    ),
    GoRoute(
      path: '/',
      builder: (context, state) => const HomeShell(),
    ),
    GoRoute(
      path: '/rides/create',
      builder: (context, state) => const CreateRidePage(),
    ),
    GoRoute(
      path: '/support',
      builder: (context, state) => const SupportPage(),
    ),
  ],
);

class YuldashApp extends StatefulWidget {
  const YuldashApp({super.key});

  @override
  State<YuldashApp> createState() => _YuldashAppState();
}

class _YuldashAppState extends State<YuldashApp> {
  final _localeNotifier = ValueNotifier(const Locale('ru'));

  @override
  Widget build(BuildContext context) {
    return AppLocaleController(
      localeNotifier: _localeNotifier,
      child: ValueListenableBuilder<Locale>(
        valueListenable: _localeNotifier,
        builder: (context, locale, _) {
          return MaterialApp.router(
            title: 'Юлдаш',
            debugShowCheckedModeBanner: false,
            theme: AppTheme.light,
            routerConfig: _router,
            locale: locale,
            supportedLocales: const [
              Locale('ru'),
              Locale('ba'),
            ],
            localizationsDelegates: const [
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
          );
        },
      ),
    );
  }
}
