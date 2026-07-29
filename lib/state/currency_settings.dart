import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/app_localizations.dart';

enum OpenDashCurrency {
  inr('INR', '₹'),
  usd('USD', '\$'),
  eur('EUR', '€'),
  gbp('GBP', '£'),
  aud('AUD', 'A\$'),
  cad('CAD', 'C\$'),
  sgd('SGD', 'S\$'),
  aed('AED', 'AED'),
  rub('RUB', '₽');

  const OpenDashCurrency(this.code, this.symbol);
  final String code;
  final String symbol;
}

String currencyDisplayName(AppLocalizations l10n, OpenDashCurrency currency) => switch (currency) {
      OpenDashCurrency.inr => l10n.currencyNameInr,
      OpenDashCurrency.usd => l10n.currencyNameUsd,
      OpenDashCurrency.eur => l10n.currencyNameEur,
      OpenDashCurrency.gbp => l10n.currencyNameGbp,
      OpenDashCurrency.aud => l10n.currencyNameAud,
      OpenDashCurrency.cad => l10n.currencyNameCad,
      OpenDashCurrency.sgd => l10n.currencyNameSgd,
      OpenDashCurrency.aed => l10n.currencyNameAed,
      OpenDashCurrency.rub => l10n.currencyNameRub,
    };

String formatCurrencyAmount(double amount, OpenDashCurrency currency, {int decimals = 0}) {
  final formatted = amount.toStringAsFixed(decimals);
  final withThousands = _addThousandsSeparators(formatted);
  return currency.symbol.length > 1 ? '${currency.symbol} $withThousands' : '${currency.symbol}$withThousands';
}

String _addThousandsSeparators(String number) {
  final parts = number.split('.');
  final intPart = parts[0];
  final buffer = StringBuffer();
  for (var i = 0; i < intPart.length; i++) {
    if (i > 0 && (intPart.length - i) % 3 == 0) buffer.write(',');
    buffer.write(intPart[i]);
  }
  return parts.length > 1 ? '${buffer.toString()}.${parts[1]}' : buffer.toString();
}

const _prefsKeyCurrency = 'currency_code';

/// Ported from `data/CurrencySettings.kt` (`SharedPreferences`-backed).
class CurrencySettingsController extends Notifier<OpenDashCurrency> {
  // Bumped by `select` so a slow initial `_load` can't win a race against a
  // currency the rider already picked and flash the UI back to the old one —
  // `_load` only applies its result while this still matches what it started
  // with (see the same guard in GarageController for the full rationale).
  int _generation = 0;

  @override
  OpenDashCurrency build() {
    _load(_generation);
    return OpenDashCurrency.inr;
  }

  Future<void> _load(int generation) async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_prefsKeyCurrency);
    // The provider can be disposed while the await above is pending —
    // writing `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    if (generation != _generation) return; // the rider already picked a currency
    state = OpenDashCurrency.values.firstWhere(
      (c) => c.code == saved,
      orElse: () => OpenDashCurrency.inr,
    );
  }

  Future<void> select(OpenDashCurrency currency) async {
    _generation++;
    state = currency;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKeyCurrency, currency.code);
  }
}

final currencySettingsProvider =
    NotifierProvider<CurrencySettingsController, OpenDashCurrency>(CurrencySettingsController.new);
