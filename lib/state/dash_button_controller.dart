import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../util/app_logger.dart' show talker;
import 'dash_engine_state.dart';

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
// "next"/"previous" instead of (or in addition to) the media codes above.
// Treated exactly like those codes below — the two sets don't overlap with
// the zoom codes, so merging them changes nothing but saves two branches.
// They DO overlap with the call codes (0x06/0x07), which is why the call
// branches have to stay first.
bool _isLooseNextButton(int code) => code == 0x06 || code == 0x09 || code == 0x22;
bool _isLoosePreviousButton(int code) => code == 0x05 || code == 0x07 || code == 0x0A;

/// Dispatches physical dash button presses to an app-side action, mirroring
/// `DashViewModel.onButton` — the native `DashSession`/`DashEngineController`
/// only ack the button and forward the raw code (see `dashEngineRawStreamProvider`);
/// deciding what a code *means* (with music playing it skips tracks, otherwise
/// it zooms the map, and a ringing call takes precedence over both) is Dart's
/// job, same as it was the ViewModel's job in the original app.
///
/// Nothing here branches on `navigating` any more: the dash renders the map in
/// both idle and navigation, so next/prev mean the same thing either way.
///
/// Built eagerly from `main.dart` so it's listening even if no screen happens
/// to be watching it — otherwise a session where no widget subscribes would
/// silently drop every button press.
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
    final mediaActive = engine.nowPlayingTitle != null;
    final hasIncomingCall = engine.incomingCaller != null;

    if (hasIncomingCall && code == _btnCallAnswer) {
      DashEngine.instance.answerCall();
    } else if (engine.hasActiveCall && code == _btnCallReject) {
      // Unlike the answer branch above, this fires for ANY call — ringing or
      // already answered/outgoing — matching the original's `call != null`
      // (vs `call.incoming == true` for answer).
      DashEngine.instance.hangupCall();
    } else if (mediaActive && (code == _btnMediaNext || _isLooseNextButton(code))) {
      DashEngine.instance.skipNext();
    } else if (mediaActive && (code == _btnMediaPrevious || _isLoosePreviousButton(code))) {
      DashEngine.instance.skipPrevious();
    } else if (code == _btnMapZoomIn || code == _btnMediaNext || _isLooseNextButton(code)) {
      DashEngine.instance.zoomIn();
    } else if (code == _btnMapZoomOut || code == _btnMediaPrevious || _isLoosePreviousButton(code)) {
      DashEngine.instance.zoomOut();
    } else {
      // The native side acks and forwards every `09 00`, so a code with no branch
      // here dies silently in this else — and from the saddle that is the same
      // press-and-nothing-happens as a broken control. The 2026-09-05 ride sent
      // 0x15 nine times and 0x0B six times; neither appears above, and nothing in
      // the log said so. Whether they SHOULD do something is a separate question
      // this line exists to raise.
      talker.warning('dash button 0x${code.toRadixString(16).toUpperCase()} has no action');
    }
  }
}

final dashButtonControllerProvider = NotifierProvider<DashButtonController, void>(DashButtonController.new);
