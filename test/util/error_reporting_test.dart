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

  test('endless DISTINCT lines are bounded too, and the loss is stated', () async {
    // The half the native limiter cannot do for us: it gates `diag/` only, and
    // this sink also feeds `app_log.txt`, which is capped at 10 MB with a single
    // rotation. An exception embedding a varying value — `RenderFlex#a1b2c3` —
    // collapses on nothing, so without a budget it writes 60 lines a second.
    installErrorReporting();
    errorReportingClockMs = () => 0;

    for (var i = 0; i < 20; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('key $i')));
    }
    await Future<void>.delayed(Duration.zero);
    expect(platform.lines, hasLength(6), reason: 'the window admits six');

    errorReportingClockMs = () => 10001;
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('after')));
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines.last, contains('+14 further line(s) not written'));
    expect(platform.lines.last, contains('6-per-10s budget'));
  });

  test('the ride file gets a stack frame, not just the message', () async {
    // On a release build `diag/` is the only log there is: `app_log.txt`, where
    // Talker keeps the whole trace, needs run-as and a debug build. A bare
    // "controller was used after being disposed" is the dead end of 28.09.
    installErrorReporting();

    void thrower() => FlutterError.onError!(
      FlutterErrorDetails(exception: StateError('needs a frame'), stack: StackTrace.current),
    );
    thrower();
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines.single, contains(' @ '));
    expect(
      platform.lines.single,
      contains('error_reporting_test.dart'),
      reason: 'the frame must name a file to open, not framework plumbing',
    );
  });

  test('the frame skips framework plumbing and names our code', () async {
    // The top of a real trace is where the throw was NOTICED. Taking it gives
    // `package:flutter/src/widgets/framework.dart`, which is the same for every
    // widget error and names nothing to open.
    installErrorReporting();

    FlutterError.onError!(
      FlutterErrorDetails(
        exception: StateError('deep'),
        stack: StackTrace.fromString(
          '#0      RenderObject.debugAssert (package:flutter/src/rendering/object.dart:1)\n'
          '#1      _rootRun (dart:async/zone.dart:2)\n'
          '#2      _SettingsScreenState._save (package:snatch_dash/screens/settings_screen.dart:437:12)\n',
        ),
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines.single, contains('settings_screen.dart:437'));
    expect(platform.lines.single, isNot(contains('package:flutter/')));
  });

  test('the same text from both handlers is two reports, not one', () async {
    // Keyed on the source as well: otherwise the reader never learns the failure
    // has an async escape path as well as a build path, and the repeat count is
    // filed against whichever source happens to report next.
    installErrorReporting();

    // `library: null`, so the two keys are identical in everything BUT the
    // source. With the default 'Flutter framework' they differ anyway and the
    // test would pass with the source left out of the key entirely.
    FlutterError.onError!(
      FlutterErrorDetails(exception: StateError('two doors'), library: null),
    );
    PlatformDispatcher.instance.onError!(StateError('two doors'), StackTrace.current);
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines, hasLength(2));
    expect(platform.lines[0], startsWith('FlutterError:'));
    expect(platform.lines[1], startsWith('PlatformDispatcher:'));
  });

  test('installing twice does not double every report', () async {
    // Chained onto itself, one error yields two entries and two channel calls,
    // and the second is counted as a repeat of the first — inflating every count
    // this file reports.
    installErrorReporting();
    installErrorReporting();
    errorReportingClockMs = () => 0;

    FlutterError.onError!(FlutterErrorDetails(exception: Exception('once')));
    await Future<void>.delayed(Duration.zero);
    expect(platform.lines, hasLength(1));

    // The count is where a chained install actually shows: the collapser eats
    // the duplicate, so the line total looks right while every repeat figure
    // the file reports is 2x.
    errorReportingClockMs = () => 10001;
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('once')));
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines, hasLength(2));
    expect(
      platform.lines.last,
      isNot(contains('repeated')),
      reason: 'one occurrence was reported once, so nothing was swallowed',
    );
  });

  test('a line refused by the budget keeps its own tally', () async {
    // Otherwise an error that fired a hundred times while the window was full
    // is reported later as a single occurrence with nothing swallowed, and the
    // count is billed to whichever unrelated line happens to be admitted next.
    installErrorReporting();
    errorReportingClockMs = () => 0;

    // Admitted once, so it has a tally...
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('mine')));
    // ...then fill the window with other texts and keep firing it.
    for (var i = 0; i < 10; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('other $i')));
    }
    errorReportingClockMs = () => 10001;
    // Six, so the window is FULL: with five, `mine` takes the last slot and is
    // admitted, and the test would be asserting about a different path.
    for (var i = 0; i < 6; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('filler $i')));
    }
    for (var i = 0; i < 100; i++) {
      FlutterError.onError!(FlutterErrorDetails(exception: Exception('mine')));
    }
    errorReportingClockMs = () => 20002;
    FlutterError.onError!(FlutterErrorDetails(exception: Exception('mine')));
    await Future<void>.delayed(Duration.zero);

    final mine = platform.lines.where((l) => l.contains('mine')).last;
    expect(mine, contains('repeated 100 more time(s)'));
  });

  test('a frame from a package is kept, a dart: frame is not', () async {
    // The skip list has to match what the comment and the spec promise. A
    // failure inside a dependency is still best described by the dependency.
    installErrorReporting();

    FlutterError.onError!(
      FlutterErrorDetails(
        exception: StateError('deep'),
        stack: StackTrace.fromString(
          '#0      List.[] (dart:core-patch/growable_array.dart:264:36)\n'
          '#1      Parser.parse (package:some_dep/parser.dart:88:7)\n',
        ),
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(platform.lines.single, contains('package:some_dep/parser.dart:88'));
    expect(platform.lines.single, isNot(contains('dart:core-patch')));
  });

  test('a previous platform handler that throws does not escape', () async {
    // Its counterpart on the FlutterError side was guarded; this one was not,
    // and a throw here means nothing reports the original error.
    PlatformDispatcher.instance.onError = (_, _) => throw StateError('bad chain');
    installErrorReporting();

    late bool handled;
    expect(
      () => handled = PlatformDispatcher.instance.onError!(
        StateError('still mine'),
        StackTrace.current,
      ),
      returnsNormally,
    );
    await Future<void>.delayed(Duration.zero);

    expect(handled, isFalse);
    expect(platform.lines.single, contains('still mine'));
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
