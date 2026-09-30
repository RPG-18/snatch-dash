import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_method_channel.dart';
import 'package:opendash_dash_engine/src/messages.g.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelOpendashDashEngine();

  /// Pigeon's channel for one host method: a `BasicMessageChannel` with the
  /// generated codec, not a `MethodChannel` — which is why the old mock kept
  /// answering while the call underneath it had moved.
  BasicMessageChannel<Object?> hostChannel(String method) =>
      BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.opendash_dash_engine.DashEngineApi.$method',
        DashEngineApi.pigeonChannelCodec,
      );

  void mock(String method, Object? Function(Object? message) reply) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<Object?>(
          hostChannel(method),
          (Object? message) async => reply(message),
        );
  }

  tearDown(() {
    for (final method in ['getPlatformVersion', 'setSsid']) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockDecodedMessageHandler<Object?>(hostChannel(method), null);
    }
  });

  test('getPlatformVersion', () async {
    // A Pigeon reply is a one-element list; an error is a three-element one.
    mock('getPlatformVersion', (_) => <Object?>['42']);
    expect(await platform.getPlatformVersion(), '42');
  });

  test('arguments arrive positionally, in schema order', () async {
    // The whole point of the change: an argument used to be looked up by
    // string, so a name that disagreed between the two sides read as null and
    // nothing said so.
    Object? seen;
    mock('setSsid', (message) {
      seen = message;
      return <Object?>[null];
    });
    await platform.setSsid('RE_9CP9_250218');
    expect(seen, <Object?>['RE_9CP9_250218']);
  });

  test('each stream is created once and shared', () async {
    // Every call to Pigeon's stream function builds a new EventChannel, and
    // the native side keeps one sink per channel — so a second call would
    // leave the first subscriber attached to a sink nothing writes to. There
    // are three Dart readers of the state alone.
    expect(identical(platform.stateStream, platform.stateStream), isTrue);
    expect(identical(platform.buttonStream, platform.buttonStream), isTrue);
    expect(identical(platform.logStream, platform.logStream), isTrue);
  });
}
