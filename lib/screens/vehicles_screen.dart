import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../models/vehicle_profile.dart';
import '../state/vehicle_store.dart';

/// Vehicle profiles (add/edit/select active vehicle). Ports `VehiclesScreen.kt`.
class VehiclesScreen extends ConsumerWidget {
  const VehiclesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(vehicleStoreProvider);
    final notifier = ref.read(vehicleStoreProvider.notifier);
    final l10n = AppLocalizations.of(context)!;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.vehiclesTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Column(
              children: [
                for (var i = 0; i < state.vehicles.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  _VehicleTile(
                    vehicle: state.vehicles[i],
                    active: state.vehicles[i].id == state.activeVehicleId,
                    onSelect: () => notifier.select(state.vehicles[i].id),
                    onEdit: () => _showEditDialog(context, ref, state.vehicles[i]),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            icon: const Icon(Icons.add),
            label: Text(l10n.vehiclesAdd),
            onPressed: () => _showEditDialog(
              context,
              ref,
              const VehicleProfile(
                id: '',
                title: '',
                nickname: '',
                puc: 'Not set',
                insurance: 'Not set',
                service: 'Not set',
              ),
              isNew: true,
            ),
          ),
        ],
      ),
    );
  }

  void _showEditDialog(BuildContext context, WidgetRef ref, VehicleProfile vehicle, {bool isNew = false}) {
    showDialog<void>(
      context: context,
      builder: (context) => _EditVehicleDialog(vehicle: vehicle, isNew: isNew),
    );
  }
}

class _VehicleTile extends StatelessWidget {
  const _VehicleTile({
    required this.vehicle,
    required this.active,
    required this.onSelect,
    required this.onEdit,
  });

  final VehicleProfile vehicle;
  final bool active;
  final VoidCallback onSelect;
  final VoidCallback onEdit;

  bool _isProblem(String v) => v.toLowerCase() == 'expired' || v.toLowerCase() == 'na';

  /// [vehicle]'s puc/insurance/service fields store the literal English
  /// sentinel "Not set" when left blank (see [_EditVehicleDialogState]) — only
  /// the displayed text is localized, not the stored value.
  String _displayValue(AppLocalizations l10n, String value) => value == 'Not set' ? l10n.vehiclesNotSet : value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context)!;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(padding: EdgeInsets.only(top: 4), child: Icon(Icons.two_wheeler)),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(vehicle.title, style: theme.textTheme.titleMedium?.copyWith(color: theme.colorScheme.primary)),
                if (vehicle.nickname.isNotEmpty)
                  Text(vehicle.nickname, style: theme.textTheme.bodySmall),
                const SizedBox(height: 10),
                _MetaRow(l10n.vehiclesPucLabel, _displayValue(l10n, vehicle.puc), alert: _isProblem(vehicle.puc)),
                _MetaRow(l10n.vehiclesInsuranceLabel, _displayValue(l10n, vehicle.insurance), alert: _isProblem(vehicle.insurance)),
                _MetaRow(l10n.vehiclesServiceLabel, _displayValue(l10n, vehicle.service)),
                const SizedBox(height: 10),
                active
                    ? Chip(label: Text(l10n.vehiclesCurrentChip), avatar: const Icon(Icons.check, size: 16))
                    : OutlinedButton(onPressed: onSelect, child: Text(l10n.vehiclesSetCurrent)),
              ],
            ),
          ),
          IconButton(icon: const Icon(Icons.edit_outlined), onPressed: onEdit),
        ],
      ),
    );
  }
}

class _MetaRow extends StatelessWidget {
  const _MetaRow(this.label, this.value, {this.alert = false});
  final String label;
  final String value;
  final bool alert;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          SizedBox(width: 90, child: Text(label, style: theme.textTheme.bodySmall)),
          const Text(': '),
          Text(
            value,
            style: theme.textTheme.bodySmall?.copyWith(
              color: alert ? theme.colorScheme.error : null,
            ),
          ),
        ],
      ),
    );
  }
}

class _EditVehicleDialog extends ConsumerStatefulWidget {
  const _EditVehicleDialog({required this.vehicle, required this.isNew});
  final VehicleProfile vehicle;
  final bool isNew;

  @override
  ConsumerState<_EditVehicleDialog> createState() => _EditVehicleDialogState();
}

class _EditVehicleDialogState extends ConsumerState<_EditVehicleDialog> {
  late final _title = TextEditingController(text: widget.vehicle.title);
  late final _nickname = TextEditingController(text: widget.vehicle.nickname);
  late final _puc = TextEditingController(text: widget.vehicle.puc);
  late final _insurance = TextEditingController(text: widget.vehicle.insurance);
  late final _service = TextEditingController(text: widget.vehicle.service);

  @override
  void dispose() {
    _title.dispose();
    _nickname.dispose();
    _puc.dispose();
    _insurance.dispose();
    _service.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      title: Text(widget.isNew ? l10n.vehiclesAddDialogTitle : l10n.vehiclesEditTitle),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: _title, decoration: InputDecoration(labelText: l10n.vehiclesNameLabel)),
            TextField(controller: _nickname, decoration: InputDecoration(labelText: l10n.vehiclesNicknameLabel)),
            TextField(controller: _puc, decoration: InputDecoration(labelText: l10n.vehiclesPucExpiryLabel)),
            TextField(
              controller: _insurance,
              decoration: InputDecoration(labelText: l10n.vehiclesInsuranceExpiryLabel),
            ),
            TextField(controller: _service, decoration: InputDecoration(labelText: l10n.vehiclesServiceLabel)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.actionCancel)),
        TextButton(
          onPressed: _title.text.trim().isEmpty
              ? null
              : () {
                  final updated = VehicleProfile(
                    id: widget.vehicle.id,
                    title: _title.text.trim(),
                    nickname: _nickname.text.trim(),
                    puc: _puc.text.trim().isEmpty ? 'Not set' : _puc.text.trim(),
                    insurance: _insurance.text.trim().isEmpty ? 'Not set' : _insurance.text.trim(),
                    service: _service.text.trim().isEmpty ? 'Not set' : _service.text.trim(),
                  );
                  final notifier = ref.read(vehicleStoreProvider.notifier);
                  widget.isNew ? notifier.add(updated) : notifier.update(updated);
                  Navigator.pop(context);
                },
          child: Text(l10n.actionSave),
        ),
      ],
    );
  }
}
