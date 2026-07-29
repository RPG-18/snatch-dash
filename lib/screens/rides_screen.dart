import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../l10n/app_localizations.dart';
import '../state/rides_controller.dart';

/// Ride history list with per-ride stats + delete. Ports `RidesViewModel` +
/// `RidesScreen.kt`. Rides are populated once the ride recorder lands
/// (Phase 4/5) — the list, stats, and delete flow here are real.
class RidesScreen extends ConsumerWidget {
  const RidesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rides = ref.watch(ridesControllerProvider);
    final l10n = AppLocalizations.of(context)!;
    final dateFormat = DateFormat.yMd(Localizations.localeOf(context).toString());

    return Scaffold(
      appBar: AppBar(title: Text(l10n.ridesTitle)),
      body: rides.isEmpty
          ? Center(child: Text(l10n.homeNoRidesYet))
          : ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: rides.length,
              separatorBuilder: (context, index) => const SizedBox(height: 8),
              itemBuilder: (context, i) {
                final ride = rides[i];
                final start = DateTime.fromMillisecondsSinceEpoch(ride.startMs);
                final minutes = ride.durationSec ~/ 60;
                return Card(
                  child: ListTile(
                    leading: const Icon(Icons.route_outlined),
                    title: Text(l10n.rideDurationSummary(ride.distanceKm.toStringAsFixed(1), minutes)),
                    subtitle: Text(
                      l10n.rideDateAvgSpeed(dateFormat.format(start), ride.avgSpeedKmh.toStringAsFixed(0)),
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () => ref.read(ridesControllerProvider.notifier).deleteRide(ride),
                    ),
                  ),
                );
              },
            ),
    );
  }
}
