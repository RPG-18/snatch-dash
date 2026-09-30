import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:snatch_dash/util/app_logger.dart';
import 'package:snatch_dash/util/error_reporting.dart';
import 'package:talker_flutter/talker_flutter.dart';

/// That a Flutter error reaches the ride file at all.
///
/// Before 2026-09-29 it reached nothing: no `FlutterError.onError`, no zone
/// handler, so the console had it and `diag/` — the only log a release build
/// keeps — did not. The day that cost is in the source file's own doc.
void main() {
  late _RecordingPlatform platform;
  late FlutterExceptionHandler? savedOnError;
  late ErrorCallback? savedPlatformOnError;

  setUp(() {
    platform = _RecordingPlatform();
    OpendashDashEnginePlatform.instance = platform;
    savedOnError = FlutterError.onError;
    // Restored too: without it each test leaves another handler chained to the
    // last, and by the third the reporter runs three times per error.
    savedPlatformOnError = PlatformDispatcher.instance.onError;
    talker.cleanHistory();
    resetErrorReportingForTest();
  });

  tearDown(() {
    FlutterError.onError = savedOnError;
    PlatformDispatcher.instance.onError = savedPlatformOnError;
  });

  test('a framework error reaches the ride file, with its context', () async {
    installErrorReporting();

    FlutterError.onError!(
      FlutterErrorDetails(
        exception: StateError('controller disposed'),
        library: 'widgets library',
        context: ErrorDescription('building TextField'),
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines, hasLength(1));
    final line = platform.lines.single;
    expect(line, contains('controller disposed'));
    // The library and the context are what turn "something threw" into a place
    // to look — exactly what was missing on 28.09.
    expect(line, contains('widgets library'));
    expect(line, contains('building TextField'));
  });

  test('the previous handler still runs, so the console dump survives', () {
    var previousCalls = 0;
    FlutterError.onError = (_) => previousCalls++;

    installErrorReporting();
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('x')));

    // Reporting must ADD sinks. The default handler is what prints the widget
    // stack, and that dump is the only thing that worked on 28.09.
    expect(previousCalls, 1);
  });

  test('an error that escapes to the platform is reported too', () async {
    // The half `FlutterError.onError` never sees: an unawaited future that
    // throws, a callback outside a build.
    installErrorReporting();

    final handled = PlatformDispatcher.instance.onError!(
      StateError('escaped'),
      StackTrace.current,
    );
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines.single, contains('escaped'));
    expect(
      handled,
      isFalse,
      reason:
          'this reports, it does not swallow — the default behaviour stands',
    );
  });

  test('the same error every frame costs one line, not sixty', () async {
    // app_log.txt is capped at 10 MB with one rotation, so an unlimited path
    // would overwrite the first occurrence — the evidence this exists to keep.
    installErrorReporting();

    for (var i = 0; i < 60; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('same')));
    }
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines, hasLength(1));
  });

  test('the swallowed repeats are counted, not just dropped', () async {
    // Collapsing on the same 10 s window as the native limiter means a repeat never
    // reaches it, so its own `repeats` counter is dead for this tag. Without the
    // count travelling here, the ride file cannot tell three occurrences of a
    // once-per-frame error from a hundred thousand.
    installErrorReporting();
    // Pinned from zero, not left on the real stopwatch: the process has already
    // been running for some milliseconds by now, so "10001" would otherwise be
    // just short of a window and the test would pass for the wrong reason.
    errorReportingClockMs = () => 0;

    for (var i = 0; i < 40; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('rate me')));
    }
    await Future<void>.delayed(Duration.zero);
    expect(platform.lines, hasLength(1));
    expect(
      platform.lines.single,
      isNot(contains('repeated')),
      reason: 'the first occurrence has nothing to report yet',
    );

    // Past the window, the next occurrence carries what it swallowed.
    errorReportingClockMs = () => 10001;
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('rate me')));
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines, hasLength(2));
    expect(platform.lines.last, contains('repeated 39 more time(s)'));
    expect(platform.lines.last, contains('in the last 10s'));

    // And the counter starts over, rather than accumulating for the ride.
    errorReportingClockMs = () => 20002;
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('rate me')));
    await Future<void>.delayed(Duration.zero);
    expect(platform.lines.last, isNot(contains('repeated')));
  });

  test('an exception whose toString throws does not escape', () async {
    // The handler runs from the framework's own error path. `exceptionAsString()`
    // called outside the guard would turn a diagnosable failure into an
    // undiagnosable one — and a `toString` reaching disposed state is precisely how
    // the 28.09 bug behaved.
    installErrorReporting();

    expect(
      () => FlutterError.onError!(
        FlutterErrorDetails(exception: _HostileError()),
      ),
      returnsNormally,
    );
    expect(
      () => PlatformDispatcher.instance.onError!(
        _HostileError(),
        StackTrace.current,
      ),
      returnsNormally,
    );
    await Future<void>.delayed(Duration.zero);
  });

  test('a multi-line exception arrives as one line', () async {
    // RideDiagnostics stamps only the first line; the rest would land with no
    // timestamp and no WARN, invisible to the grep the file exists for.
    installErrorReporting();

    FlutterError.onError!(
      FlutterErrorDetails(exception: Exception('first\nsecond\nthird')),
    );
    await Future<void>.delayed(Duration.zero);

    final line = platform.lines.single;
    expect(line, isNot(contains('\n')));
    expect(line, contains('first'));
    expect(line, contains('third'));
  });

  test('Talker gets the error too, not only the ride file', () async {
    // Two sinks, and the tests only watched one of them: silencing this call
    // left the suite green. Talker is what the debug-build `app_log.txt` and
    // the in-app `/more/logs` viewer read, i.e. the half available without a
    // cable — and the ride file only exists between connect and disconnect.
    installErrorReporting();

    FlutterError.onError!(
      FlutterErrorDetails(exception: StateError('both sinks')),
    );
    await Future<void>.delayed(Duration.zero);

    final logged = talker.history
        .where((e) => e.message?.contains('both sinks') ?? false)
        .toList();
    expect(logged, hasLength(1));
    expect(
      logged.single.logLevel,
      LogLevel.error,
      reason: '`app_log.txt` and the log screen are read through a level '
          'filter — at info this is a failure nobody sees',
    );
  });

  test('a channel that throws does not take the error path down with it', () async {
    platform.throwOnRideError = true;
    installErrorReporting();

    // No exception may escape: this runs from the framework's own error path,
    // and a throw here replaces a diagnosable failure with an undiagnosable one.
    expect(
      () => FlutterError.onError!(
        FlutterErrorDetails(exception: Exception('channel down')),
      ),
      returnsNormally,
    );
    await Future<void>.delayed(Duration.zero);

    // And the other sink still has it. A dead channel must cost the ride-file
    // copy only, not the whole report.
    expect(
      talker.history.where((e) => e.message?.contains('channel down') ?? false),
      hasLength(1),
    );
  });
}

/// An error object that fights back. Rare, but the guard has to hold for it.
class _HostileError implements Exception {
  @override
  String toString() => throw StateError('toString is the thing that is broken');
}

class _RecordingPlatform extends OpendashDashEnginePlatform
    with MockPlatformInterfaceMixin {
  final lines = <String>[];
  bool throwOnRideError = false;

  @override
  Future<void> rideError(String message) async {
    if (throwOnRideError) throw Exception('channel gone');
    lines.add(message);
  }
}
