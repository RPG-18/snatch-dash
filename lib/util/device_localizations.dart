import 'package:flutter/widgets.dart';

import '../l10n/app_localizations.dart';

/// For code paths with no `BuildContext` (TTS voice guidance, system
/// notifications) — resolves strings against the device locale the same way
/// `MaterialApp` resolves it for the widget tree, since this app has no
/// in-app language switcher (locale always follows the OS).
AppLocalizations deviceLocalizations() {
  final deviceLocale = WidgetsBinding.instance.platformDispatcher.locale;
  final match = AppLocalizations.supportedLocales.firstWhere(
    (l) => l.languageCode == deviceLocale.languageCode,
    orElse: () => AppLocalizations.supportedLocales.first,
  );
  return lookupAppLocalizations(match);
}
