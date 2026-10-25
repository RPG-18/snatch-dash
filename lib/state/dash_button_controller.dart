import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../util/app_logger.dart' show talker;
import 'dash_engine_state.dart';

// Physical up/down/center confirmed by the rider on 2026-10-23.
// Call actions still take precedence over the overlapping volume codes.
const _btnCallAnswer = 0x06;
const _btnCallReject = 0x07;
const _btnMediaPlayPause = 0x05;
const _btnMediaVolumeUp = 0x06;
const _btnMediaVolumeDown = 0x07;
const _btnMapZoomIn = 0x13;
const _btnMapZoomOut = 0x14;
const _btnMediaNext = 0x09;
const _btnMediaPrevious = 0x0A;
const _btnMediaNextAlias = 0x22;

/// Routes dash buttons to media, call, and map actions.
///
/// Native code acknowledges and forwards each raw event before Dart routing.
/// Up/down/center use mediaActive, not title or playback state, so paused and
/// titleless sessions remain controllable. With no media they do not zoom.
/// Track buttons retain their title-based media/zoom behavior.
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
    // The button stream, and straight from it rather than through a
    // `StreamProvider`.
    //
    // **Both halves of that matter.** It is its own channel since the boundary
    // was generated — before, this had to fish a `button` key out of every
    // state update while every other reader skipped the ones that had it. And
    // it is subscribed directly because Pigeon generates value equality for
    // its classes: two presses of the same button produce equal events, and
    // Riverpod suppresses a `ref.listen` callback whose new value equals the
    // old one. Zooming in twice would zoom in once. The map-shaped payload
    // this replaced compared by identity, so the old code never met the
    // problem.
    final sub = DashEngine.instance.buttonStream.listen(
      (event) => _handle(event.code),
    );
    ref.onDispose(sub.cancel);
  }

  void _handle(int code) {
    final engine = ref.read(dashEngineStateProvider);
    final hasMediaTitle = engine.nowPlayingTitle != null;
    final hasIncomingCall = engine.incomingCaller != null;

    if (hasIncomingCall && code == _btnCallAnswer) {
      DashEngine.instance.answerCall();
    } else if (engine.hasActiveCall && code == _btnCallReject) {
      // Unlike the answer branch above, this fires for ANY call — ringing or
      // already answered/outgoing — matching the original's `call != null`
      // (vs `call.incoming == true` for answer).
      DashEngine.instance.hangupCall();
    } else if (code == _btnMediaPlayPause) {
      if (engine.mediaActive) DashEngine.instance.mediaPlayPause();
    } else if (code == _btnMediaVolumeUp) {
      if (engine.mediaActive) DashEngine.instance.mediaVolumeUp();
    } else if (code == _btnMediaVolumeDown) {
      if (engine.mediaActive) DashEngine.instance.mediaVolumeDown();
    } else if (hasMediaTitle &&
        (code == _btnMediaNext || code == _btnMediaNextAlias)) {
      DashEngine.instance.skipNext();
    } else if (hasMediaTitle && code == _btnMediaPrevious) {
      DashEngine.instance.skipPrevious();
    } else if (code == _btnMapZoomIn ||
        code == _btnMediaNext ||
        code == _btnMediaNextAlias) {
      DashEngine.instance.zoomIn();
    } else if (code == _btnMapZoomOut || code == _btnMediaPrevious) {
      DashEngine.instance.zoomOut();
    } else {
      // The native side acks and forwards every `09 00`, so a code with no branch
      // here dies silently in this else — and from the saddle that is the same
      // press-and-nothing-happens as a broken control. The 2026-09-05 ride sent
      // 0x15 nine times and 0x0B six times; neither appears above, and nothing in
      // the log said so.
      //
      // Both are answered now: in the official app 0x15 is recenter and 0x0B is
      // start-navigation (docs/k1g_commands.md, "Buttons `09 00`"). Wiring them
      // to `recenter()` and a navigation start is a behaviour change, so it is
      // deliberately NOT done here — this line keeps saying so until it is.
      talker.warning(
        'dash button 0x${code.toRadixString(16).toUpperCase()} has no action',
      );
    }
  }
}

final dashButtonControllerProvider =
    NotifierProvider<DashButtonController, void>(DashButtonController.new);
