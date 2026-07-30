import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../l10n/app_localizations.dart';
import '../models/route_preview_args.dart';
import '../models/shared_location.dart';
import '../nav/geo_point.dart';
import '../state/dash_engine_state.dart';
import '../state/rides_controller.dart';
import '../state/saved_destinations_controller.dart';
import '../state/vehicle_store.dart';
import '../util/current_position.dart';

/// Landing tab: connect status, saved destinations, recent rides. Ports
/// `HomeScreen.kt`.
class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  GeoPoint? _origin;

  @override
  void initState() {
    super.initState();
    _loadOrigin();
  }

  Future<void> _loadOrigin() async {
    final origin = await currentPosition();
    if (!mounted) return;
    setState(() => _origin = origin);
  }

  String _formatDistance(AppLocalizations l10n, double meters) {
    return meters >= 1000
        ? l10n.unitKm((meters / 1000).toStringAsFixed(1))
        : l10n.unitM(meters.round().toString());
  }

  @override
  Widget build(BuildContext context) {
    final engine = ref.watch(dashEngineStateProvider);
    final saved = ref.watch(savedDestinationsControllerProvider);
    final rides = ref.watch(ridesControllerProvider);
    final vehicle = ref.watch(vehicleStoreProvider).active;
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context)!;

    final connected = engine.stage == DashStage.streaming;
    final searching = engine.stage == DashStage.connecting || engine.stage == DashStage.authenticating;
    final (statusLabel, statusSub, statusColor) = connected
        ? (l10n.homeStatusConnected, l10n.homeStatusConnectedSub, theme.colorScheme.primary)
        : searching
            ? (l10n.homeStatusSearching, l10n.homeStatusSearchingSub, Colors.amber)
            : (l10n.homeStatusOffline, l10n.homeStatusOfflineSub, theme.colorScheme.onSurfaceVariant);

    return Scaffold(
      appBar: AppBar(title: const Text('SnatchDash')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  CircleAvatar(radius: 28, backgroundColor: statusColor.withValues(alpha: 0.15), child: Icon(Icons.two_wheeler, color: statusColor)),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(children: [
                          Icon(Icons.circle, size: 10, color: statusColor),
                          const SizedBox(width: 6),
                          Text(statusLabel, style: theme.textTheme.titleMedium),
                        ]),
                        Text(statusSub, style: theme.textTheme.bodySmall),
                        const SizedBox(height: 6),
                        Chip(label: Text(vehicle.title), avatar: const Icon(Icons.two_wheeler, size: 16)),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.navigation_outlined),
                title: Text(l10n.homeStartNavigation),
                subtitle: Text(l10n.homeStartNavigationSub),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/home/route'),
              ),
              const Divider(height: 1),
              ListTile(
                leading: Icon(connected ? Icons.dashboard_outlined : Icons.wifi),
                title: Text(connected ? l10n.homeDashView : l10n.homeConnectToDash),
                subtitle: Text(connected ? l10n.homeDashViewSub : l10n.homeConnectToDashSub),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/home/dash'),
              ),
            ]),
          ),
          const SizedBox(height: 16),
          Text(l10n.homeSavedDestinations, style: theme.textTheme.labelLarge),
          const SizedBox(height: 8),
          if (saved.isEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Text(l10n.homeNoSavedDestinations),
              ),
            )
          else
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (var i = 0; i < saved.length; i++) ...[
                    if (i > 0) const SizedBox(width: 12),
                    _SavedPlaceCard(
                      location: saved[i],
                      distanceLabel: _origin == null
                          ? null
                          : _formatDistance(l10n, GeoPoint.distMeters(_origin!, GeoPoint(saved[i].lat, saved[i].lng))),
                      onTap: () {
                        context.push(
                          '/home/route-preview',
                          extra: RoutePreviewArgs(
                            destinationName: saved[i].name,
                            destination: GeoPoint(saved[i].lat, saved[i].lng),
                            origin: _origin,
                          ),
                        );
                      },
                    ),
                  ],
                ],
              ),
            ),
          const SizedBox(height: 16),
          Text(l10n.homeRidesLabel, style: theme.textTheme.labelLarge),
          const SizedBox(height: 8),
          Card(
            child: rides.isEmpty
                ? ListTile(
                    leading: const Icon(Icons.history),
                    title: Text(l10n.homeNoRidesYet),
                    subtitle: Text(l10n.homeRidesAutoSub),
                  )
                : Column(children: [
                    ListTile(
                      leading: const Icon(Icons.history),
                      title: Text(l10n.homeRidesSummary(
                        rides.length,
                        rides.fold(0.0, (s, r) => s + r.distanceKm).toStringAsFixed(1),
                      )),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push('/home/rides'),
                    ),
                  ]),
          ),
        ],
      ),
    );
  }
}

class _SavedPlaceCard extends StatelessWidget {
  const _SavedPlaceCard({required this.location, required this.distanceLabel, required this.onTap});

  final SavedLocation location;

  /// Formatted distance from the rider's current position, or null while no
  /// GPS fix is available yet.
  final String? distanceLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: 140,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: theme.colorScheme.outlineVariant, width: 2),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              location.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            if (distanceLabel != null) Text(distanceLabel!, style: theme.textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}
