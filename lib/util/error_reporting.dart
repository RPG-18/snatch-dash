import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import 'app_logger.dart';

/// Sends Flutter's own failures to Talker and to the ride file.
///
/// **Until 2026-09-29 they went nowhere.** Nothing installed
/// [FlutterError.onError] or a zone handler, so a framework exception reached
/// the console and stopped there — not Talker, not `app_log.txt`, and above
/// all not `diag/`, which is the only log a release build keeps.
///
/// What that cost: on 28.09 the settings screen went to a red error screen on
/// both phones. The visible assertion was a knock-on; the error that actually
/// broke it — `A TextEditingController was used after being disposed` — was
/// found only by attaching `flutter run`, after logs pulled from two phones
/// turned out to be empty. Hours on an instrument that did not exist.
///
/// **Two sinks, deliberately.** Talker gives the debug-build `app_log.txt` and
/// the in-app viewer their usual entry; [DashEngine.rideError] gives the ride
/// file the one line that survives a release. Neither replaces the other.
///
/// **Errors here must not throw.** This runs from the framework's own error
/// path, and an exception escaping it replaces a diagnosable failure with an
/// undiagnosable one.
void installErrorReporting() {
  final previous = FlutterError.onError;
  FlutterError.onError = (details) {
    // The default first: it is what prints the full dump with the widget
    // stack, and that dump is what `flutter run` showed when nothing else
    // did. Reporting must add sinks, never remove the one that worked.
    // Guarded separately from ours: if the framework's own dump throws, that must
    // not cost the sinks below, and vice versa.
    try {
      previous?.call(details);
    } catch (_) {}
    try {
      // `exceptionAsString()` and `toDescription()` run INSIDE the guard, not as
      // arguments evaluated outside it: an exception whose `toString` throws is a
      // real thing — a `toString` reaching disposed state is how this file's own
      // motivating bug behaved — and from out there it would escape the handler.
      _report(
        'FlutterError',
        details.exceptionAsString(),
        details.stack,
        context: details.context?.toDescription(),
        library: details.library,
      );
    } catch (_) {}
  };

  // The other half: errors that escape to the platform rather than to the
  // framework — an unawaited future that throws, a callback outside a widget
  // build. `FlutterError.onError` never sees those.
  final previousPlatform = PlatformDispatcher.instance.onError;
  PlatformDispatcher.instance.onError = (error, stack) {
    // `error.toString()` inside the guard, for the reason above.
    try {
      _report('PlatformDispatcher', error.toString(), stack);
    } catch (_) {}
    // `false` = not handled, so the process keeps its default behaviour. This
    // is a reporter, not a swallower; deciding to continue is a different
    // question from deciding to record.
    return previousPlatform?.call(error, stack) ?? false;
  };
}

/// One collapsed line: when it last went through, and how many identical ones
/// have been swallowed since.
class _Seen {
  _Seen(this.atMs);
  int atMs;
  int suppressed = 0;
}

/// Identical lines seen recently.
final _lastSeen = <String, _Seen>{};

/// Monotonic, deliberately. The window is a DURATION, and `DateTime.now()`
/// walks: an NTP step backwards silences every repeat for the length of the
/// step, one forwards empties the window at once. The native twin takes
/// `monotonicMs` for the same reason — see `MapLibreLogBridge.clockMs`, and
/// the wall-clock hysteresis bug CLAUDE.md records from the RTP review.
final _stopwatch = Stopwatch()..start();

/// The clock the window is measured with, replaceable so a test need not wait
/// 10 s out — the same seam `MapLibreLogBridge.clockMs` is, and for the same
/// reason: without it the only testable half is "a repeat is swallowed", never
/// "and the count comes back".
@visibleForTesting
int Function() errorReportingClockMs = () => _stopwatch.elapsedMilliseconds;

/// How long an identical line stays collapsed. Matches the native window, so
/// the two limits do not disagree about what "recently" means.
const _collapseWindowMs = 10 * 1000;

/// How many repeats were swallowed since this line last went through, or null
/// if it is itself a repeat and must not go through now.
int? _suppressedSince(String line) {
  final now = errorReportingClockMs();
  final seen = _lastSeen[line];
  if (seen != null) {
    if (now - seen.atMs < _collapseWindowMs) {
      seen.suppressed++;
      return null;
    }
    final carried = seen.suppressed;
    seen
      ..atMs = now
      ..suppressed = 0;
    return carried;
  }
  _lastSeen[line] = _Seen(now);
  // Bounded: a source producing endless DISTINCT lines must not turn this into
  // a leak. Dropping the oldest is right — they are the ones already past the
  // window anyway.
  if (_lastSeen.length > 64) {
    final oldest = _lastSeen.entries.reduce(
      (a, b) => a.value.atMs <= b.value.atMs ? a : b,
    );
    _lastSeen.remove(oldest.key);
  }
  return 0;
}

@visibleForTesting
void resetErrorReportingForTest() {
  _lastSeen.clear();
  errorReportingClockMs = () => _stopwatch.elapsedMilliseconds;
}

void _report(
  String source,
  String message,
  StackTrace? stack, {
  String? context,
  String? library,
}) {
  final where = [
    if (library != null && library.isNotEmpty) library,
    if (context != null && context.isNotEmpty) context,
  ].join(' / ');
  // Flattened: `exceptionAsString()` is multi-line for exactly the error this
  // was built for, and `RideDiagnostics` stamps only the first line — the rest
  // would land with no timestamp, no `+NNNms` and no `WARN`, invisible to the
  // grep the ride file exists for.
  final flat = message.replaceAll(RegExp(r'\s*\n\s*'), ' ⏎ ').trim();
  final line = where.isEmpty ? flat : '$flat ($where)';

  // Collapsed BEFORE either sink, not just the native one. `app_log.txt` is
  // capped at 10 MB with a single rotation, so an error repeating once per
  // frame would overwrite the first occurrence — the evidence this whole path
  // exists to keep — and would cost a platform-channel round trip per frame on
  // the thread the frame pipeline shares.
  //
  // Coarser than the native limiter: identical text only, no budget for
  // distinct messages. The native side still applies that half, and it is the
  // backstop for a source emitting endless DISTINCT lines, which this half
  // cannot bound.
  final suppressed = _suppressedSince(line);
  if (suppressed == null) return;
  // The count travels with the next line to go through. Collapsing here on the
  // same 10 s window means a repeat never reaches the native limiter, so ITS
  // `repeats` counter is permanently zero for this tag — and without this the
  // ride file could not tell three occurrences from a hundred thousand, which
  // for an error firing once per frame is most of the diagnosis.
  //
  // The tail is lost, exactly as it is natively: a burst that simply stops
  // leaves its last repeats uncounted, because only the next occurrence
  // reports them.
  final reported = suppressed == 0
      ? line
      : '$line (repeated $suppressed more time(s) in the last '
            '${_collapseWindowMs ~/ 1000}s)';

  // Guarded one at a time: a failure reaching the ride file must not also cost
  // the Talker entry, and the whole point is that this path cannot itself be
  // the thing that goes silent.
  try {
    talker.error('[$source] $reported', null, stack);
  } catch (_) {
    // Nothing useful left to do — the logger is the thing that failed.
  }
  try {
    // Fire and forget: the method channel answers on the platform thread and
    // nothing here waits for it. The native side rate-limits, because a build
    // error repeats once per frame.
    unawaited(
      DashEngine.instance
          .rideError('$source: $reported')
          .catchError((Object _) {}),
    );
  } catch (_) {
    // The channel is not attached yet (errors before the plugin registers) or
    // the engine is gone. Talker still has it.
  }
}
