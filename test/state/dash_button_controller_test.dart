import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:snatch_dash/state/dash_button_controller.dart';
import 'package:snatch_dash/state/dash_engine_state.dart';

/// That a button pressed twice does something twice.
///
/// Not hypothetical: making the joystick its own Pigeon stream (task 7.6) very
/// nearly shipped with the second press swallowed. Pigeon generates value
/// equality for its classes, so two presses of one button are equal events, and
/// a `StreamProvider` holding them suppresses the second `ref.listen` callback.
/// The map-shaped payload it replaced compared by identity, so the behaviour
/// changed without a single call site changing.
void main() {
  late _RecordingPlatform platform;
  late OpendashDashEnginePlatform saved;

  setUp(() {
    saved = OpendashDashEnginePlatform.instance;
    platform = _RecordingPlatform();
    OpendashDashEnginePlatform.instance = platform;
  });

  tearDown(() {
    OpendashDashEnginePlatform.instance = saved;
    platform.dispose();
  });

  test('the same button pressed twice acts twice', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    // Built eagerly in main.dart, so nothing else has to be watching.
    container.read(dashButtonControllerProvider);

    platform.press(_zoomIn);
    platform.press(_zoomIn);
    await pumpEventQueue();

    expect(platform.calls, ['zoomIn', 'zoomIn']);
  });

  test('alternating buttons still act, so the test above is about repeats', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(dashButtonControllerProvider);

    platform.press(_zoomIn);
    platform.press(_zoomOut);
    await pumpEventQueue();

    expect(platform.calls, ['zoomIn', 'zoomOut']);
  });

  test('the subscription goes away with the container', () async {
    final container = ProviderContainer();
    container.read(dashButtonControllerProvider);
    container.dispose();

    platform.press(_zoomIn);
    await pumpEventQueue();

    expect(platform.calls, isEmpty, reason: 'a disposed controller still acted');
  });

  test(
    'paused media with an empty title retains legacy next and previous actions',
    () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      container.read(dashButtonControllerProvider);
      final stateSub = container.listen(dashEngineStateProvider, (_, _) {});
      addTearDown(stateSub.close);
      await pumpEventQueue();

      platform.publish(title: '', mediaActive: true, mediaPlaying: false);
      await pumpEventQueue();
      platform.press(0x09);
      platform.press(0x0A);
      await pumpEventQueue();

      expect(platform.calls, ['skipNext', 'skipPrevious']);
    },
  );

  test(
    'new media flags do not switch the compatibility title gate before ride',
    () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      container.read(dashButtonControllerProvider);
      final stateSub = container.listen(dashEngineStateProvider, (_, _) {});
      addTearDown(stateSub.close);
      await pumpEventQueue();

      platform.publish(mediaActive: true, mediaPlaying: null);
      await pumpEventQueue();
      platform.press(0x09);
      await pumpEventQueue();
      platform.publish(title: 'Song', mediaActive: false, mediaPlaying: null);
      await pumpEventQueue();
      platform.press(0x09);
      await pumpEventQueue();

      expect(platform.calls, ['zoomIn', 'skipNext']);
    },
  );

  test(
    'ringing answer and active-call hangup retain precedence over media aliases',
    () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      container.read(dashButtonControllerProvider);
      final stateSub = container.listen(dashEngineStateProvider, (_, _) {});
      addTearDown(stateSub.close);
      await pumpEventQueue();

      platform.publish(
        title: 'Song',
        mediaActive: true,
        mediaPlaying: true,
        incomingCaller: 'Caller',
        hasActiveCall: true,
      );
      await pumpEventQueue();
      platform.press(0x06);
      platform.press(0x07);
      await pumpEventQueue();
      platform.publish(title: 'Song', mediaActive: true, hasActiveCall: true);
      await pumpEventQueue();
      platform.press(0x07);
      await pumpEventQueue();

      expect(platform.calls, ['answerCall', 'hangupCall', 'hangupCall']);
    },
  );
}

const _zoomIn = 0x13;
const _zoomOut = 0x14;

class _RecordingPlatform extends OpendashDashEnginePlatform
    with MockPlatformInterfaceMixin {
  final _buttons = StreamController<DashButtonEvent>.broadcast();
  final _states = StreamController<DashEngineState>.broadcast();
  final calls = <String>[];

  void press(int code) => _buttons.add(DashButtonEvent(code: code));
  void publish({
    String? title,
    required bool mediaActive,
    bool? mediaPlaying,
    String? incomingCaller,
    bool hasActiveCall = false,
  }) {
    final state = idleDashState
      ..nowPlayingTitle = title
      ..mediaActive = mediaActive
      ..mediaPlaying = mediaPlaying
      ..incomingCaller = incomingCaller
      ..hasActiveCall = hasActiveCall;
    _states.add(state);
  }

  void dispose() {
    unawaited(_buttons.close());
    unawaited(_states.close());
  }

  @override
  Stream<DashEngineState> get stateStream => _states.stream;

  @override
  Stream<DashButtonEvent> get buttonStream => _buttons.stream;

  @override
  Future<void> zoomIn() async => calls.add('zoomIn');

  @override
  Future<void> zoomOut() async => calls.add('zoomOut');

  @override
  Future<bool> skipNext() async {
    calls.add('skipNext');
    return true;
  }

  @override
  Future<bool> skipPrevious() async {
    calls.add('skipPrevious');
    return true;
  }

  @override
  Future<bool> answerCall() async {
    calls.add('answerCall');
    return true;
  }

  @override
  Future<bool> hangupCall() async {
    calls.add('hangupCall');
    return true;
  }

  @override
  Future<void> mediaPlayPause() async => calls.add('mediaPlayPause');

  @override
  Future<void> mediaVolumeUp() async => calls.add('mediaVolumeUp');

  @override
  Future<void> mediaVolumeDown() async => calls.add('mediaVolumeDown');

  @override
  Future<void> mediaMute() async => calls.add('mediaMute');
}
