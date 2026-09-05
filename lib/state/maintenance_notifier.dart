import 'dart:math';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/app_localizations.dart';
import '../models/garage_models.dart';
import '../util/device_localizations.dart';

const _channelId = 'maintenance';
const _notifId = 4201;
const _prefsKeyNotified = 'maint_notified_ids';

/// Posts a reminder when a maintenance item comes due. Ported from
/// `data/MaintenanceNotifier.kt` — same de-dupe rule (only buzzes when an
/// item newly crosses into "due"; servicing it clears the flag so it can
/// remind again next interval), called from [GarageController] after every
/// reload rather than from a background service.
class MaintenanceNotifier {
  MaintenanceNotifier._();
  static final MaintenanceNotifier instance = MaintenanceNotifier._();

  final _plugin = FlutterLocalNotificationsPlugin();

  /// The pending *future* of initialization, not a "done" flag.
  ///
  /// A `bool` set before the `await` (which is what this was) let a second
  /// [check] sail past initialization while the first was still inside
  /// `_plugin.initialize()`, and reach `_plugin.show()` on a plugin that had not
  /// finished setting up its notification channel. Same reasoning — and the same
  /// shape — as `AppDatabase._opening`, including not caching a failure.
  Future<void>? _initializing;

  /// Serialises [check]. [GarageController._reload] documents that it runs
  /// concurrently and fires this through `unawaited` at the end of every run, so
  /// two checks overlapping is ordinary, not exotic. Both would then read
  /// `_prefsKeyNotified` before either wrote it, compute the same `newlyDue`
  /// against the same base, and both buzz — with the later write losing the
  /// earlier one. Run in order and the second sees the first's write, finds
  /// nothing newly due, and returns.
  Future<void> _queue = Future.value();

  Future<void> _ensureInitialized() async {
    final pending = _initializing ??= _initialize();
    try {
      await pending;
    } catch (_) {
      // Never cache a failed initialization — the next check() gets a fresh try.
      if (identical(_initializing, pending)) _initializing = null;
      rethrow;
    }
  }

  Future<void> _initialize() async {
    final l10n = deviceLocalizations();
    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    await _plugin.initialize(settings: const InitializationSettings(android: android));
    await _plugin
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(AndroidNotificationChannel(
          _channelId,
          l10n.maintChannelName,
          description: l10n.maintChannelDesc,
          importance: Importance.defaultImportance,
        ));
  }

  bool _isDue(MaintenanceItem m, int odo) {
    final distanceDue = (m.lastDoneOdoKm + m.intervalKm - odo) < m.intervalKm * 0.25;
    final schedule = Himalayan450MaintenanceSchedule.forItem(m);
    final months = schedule?.intervalMonths;
    if (months == null) return distanceDue;
    final lastDone = DateTime.fromMillisecondsSinceEpoch(m.lastDoneDateMs);
    final dueAt = DateTime(lastDone.year, lastDone.month + months, lastDone.day);
    final remainingDays = (dueAt.difference(DateTime.now()).inMilliseconds / 86400000).ceil();
    return distanceDue || remainingDays < months * 30 * 0.25;
  }

  /// Posts the reminder if anything newly crossed into "due". Serialised — see
  /// [_queue].
  Future<void> check(List<MaintenanceItem> items, int odometer) {
    final result = _queue.then((_) => _check(items, odometer));
    // The chain has to survive a failure: a rejected `_queue` would make every
    // later check() fail at once with the *previous* call's error. Callers still
    // see their own failure through `result`.
    _queue = result.catchError((Object _) {});
    return result;
  }

  Future<void> _check(List<MaintenanceItem> items, int odometer) async {
    await _ensureInitialized();

    final due = items.where((m) => _isDue(m, odometer)).toList();
    final dueIds = due.map((m) => m.id.toString()).toSet();

    final prefs = await SharedPreferences.getInstance();
    final notified = (prefs.getStringList(_prefsKeyNotified) ?? const []).toSet();
    // Drop serviced items so they can remind again next interval, but keep
    // still-due items already flagged.
    final stillKnownDue = notified.intersection(dueIds);

    final newlyDue = dueIds.difference(notified);
    if (newlyDue.isEmpty) {
      await prefs.setStringList(_prefsKeyNotified, stillKnownDue.toList());
      return;
    }

    final enabled = await _plugin
            .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
            ?.areNotificationsEnabled() ??
        true;
    if (!enabled) {
      await prefs.setStringList(_prefsKeyNotified, stillKnownDue.toList()); // not flagged → can fire later
      return;
    }
    await prefs.setStringList(_prefsKeyNotified, dueIds.toList()); // posting now → remember all due

    final l10n = deviceLocalizations();
    final (title, body) = _buildText(l10n, due, odometer);
    await _plugin.show(
      id: _notifId,
      title: title,
      body: body,
      notificationDetails: NotificationDetails(
        android: AndroidNotificationDetails(
          _channelId,
          l10n.maintChannelName,
          channelDescription: l10n.maintChannelDesc,
          styleInformation: const BigTextStyleInformation(''),
          priority: Priority.defaultPriority,
        ),
      ),
    );
  }

  (String, String) _buildText(AppLocalizations l10n, List<MaintenanceItem> due, int odo) {
    String line(MaintenanceItem m) {
      final remaining = m.lastDoneOdoKm + m.intervalKm - odo;
      final schedule = Himalayan450MaintenanceSchedule.forItem(m);
      int? remainingDays;
      if (schedule?.intervalMonths != null) {
        final lastDone = DateTime.fromMillisecondsSinceEpoch(m.lastDoneDateMs);
        final dueAt = DateTime(lastDone.year, lastDone.month + schedule!.intervalMonths!, lastDone.day);
        remainingDays = (dueAt.difference(DateTime.now()).inMilliseconds / 86400000).ceil();
      }
      if (remainingDays != null && remainingDays < 0) return l10n.maintLineOverdueByDate(m.name);
      if (remaining < 0) return l10n.maintLineOverdueKm(m.name, -remaining);
      if (remainingDays != null) {
        return l10n.maintLineDueKmDays(m.name, remaining, max(remainingDays, 0));
      }
      return l10n.maintLineDueKm(m.name, remaining);
    }

    if (due.length == 1) return (l10n.maintNotifTitleSingle, line(due.first));
    return (l10n.maintNotifTitleMultiple(due.length), due.map(line).join('\n'));
  }
}
