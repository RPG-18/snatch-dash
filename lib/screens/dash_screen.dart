import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../l10n/app_localizations.dart';
import '../map/opendash_map.dart';
import '../nav/geo_point.dart';
import '../state/dash_engine_state.dart';
import '../state/offline_maps_controller.dart';
import '../state/route_controller.dart';
import '../util/location_permission.dart';
import 'debug/maneuver_glyph_probe.dart';

/// Yandex map with the active route and rider position, plus
/// connect/disconnect controls. Ports `DashViewModel` + `DashScreen.kt` —
/// bound to the native engine's EventChannel (`dashEngineStateProvider`) and
/// the `yandex_mapkit` map.
///
/// **Not a mirror of the dash frame.** The dash renders MapLibre from a local
/// pack; this screen renders Yandex over the network — different style, detail
/// and offline behaviour. See spec/dash_screen.md's TODO about moving the
/// in-app map to MapLibre once the offline-map MVP lands.
class DashScreen extends ConsumerWidget {
  const DashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final engine = ref.watch(dashEngineStateProvider);
    final route = ref.watch(routeControllerProvider).route;
    final destination = ref.watch(routeControllerProvider).destination;
    // `!= false` on purpose: null means the registry has not been read yet, and
    // flashing "no maps" over a working one is worse than saying nothing.
    final hasMaps = ref.watch(hasInstalledPacksProvider) != false;
    final l10n = AppLocalizations.of(context)!;

    final dest = destination != null
        ? GeoPoint(destination.lat, destination.lng)
        : null;

    return Scaffold(
      appBar: AppBar(
        title: Text(_stageLabel(l10n, engine.stage)),
        actions: [
          IconButton(
            icon: Icon(engine.headingUp ? Icons.explore : Icons.explore_outlined),
            tooltip: l10n.dashHeadingUpTooltip,
            onPressed: () => DashEngine.instance.toggleHeadingUp(),
          ),
        ],
      ),
      body: Stack(
        children: [
          OpenDashMap(
            riderLat: engine.riderLat,
            riderLng: engine.riderLng,
            riderBearing: engine.riderBearing ?? 0,
            dest: dest,
            routePoints: route?.geometry ?? const [],
            jamSegments: route?.jamSegments ?? const [],
            navMode: true,
          ),
          if (kDebugMode)
            Positioned(
              top: 12,
              right: 12,
              child: ManeuverGlyphProbe(riderLat: engine.riderLat, riderLng: engine.riderLng),
            ),
          // Top-centre status chips, stacked so they can never overlap each
          // other. The bottom of the screen is spoken for: the remaining-km
          // pill sits at 96 and the media/call cards at 16, so a chip placed
          // there would land on top of them in exactly the situation it is
          // needed — connected without packs while music plays.
          if (!hasMaps || engine.gpsLost || engine.gpsWeak)
            Positioned(
              top: 12,
              left: 0,
              right: 0,
              child: Column(
                children: [
                  if (engine.gpsLost || engine.gpsWeak)
                    Chip(label: Text(engine.gpsLost ? l10n.dashGpsLost : l10n.dashGpsWeak)),
                  // Connecting without packs is allowed (media, calls and the
                  // link itself don't need maps), so the dash shows an empty
                  // frame. This chip is the only place that says why — without
                  // it a blank instrument cluster reads as a failure.
                  if (!hasMaps)
                    ActionChip(
                      avatar: const Icon(Icons.map_outlined, size: 18),
                      label: Text(l10n.dashNoOfflineMaps),
                      onPressed: () => context.push('/more/offline-maps'),
                    ),
                ],
              ),
            ),
          if (engine.remainingKm != null)
            Positioned(
              bottom: 96,
              left: 0,
              right: 0,
              child: Center(
                child: Chip(label: Text(l10n.dashDistanceRemaining(engine.remainingKm!.toStringAsFixed(1)))),
              ),
            ),
          if (engine.incomingCaller != null)
            Positioned(
              top: 12,
              left: 16,
              right: 16,
              child: Card(
                color: Theme.of(context).colorScheme.primaryContainer,
                child: ListTile(
                  leading: const Icon(Icons.call),
                  title: Text(engine.incomingCaller!),
                  trailing: Wrap(spacing: 4, children: [
                    IconButton(
                      icon: const Icon(Icons.call_end),
                      onPressed: () => DashEngine.instance.hangupCall(),
                    ),
                    IconButton(
                      icon: const Icon(Icons.call),
                      onPressed: () => DashEngine.instance.answerCall(),
                    ),
                  ]),
                ),
              ),
            )
          else if (engine.nowPlayingTitle != null)
            Positioned(
              bottom: 16,
              left: 16,
              right: 16,
              child: Card(
                child: ListTile(
                  leading: const Icon(Icons.music_note),
                  title: Text(engine.nowPlayingTitle!, maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: Wrap(spacing: 4, children: [
                    IconButton(
                      icon: const Icon(Icons.skip_previous),
                      onPressed: () => DashEngine.instance.skipPrevious(),
                    ),
                    IconButton(
                      icon: const Icon(Icons.skip_next),
                      onPressed: () => DashEngine.instance.skipNext(),
                    ),
                  ]),
                ),
              ),
            ),
        ],
      ),
      floatingActionButton: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          if (dest != null)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FloatingActionButton.small(
                heroTag: 'exitNav',
                tooltip: l10n.dashExitNavigationTooltip,
                onPressed: () {
                  ref.read(routeControllerProvider.notifier).exitNavigation();
                  context.go('/home');
                },
                child: const Icon(Icons.close),
              ),
            ),
          FloatingActionButton.extended(
            heroTag: 'connect',
            onPressed: () async {
              if (engine.stage == DashStage.idle || engine.stage == DashStage.error) {
                if (!await ensureLocationPermission()) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text(l10n.dashGpsPermissionRequired)),
                    );
                  }
                  return;
                }
                DashEngine.instance.connect();
              } else {
                DashEngine.instance.disconnect();
              }
            },
            icon: Icon(_isConnected(engine.stage) ? Icons.link_off : Icons.link),
            label: Text(_isConnected(engine.stage) ? l10n.dashDisconnect : l10n.dashConnect),
          ),
        ],
      ),
    );
  }

  bool _isConnected(DashStage stage) =>
      stage != DashStage.idle && stage != DashStage.error;

  String _stageLabel(AppLocalizations l10n, DashStage stage) => switch (stage) {
        DashStage.idle => l10n.dashStageOffline,
        DashStage.connecting => l10n.dashStageConnecting,
        DashStage.authenticating => l10n.dashStageAuthenticating,
        DashStage.ready => l10n.dashStageReady,
        DashStage.streaming => l10n.dashStageConnected,
        DashStage.error => l10n.dashStageError,
      };
}
