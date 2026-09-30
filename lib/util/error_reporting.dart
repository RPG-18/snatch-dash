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
/// **Limited here, at the source, and only here.** The rule is the native
/// one — an identical line at most once per [_collapseWindowMs], at most
/// [_windowBudget] lines admitted per window — but it is applied in Dart
/// because only Dart is upstream of BOTH sinks. `CollapsingRideLog` gates the
/// ride file alone, so with the limit on that side `app_log.txt` stayed
/// unprotected against an exception whose text varies per frame (a widget key,
/// a hash), and it is capped at 10 MB with a single rotation — 15 minutes of
/// that overwrites the first occurrence, the evidence this whole path exists
/// to keep. Running the rule on both sides instead just makes the downstream
/// counters dead: a repeat swallowed here never reaches the native limiter, so
/// its `repeats` would be permanently zero for this tag.
///
/// **Errors here must not throw.** This runs from the framework's own error
/// path, and an exception escaping it replaces a diagnosable failure with an
/// undiagnosable one.
void installErrorReporting() {
  // Installing twice chains this handler onto itself: one error then yields two
  // Talker entries and two channel calls, and the second is counted as a repeat
  // of the first, inflating every count this file reports. The test suite has
  // to undo the install for exactly that reason; the function should not need
  // a careful caller to stay correct.
  if (_installed) return;
  _installed = true;

  final previous = FlutterError.onError;
  FlutterError.onError = (details) {
    // The default first: it is what prints the full dump with the widget
    // stack, and that dump is what `flutter run` showed when nothing else
    // did. Reporting must add sinks, never remove the one that worked.
    // Guarded separately from ours, so neither half can take the other down.
    try {
      previous?.call(details);
    } catch (_) {}
    try {
      // `exceptionAsString()` and `toDescription()` run INSIDE the guard, not
      // as arguments evaluated outside it: an exception whose `toString`
      // throws is a real thing — a `toString` reaching disposed state is how
      // this file's own motivating bug behaved — and from out there it would
      // escape the handler.
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
    //
    // Guarded like its `FlutterError` counterpart: a predecessor that throws
    // would escape from here, and then nothing reports the original error —
    // against this file's own contract.
    try {
      return previousPlatform?.call(error, stack) ?? false;
    } catch (_) {
      return false;
    }
  };
}

bool _installed = false;

/// One collapsed line: when it last went through, and how many identical ones
/// have been swallowed since.
class _Seen {
  _Seen(this.atMs);
  int atMs;
  int suppressed = 0;
}

/// Lines admitted recently, keyed by everything that distinguishes them.
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

/// How long an identical line stays collapsed, and how many lines a window
/// admits at all. Both match `MapLibreLogBridge`, so a reader of the ride file
/// does not have to hold two different meanings of "recently".
const _collapseWindowMs = 10 * 1000;
const _windowBudget = 6;

int _windowStartMs = 0;
int _admitted = 0;
int _droppedOverBudget = 0;

/// Compiled once. It runs on the hot path — a build error arrives once per
/// frame — and Dart does not cache `RegExp` objects.
final _newlineRun = RegExp(r'\s*\n\s*');

/// Whether this line goes through, and what it has to carry if it does: the
/// repeats swallowed since it last did, and the lines the budget refused since
/// the last one went through. Null means "this one is itself a repeat, or the
/// window is full".
({int repeats, int dropped})? _admit(String key) {
  final now = errorReportingClockMs();
  if (now - _windowStartMs >= _collapseWindowMs) {
    _windowStartMs = now;
    _admitted = 0;
  }
  final seen = _lastSeen[key];
  if (seen != null && now - seen.atMs < _collapseWindowMs) {
    seen.suppressed++;
    return null;
  }
  if (_admitted >= _windowBudget) {
    // Counted once, and against the right thing. A line already known keeps
    // its own tally, so an error that fired a hundred times while the window
    // was full is not reported later as one occurrence with nothing swallowed;
    // a line never seen has no tally to keep, and goes to the window's.
    if (seen != null) {
      seen.suppressed++;
    } else {
      // Occurrences, not distinct texts — the same count the native rule
      // keeps, and what "line(s) not written" says.
      _droppedOverBudget++;
    }
    return null;
  }
  _admitted++;
  final repeats = seen?.suppressed ?? 0;
  if (seen != null) {
    seen
      ..atMs = now
      ..suppressed = 0;
  } else {
    _lastSeen[key] = _Seen(now);
    // Bounded: a source producing endless DISTINCT lines must not turn this
    // into a leak. Dropping the oldest is right — they are the ones already
    // past the window anyway.
    if (_lastSeen.length > 64) {
      final oldest = _lastSeen.entries.reduce(
        (a, b) => a.value.atMs <= b.value.atMs ? a : b,
      );
      _lastSeen.remove(oldest.key);
    }
  }
  final dropped = _droppedOverBudget;
  _droppedOverBudget = 0;
  return (repeats: repeats, dropped: dropped);
}

@visibleForTesting
void resetErrorReportingForTest() {
  _lastSeen.clear();
  _windowStartMs = 0;
  _admitted = 0;
  _droppedOverBudget = 0;
  _installed = false;
  errorReportingClockMs = () => _stopwatch.elapsedMilliseconds;
}

/// The first stack frame that is not framework plumbing, trimmed to fit a log
/// line.
///
/// **The ride file gets this and nothing else of the stack.** A full trace does
/// not belong in a file whose other lines are one each, but a bare message does
/// not either: `A TextEditingController was used after being disposed` without
/// a frame is the same dead end that cost the day on 28.09, and on a release
/// build `diag/` is the only log there is — `app_log.txt`, where Talker keeps
/// the whole trace, needs `run-as` and a debug build.
String? _topFrame(StackTrace? stack) {
  if (stack == null) return null;
  for (final raw in stack.toString().split('\n')) {
    final line = raw.trim();
    if (line.isEmpty) continue;
    // Skipped, because these are where the throw was NOTICED, not where it
    // came from: the top of a widget error is always
    // `package:flutter/src/widgets/framework.dart`, the same for every one of
    // them, naming nothing to open. Everything else is kept, our own code and
    // a third-party package alike — a failure inside a dependency is still
    // best described by the dependency it is in.
    if (line.contains('package:flutter/') || line.contains('(dart:')) {
      continue;
    }
    // Trimmed from the LEFT. A frame reads `#0  Function (file:line:col)`, so
    // the end is the part worth having — cutting the tail keeps the frame
    // number and throws away the file and the line number, which is the whole
    // reason for carrying a frame at all.
    return line.length > 120 ? '…${line.substring(line.length - 119)}' : line;
  }
  return null;
}

void _report(
  String source,
  String message,
  StackTrace? stack, {
  String? context,
  String? library,
}) {
  // Keyed on the source too. Without it the same text arriving once through
  // `FlutterError.onError` and once through the platform handler counts as one
  // occurrence — so the reader never learns the failure has an async escape
  // path as well as a build path, and the repeat count is filed against
  // whichever source happens to report next.
  final admitted = _admit('$source|$library|$context|$message');
  if (admitted == null) return;

  // Flattened AFTER the decision, not before: `exceptionAsString()` is
  // multi-line for exactly the error this was built for, and scanning it for
  // the 59 occurrences per second that are about to be discarded is work for
  // nothing. `RideDiagnostics` stamps only the first line, so the rest would
  // land with no timestamp, no `+NNNms` and no `WARN`, invisible to the grep
  // the ride file exists for.
  final where = [
    if (library != null && library.isNotEmpty) library,
    if (context != null && context.isNotEmpty) context,
  ].join(' / ');
  final flat = message.replaceAll(_newlineRun, ' ⏎ ').trim();
  final line = StringBuffer(where.isEmpty ? flat : '$flat ($where)');
  // The counts travel with the next line that goes through. The tail is lost,
  // exactly as it is natively: a burst that simply stops leaves its last
  // repeats uncounted, because only the next occurrence reports them.
  const seconds = _collapseWindowMs ~/ 1000;
  if (admitted.repeats > 0) {
    line.write(
      ' (repeated ${admitted.repeats} more time(s) in the last ${seconds}s)',
    );
  }
  if (admitted.dropped > 0) {
    line.write(
      ' (+${admitted.dropped} further line(s) not written — over the '
      '$_windowBudget-per-${seconds}s budget)',
    );
  }
  final reported = line.toString();

  // Guarded one at a time: a failure reaching the ride file must not also cost
  // the Talker entry, and the whole point is that this path cannot itself be
  // the thing that goes silent.
  try {
    talker.error('[$source] $reported', null, stack);
  } catch (_) {
    // Nothing useful left to do — the logger is the thing that failed.
  }
  try {
    final frame = _topFrame(stack);
    // Fire and forget: the method channel answers on the platform thread and
    // nothing here waits for it.
    unawaited(
      DashEngine.instance
          .rideError('$source: $reported${frame == null ? '' : ' @ $frame'}')
          .catchError((Object _) {}),
    );
  } catch (_) {
    // The channel is not attached yet (errors before the plugin registers) or
    // the engine is gone. Talker still has it.
  }
}
