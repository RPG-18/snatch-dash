import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import 'dash_engine_state.dart';
import 'dash_wallpaper_store.dart';

/// K1G joystick/media/call button codes the physical dash sends as `09 00`
/// events — see `DashSession.dispatchIncoming`. Values match the original
/// native app's `DashViewModel` companion constants.
const _btnCallAnswer = 0x06;
const _btnCallReject = 0x07;
const _btnMapZoomIn = 0x13;
const _btnMapZoomOut = 0x14;
const _btnMediaNext = 0x09;
const _btnMediaPrevious = 0x0A;

// A second, looser set of codes some dash firmware/rocker variants send for
// "next"/"previous" instead of (or in addition to) the media codes above —
// only consulted once none of the more specific branches below matched.
bool _isNextWallpaperButton(int code) => code == 0x06 || code == 0x09 || code == 0x22;
bool _isPreviousWallpaperButton(int code) => code == 0x05 || code == 0x07 || code == 0x0A;

/// Dispatches physical dash button presses to an app-side action, mirroring
/// `DashViewModel.onButton` — the native `DashSession`/`DashEngineController`
/// only ack the button and forward the raw code (see `dashEngineRawStreamProvider`);
/// deciding what a code *means* (in the idle-wallpaper screen it cycles
/// wallpapers, on the nav map it zooms, elsewhere it answers/skips) is Dart's
/// job, same as it was the ViewModel's job in the original app.
///
/// Built eagerly from `main.dart` (like `dashWallpaperStoreProvider`) so it's
/// listening even if no screen happens to be watching it — otherwise a
/// session that never opens a wallpaper-cycling widget would silently drop
/// every button press, same failure mode the wallpaper-push regression this
/// was ported alongside had.
class DashButtonController extends Notifier<void> {
  @override
  void build() {
    ref.listen(dashEngineRawStreamProvider, (previous, next) {
      final code = next.value?['button'] as int?;
      if (code != null) _handle(code);
    });
  }

  void _handle(int code) {
    final engine = ref.read(dashEngineStateProvider);
    final idle = !engine.navigating;
    final mediaActive = engine.nowPlayingTitle != null;
    final hasIncomingCall = engine.incomingCaller != null;

    if (hasIncomingCall && code == _btnCallAnswer) {
      DashEngine.instance.answerCall();
    } else if (engine.hasActiveCall && code == _btnCallReject) {
      // Unlike the answer branch above, this fires for ANY call — ringing or
      // already answered/outgoing — matching the original's `call != null`
      // (vs `call.incoming == true` for answer).
      DashEngine.instance.hangupCall();
    } else if (idle && code == _btnMediaNext) {
      ref.read(dashWallpaperStoreProvider.notifier).cycle(1);
    } else if (idle && code == _btnMediaPrevious) {
      ref.read(dashWallpaperStoreProvider.notifier).cycle(-1);
    } else if (!idle && mediaActive && code == _btnMediaNext) {
      DashEngine.instance.skipNext();
    } else if (!idle && mediaActive && code == _btnMediaPrevious) {
      DashEngine.instance.skipPrevious();
    } else if (code == _btnMapZoomIn || (!mediaActive && code == _btnMediaNext)) {
      DashEngine.instance.zoomIn();
    } else if (code == _btnMapZoomOut || (!mediaActive && code == _btnMediaPrevious)) {
      DashEngine.instance.zoomOut();
    } else if (idle && _isNextWallpaperButton(code)) {
      ref.read(dashWallpaperStoreProvider.notifier).cycle(1);
    } else if (idle && _isPreviousWallpaperButton(code)) {
      ref.read(dashWallpaperStoreProvider.notifier).cycle(-1);
    }
  }
}

final dashButtonControllerProvider = NotifierProvider<DashButtonController, void>(DashButtonController.new);
