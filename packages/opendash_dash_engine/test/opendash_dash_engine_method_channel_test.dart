import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_method_channel.dart';
import 'package:opendash_dash_engine/src/messages.g.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  void hostStream(String name) {
    final channel = MethodChannel(
      'dev.flutter.pigeon.opendash_dash_engine.DashEngineEvents.$name',
      pigeonMethodCodec,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => null);
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });
  }

  Future<void> emit(String name, Object event) async {
    ServicesBinding.instance.channelBuffers.push(
      'dev.flutter.pigeon.opendash_dash_engine.DashEngineEvents.$name',
      pigeonMethodCodec.encodeSuccessEnvelope(event),
      (_) {},
    );
    await pumpEventQueue();
  }

  test(
    'state readers both receive updates and one can leave independently',
    () async {
      hostStream('state');
      final platform = MethodChannelOpendashDashEngine();
      final first = <DashEngineState>[];
      final second = <DashEngineState>[];
      final firstSub = platform.stateStream.listen(first.add);
      addTearDown(firstSub.cancel);
      final secondSub = platform.stateStream.listen(second.add);
      addTearDown(secondSub.cancel);
      await pumpEventQueue();

      final active = state(mediaActive: true, mediaPlaying: true);
      await emit('state', active);
      await firstSub.cancel();
      final inactive = state(mediaActive: false);
      await emit('state', inactive);

      expect(first, [active]);
      expect(second, [active, inactive]);
    },
  );

  test(
    'button readers retain repeated presses after another reader leaves',
    () async {
      hostStream('button');
      final platform = MethodChannelOpendashDashEngine();
      final first = <int>[];
      final second = <int>[];
      final firstSub = platform.buttonStream.listen(
        (event) => first.add(event.code),
      );
      addTearDown(firstSub.cancel);
      final secondSub = platform.buttonStream.listen(
        (event) => second.add(event.code),
      );
      addTearDown(secondSub.cancel);
      await pumpEventQueue();

      await emit('button', DashButtonEvent(code: 0x09));
      await firstSub.cancel();
      await emit('button', DashButtonEvent(code: 0x09));
      await emit('button', DashButtonEvent(code: 0x09));

      expect(first, [0x09]);
      expect(second, [0x09, 0x09, 0x09]);
    },
  );
}

DashEngineState state({required bool mediaActive, bool? mediaPlaying}) =>
    DashEngineState(
      stage: DashStage.idle,
      explicitDisconnect: false,
      wifiStatus: WifiStatus.idle,
      navigating: false,
      hasGps: false,
      offRoute: false,
      gpsLost: false,
      gpsWeak: false,
      followMode: true,
      headingUp: true,
      zoom: 0,
      hasActiveCall: false,
      mediaActive: mediaActive,
      mediaPlaying: mediaPlaying,
    );
