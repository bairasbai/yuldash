import 'package:flutter/material.dart';

class AppLocaleController extends InheritedNotifier<ValueNotifier<Locale>> {
  const AppLocaleController({
    super.key,
    required ValueNotifier<Locale> localeNotifier,
    required super.child,
  }) : super(notifier: localeNotifier);

  static Locale localeOf(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppLocaleController>();
    return scope?.notifier?.value ?? const Locale('ru');
  }

  static void setLocale(BuildContext context, Locale locale) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppLocaleController>();
    scope?.notifier?.value = locale;
  }

  static void toggle(BuildContext context) {
    final current = localeOf(context);
    setLocale(context, current.languageCode == 'ru' ? const Locale('ba') : const Locale('ru'));
  }
}

