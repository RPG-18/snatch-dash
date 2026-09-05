import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../state/garage_controller.dart';

/// Icon keys a maintenance item can carry, in the order the picker offers
/// them. Persisted as the key, not the glyph — `iconKey` is a database column
/// (`GarageScreen.kt`'s `iconFor` used the same set). The first entry is the
/// fallback for a row whose key predates this list.
const _serviceIcons = <String, IconData>{
  'wrench': Icons.build,
  'chain': Icons.link,
  'drop': Icons.opacity,
  'gauge': Icons.speed,
  'thermo': Icons.thermostat,
  'fuel': Icons.local_gas_station,
};

/// Odometer, fuel fill-ups, maintenance intervals/history. Ports
/// `GarageViewModel` + `GarageScreen.kt` (fuel + maintenance tabs; export
/// lands in Phase 6, real persistence in Phase 4).
class GarageScreen extends ConsumerStatefulWidget {
  const GarageScreen({super.key});

  @override
  ConsumerState<GarageScreen> createState() => _GarageScreenState();
}

class _GarageScreenState extends ConsumerState<GarageScreen> with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: 2, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ui = ref.watch(garageControllerProvider);
    final l10n = AppLocalizations.of(context)!;

    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.garageTitle(ui.odometerKm)),
        bottom: TabBar(controller: _tabs, tabs: [Tab(text: l10n.garageTabFuel), Tab(text: l10n.garageTabMaintenance)]),
        actions: [
          IconButton(
            icon: const Icon(Icons.speed),
            tooltip: l10n.garageSetOdometerTooltip,
            onPressed: () => _showSetOdometer(context, ref, ui.odometerKm),
          ),
        ],
      ),
      body: TabBarView(
        controller: _tabs,
        children: [
          _FuelTab(ui: ui),
          _MaintenanceTab(ui: ui),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _tabs.index == 0 ? _showAddFuel(context, ref) : _showAddService(context, ref),
        child: const Icon(Icons.add),
      ),
    );
  }

  void _showSetOdometer(BuildContext context, WidgetRef ref, int current) {
    final l10n = AppLocalizations.of(context)!;
    final controller = TextEditingController(text: current.toString());
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.garageSetOdometerTitle),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(suffixText: 'km'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.actionCancel)),
          TextButton(
            onPressed: () {
              final km = int.tryParse(controller.text);
              if (km != null) ref.read(garageControllerProvider.notifier).setOdometer(km);
              Navigator.pop(context);
            },
            child: Text(l10n.actionSave),
          ),
        ],
      ),
    );
  }

  void _showAddFuel(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final litres = TextEditingController();
    final cost = TextEditingController();
    final odo = TextEditingController();
    final location = TextEditingController();
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.garageAddFillUpTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: litres, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: l10n.garageLitresLabel)),
            TextField(controller: cost, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: l10n.garageCostLabel)),
            TextField(controller: odo, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: l10n.garageOdometerKmLabel)),
            TextField(controller: location, decoration: InputDecoration(labelText: l10n.garageLocationOptionalLabel)),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.actionCancel)),
          TextButton(
            onPressed: () {
              final l = double.tryParse(litres.text);
              final c = double.tryParse(cost.text);
              final o = int.tryParse(odo.text);
              if (l != null && c != null && o != null) {
                ref.read(garageControllerProvider.notifier).addFuel(l, c, o, location.text.trim());
              }
              Navigator.pop(context);
            },
            child: Text(l10n.actionSave),
          ),
        ],
      ),
    );
  }

  void _showAddService(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final name = TextEditingController();
    final interval = TextEditingController(text: '1000');
    // The icon ends up on the maintenance list next to the name, so the rider
    // picks it here instead of every item silently becoming a wrench.
    var iconKey = _serviceIcons.keys.first;
    showDialog<void>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(l10n.garageAddIntervalTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(controller: name, decoration: InputDecoration(labelText: l10n.garageNameLabel)),
              TextField(controller: interval, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: l10n.garageIntervalKmLabel)),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                children: [
                  for (final entry in _serviceIcons.entries)
                    ChoiceChip(
                      label: Icon(entry.value, size: 20),
                      showCheckmark: false,
                      selected: entry.key == iconKey,
                      onSelected: (_) => setDialogState(() => iconKey = entry.key),
                    ),
                ],
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.actionCancel)),
            TextButton(
              onPressed: () {
                final n = name.text.trim();
                final i = int.tryParse(interval.text);
                if (n.isNotEmpty && i != null) {
                  ref.read(garageControllerProvider.notifier).addService(n, iconKey, i);
                }
                Navigator.pop(context);
              },
              child: Text(l10n.actionSave),
            ),
          ],
        ),
      ),
    );
  }
}

class _FuelTab extends ConsumerWidget {
  const _FuelTab({required this.ui});
  final GarageUi ui;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    if (ui.fuel.isEmpty) {
      return Center(child: Text(l10n.garageNoFillUpsYet));
    }
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _Stat(l10n.garageAvg30Day, ui.avgKmpl30 != null ? '${ui.avgKmpl30!.toStringAsFixed(1)} km/l' : '—'),
                _Stat(l10n.garageFills30d, '${ui.fills30}'),
                _Stat(l10n.garageLitres30d, ui.litres30.toStringAsFixed(1)),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              for (var i = 0; i < ui.fuel.length; i++) ...[
                if (i > 0) const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.local_gas_station_outlined),
                  title: Text('${ui.fuel[i].fill.litres.toStringAsFixed(1)} L · ${ui.fuel[i].fill.cost.toStringAsFixed(0)}'),
                  subtitle: Text(
                    '${ui.fuel[i].fill.odometerKm} km'
                    '${ui.fuel[i].kmpl != null ? ' · ${ui.fuel[i].kmpl!.toStringAsFixed(1)} km/l' : ''}',
                  ),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () => ref.read(garageControllerProvider.notifier).deleteFuel(ui.fuel[i].fill),
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(children: [
      Text(value, style: theme.textTheme.titleMedium),
      Text(label, style: theme.textTheme.bodySmall),
    ]);
  }
}

class _MaintenanceTab extends ConsumerWidget {
  const _MaintenanceTab({required this.ui});
  final GarageUi ui;

  Color _toneColor(BuildContext context, String tone) {
    final scheme = Theme.of(context).colorScheme;
    return switch (tone) {
      'alert' => scheme.error,
      'warn' => Colors.amber,
      _ => Colors.green,
    };
  }

  /// Ported from `GarageScreen.kt`'s `iconFor(iconKey)`.
  IconData _iconForKey(String iconKey) => _serviceIcons[iconKey] ?? _serviceIcons.values.first;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    if (ui.maint.isEmpty) {
      return Center(child: Text(l10n.garageNoMaintenanceYet));
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: ui.maint.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (context, i) {
        final row = ui.maint[i];
        return Card(
          child: ListTile(
            leading: Icon(_iconForKey(row.item.iconKey), color: _toneColor(context, row.tone)),
            title: Text(row.item.name),
            subtitle: Text(
              row.remainingKm >= 0
                  ? l10n.dashDistanceRemaining(row.remainingKm.toString())
                  : l10n.garageKmOverdue(-row.remainingKm),
            ),
            trailing: PopupMenuButton<String>(
              onSelected: (value) {
                if (value == 'done') {
                  ref.read(garageControllerProvider.notifier).markServiceDone(row.item, ui.odometerKm);
                } else if (value == 'delete') {
                  ref.read(garageControllerProvider.notifier).deleteService(row.item);
                }
              },
              itemBuilder: (context) => [
                PopupMenuItem(value: 'done', child: Text(l10n.garageMarkDoneToday)),
                PopupMenuItem(value: 'delete', child: Text(l10n.actionDelete)),
              ],
            ),
          ),
        );
      },
    );
  }
}
