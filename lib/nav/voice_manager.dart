import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/app_localizations.dart';
import '../util/app_logger.dart' show talker;
import '../util/device_localizations.dart';
import 'maneuver_cue_scheduler.dart';
import 'route.dart';
import 'voice_settings.dart';

const _prefsKeyEnabled = 'voice_enabled';
const _prefsKeyVolume = 'voice_volume';
const _prefsKeyMusicPolicy = 'voice_music_policy';
const _prefsKeyLegacyMode = 'voice_mode';

/// Turn-by-turn voice guidance with locally localized maneuver phrases.
///
/// [ManeuverCueScheduler] de-duplicates the 300/150/60 metre cues. All speech
/// uses the native voice owner with one shared, persisted settings snapshot.
/// Every [ManeuverType] has a local phrase, independent of the SDK annotation
/// language, so English route annotations never change Russian voice guidance.
/// The dash glyph is a separate concern: `NavLoop` sends [Maneuver.dashCode].
class VoiceManager {
  VoiceManager();
  static final VoiceManager instance = VoiceManager();

  final _cueScheduler = ManeuverCueScheduler();
  VoiceSettings _settings = const VoiceSettings();
  Future<void>? _initialization;
  Future<void>? _writeTail;
  bool _initialized = false;
  bool _settingsChanged = false;

  VoiceSettings get settings => _settings;

  /// Loads settings once, before any announcements are allowed.
  Future<void> initialize() => _initialization ??= _writeTail = _load();

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final enabled = prefs.get(_prefsKeyEnabled);
      final legacyMode = prefs.get(_prefsKeyLegacyMode);
      final loaded = VoiceSettings(
        enabled: enabled is bool ? enabled : legacyMode != 'off',
        volume: switch (prefs.get(_prefsKeyVolume)) {
          'low' => VoiceVolume.low,
          'high' => VoiceVolume.high,
          _ => VoiceVolume.medium,
        },
        musicPolicy: switch (prefs.get(_prefsKeyMusicPolicy)) {
          'pauseAndResume' => VoiceMusicPolicy.pauseAndResume,
          'unchanged' => VoiceMusicPolicy.unchanged,
          _ => VoiceMusicPolicy.duck,
        },
      );
      // A choice made during loading must not be replaced by the disk snapshot.
      if (!_settingsChanged) _settings = loaded;
      if (prefs.containsKey(_prefsKeyLegacyMode)) {
        await _persist(_settings, removeLegacy: true);
      }
    } catch (e, st) {
      talker.error('[Voice] could not read stored settings', e, st);
    } finally {
      _initialized = true;
    }
  }

  /// Applies settings immediately and serializes their persistence.
  ///
  /// Disabling stops speech before any disk access. Volume and music policy
  /// apply to the next phrase without restarting the current one.
  Future<void> applySettings(VoiceSettings settings) async {
    _settings = settings;
    _settingsChanged = true;
    final stop = settings.enabled ? null : _stop();
    final previousWrite = _writeTail ?? initialize();
    final write = previousWrite.then((_) => _persist(settings));
    _writeTail = write;
    await write;
    if (stop != null) await stop;
  }

  Future<void> _persist(
    VoiceSettings snapshot, {
    bool removeLegacy = false,
  }) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (!await prefs.setBool(_prefsKeyEnabled, snapshot.enabled)) {
        throw StateError('Could not store $_prefsKeyEnabled');
      }
      if (!await prefs.setString(_prefsKeyVolume, snapshot.volume.name)) {
        throw StateError('Could not store $_prefsKeyVolume');
      }
      if (!await prefs.setString(
        _prefsKeyMusicPolicy,
        snapshot.musicPolicy.name,
      )) {
        throw StateError('Could not store $_prefsKeyMusicPolicy');
      }
      if (removeLegacy && !await prefs.remove(_prefsKeyLegacyMode)) {
        throw StateError('Could not remove $_prefsKeyLegacyMode');
      }
    } catch (e, st) {
      // Keep the runtime choice, and leave the chain usable by later writes.
      talker.error('[Voice] could not store settings', e, st);
    }
  }

  Future<void> _stop() async {
    try {
      await DashEngine.instance.stopVoice();
    } catch (e, st) {
      talker.error('[Voice] could not stop speech', e, st);
    }
  }

  bool get _canSpeak => _initialized && _settings.enabled;

  /// Call every nav tick. [maneuver] is the upcoming turn (null = none),
  /// [distanceM] the distance to it, [remainingM] distance to the destination.
  Future<void> maybeAnnounce(
    Maneuver? maneuver,
    double distanceM,
    double remainingM,
  ) async {
    if (!_canSpeak) return;
    final cue = _cueScheduler.next(
      maneuver: maneuver,
      distanceM: distanceM,
      remainingM: remainingM,
    );
    if (cue == null) return;

    final l10n = deviceLocalizations();
    final text = switch (cue) {
      ManeuverCue.advance300 => l10n.voiceIn(
        l10n.voiceDistanceM(300),
        _turnPhrase(l10n, maneuver!),
      ),
      ManeuverCue.advance150 => l10n.voiceIn(
        l10n.voiceDistanceM(150),
        _turnPhrase(l10n, maneuver!),
      ),
      ManeuverCue.now => l10n.voiceNow(_turnPhrase(l10n, maneuver!)),
      ManeuverCue.arrived => l10n.voiceArrived,
    };
    await _speak(text);
  }

  /// Sustained dash-connection-loss alert — see `DashConnectionAlertController`,
  /// the sole caller. Unlike [maybeAnnounce], this fires regardless of active
  /// navigation (with no destination set, too) since it's about the link to
  /// the dash itself, not a turn. Uses the same enabled gate and playback
  /// settings as maneuver announcements.
  Future<void> announceConnectionLost() async {
    if (!_canSpeak) return;
    await _speak(deviceLocalizations().voiceDashDisconnected);
  }

  /// Companion to [announceConnectionLost] — called once the dash is back,
  /// only if the loss was actually announced (see the controller).
  Future<void> announceConnectionRestored() async {
    if (!_canSpeak) return;
    await _speak(deviceLocalizations().voiceDashReconnected);
  }

  Future<void> _speak(String text) async {
    if (!_canSpeak) return;
    final snapshot = _settings;
    try {
      // No await before submission: an intervening off must never enqueue speech.
      // Cancelled and focusDenied are normal terminal results, with no fallback.
      await DashEngine.instance.speakVoice(
        text: text,
        languageTag: deviceLocalizations().localeName == 'ru'
            ? 'ru-RU'
            : 'en-US',
        volume: snapshot.volume.gain,
        policy: snapshot.musicPolicy,
      );
    } catch (e, st) {
      // Navigation is fire-and-forget; a failed cue stays consumed.
      talker.error('[Voice] could not speak announcement', e, st);
    }
  }

  /// Reset trip state when nav starts/stops so the next route announces cleanly.
  void resetTrip() => _cueScheduler.reset();

  String _turnPhrase(AppLocalizations l10n, Maneuver m) => switch (m.type) {
    ManeuverType.straight => l10n.voiceTurnContinue,
    ManeuverType.slightLeft => l10n.voiceTurnSlightLeft,
    ManeuverType.slightRight => l10n.voiceTurnSlightRight,
    ManeuverType.left => l10n.voiceTurnLeft,
    ManeuverType.right => l10n.voiceTurnRight,
    ManeuverType.hardLeft => l10n.voiceTurnHardLeft,
    ManeuverType.hardRight => l10n.voiceTurnHardRight,
    ManeuverType.forkLeft => l10n.voiceTurnForkLeft,
    ManeuverType.forkRight => l10n.voiceTurnForkRight,
    ManeuverType.uturnLeft => l10n.voiceTurnUturnLeft,
    ManeuverType.uturnRight => l10n.voiceTurnUturnRight,
    ManeuverType.enterRoundabout => l10n.voiceTurnEnterRoundabout,
    ManeuverType.leaveRoundabout =>
      m.roundaboutExitNumber != null && m.roundaboutExitNumber! > 0
          ? l10n.voiceTurnLeaveRoundaboutNumber(m.roundaboutExitNumber!)
          : l10n.voiceTurnLeaveRoundabout,
    ManeuverType.boardFerry => l10n.voiceTurnBoardFerry,
    ManeuverType.leaveFerry => l10n.voiceTurnLeaveFerry,
    ManeuverType.exitLeft => l10n.voiceTurnExitLeft,
    ManeuverType.exitRight => l10n.voiceTurnExitRight,
    ManeuverType.finish || ManeuverType.arrive => l10n.voiceTurnArrive,
    ManeuverType.waypoint => l10n.voiceTurnWaypoint,
  };
}
