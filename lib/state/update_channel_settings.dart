import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../util/github_release.dart';

const _prefsKeyChannel = 'update_channel';

/// Persisted stable-vs-nightly toggle for the self-update checker (Settings
/// screen's "Обновления" card). Same `SharedPreferences`-backed
/// load-in-`build()` shape as `CurrencySettingsController` — see that class's
/// doc for the `_generation` race-guard rationale.
class UpdateChannelController extends Notifier<UpdateChannel> {
  int _generation = 0;

  @override
  UpdateChannel build() {
    _load(_generation);
    return UpdateChannel.stable;
  }

  Future<void> _load(int generation) async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_prefsKeyChannel);
    if (!ref.mounted) return;
    if (generation != _generation) return;
    state = saved == UpdateChannel.nightly.name ? UpdateChannel.nightly : UpdateChannel.stable;
  }

  Future<void> select(UpdateChannel channel) async {
    _generation++;
    state = channel;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKeyChannel, channel.name);
  }
}

final updateChannelSettingsProvider =
    NotifierProvider<UpdateChannelController, UpdateChannel>(UpdateChannelController.new);
