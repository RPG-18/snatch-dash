import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../nav/voice_manager.dart';
import 'dash_engine_state.dart';

/// How long a non-`STREAMING` stage must persist before it's announced —
/// long enough to skip a normal reconnect blink (the WiFi/auth retry loops
/// in `DashWifiManager`/`DashEngineController` routinely recover within a
/// few seconds), short enough that the rider — screen off, can't see the
/// dash went dark — finds out promptly. Matches NorthStar's
/// `CONN_LOST_ALERT_MS`, see spec/wifi_retry_policy.md.
const _connLostAlertDelay = Duration(seconds: 12);

/// Voice/vibration-free (vibration is native-only, not ported here) alert for
/// a sustained dash connection drop. Ported from NorthStar's
/// `armConnLostAlert`/`cancelConnLostAlert` (see spec/wifi_retry_policy.md's
/// "Из живого форка") — fires once after [_connLostAlertDelay] of
/// non-`STREAMING`, not on a brief blip; announces recovery once back.
///
/// Built eagerly from `main.dart`, same reasoning as `DashButtonController` —
/// this only does anything via its own `ref.listen`, and the whole point is
/// catching a drop while the rider isn't looking at the (deliberately off)
/// screen, so it has to be alive for the whole session, not just while some
/// screen happens to be watching the connection state.
class DashConnectionAlertController extends Notifier<void> {
  Timer? _timer;
  bool _everStreamed = false;
  bool _alerted = false;

  @override
  void build() {
    ref.listen(dashEngineStateProvider, (previous, next) => _onState(next));
    ref.onDispose(() => _timer?.cancel());
  }

  void _onState(DashEngineState state) {
    if (state.stage == DashStage.streaming) {
      _everStreamed = true;
      _cancelTimer();
      if (_alerted) {
        _alerted = false;
        VoiceManager.instance.announceConnectionRestored();
      }
      return;
    }
    // Rider asked to disconnect — not a drop, nothing to announce. Reset so a
    // later fresh connect() starts this from a clean slate.
    if (state.explicitDisconnect) {
      _everStreamed = false;
      _alerted = false;
      _cancelTimer();
      return;
    }
    // Never actually connected yet this attempt (still on the very first
    // WiFi/auth handshake) — that's not a "drop", just not-there-yet.
    if (!_everStreamed) return;
    _timer ??= Timer(_connLostAlertDelay, () {
      _alerted = true;
      VoiceManager.instance.announceConnectionLost();
    });
  }

  void _cancelTimer() {
    _timer?.cancel();
    _timer = null;
  }
}

final dashConnectionAlertControllerProvider =
    NotifierProvider<DashConnectionAlertController, void>(DashConnectionAlertController.new);
