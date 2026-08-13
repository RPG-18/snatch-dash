import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Shared with `AppUpdateController.checkOnLaunch`, which reads this key
/// straight from `SharedPreferences` instead of through [autoUpdateSettingsProvider]
/// — see that method's doc for why (avoids a hydration race at app startup).
const prefsKeyAutoUpdateEnabled = 'auto_update_enabled';

/// Persisted "Автоматическое обновление" toggle (Settings screen's
/// «Обновления» card) — gates both the on-launch check
/// (`AppUpdateController.checkOnLaunch`) and whether the card's channel
/// switch/status section is shown at all. Same `SharedPreferences`-backed
/// load-in-`build()` shape as `CurrencySettingsController` — see that
/// class's doc for the `_generation` race-guard rationale. Defaults to
/// `true` — matches the auto-check-every-launch behaviour this toggle was
/// added on top of, so existing installs don't silently stop checking.
class AutoUpdateController extends Notifier<bool> {
  int _generation = 0;

  @override
  bool build() {
    _load(_generation);
    return true;
  }

  Future<void> _load(int generation) async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getBool(prefsKeyAutoUpdateEnabled);
    if (!ref.mounted) return;
    if (generation != _generation) return;
    state = saved ?? true;
  }

  Future<void> setEnabled(bool enabled) async {
    _generation++;
    state = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(prefsKeyAutoUpdateEnabled, enabled);
  }
}

final autoUpdateSettingsProvider = NotifierProvider<AutoUpdateController, bool>(AutoUpdateController.new);
