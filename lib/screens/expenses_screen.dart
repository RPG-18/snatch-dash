import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:share_plus/share_plus.dart';

import '../l10n/app_localizations.dart';
import '../models/garage_models.dart';
import '../state/currency_settings.dart';
import '../state/expense_exporter.dart';
import '../state/garage_controller.dart';

const _categories = [
  'All Expenses',
  'Fuel',
  'Repairs',
  'Accessories',
  'Riding Gear',
  'Food',
  'Stay',
  'Transport',
  'Others',
];

/// Categories are stored/filtered by their canonical English key (see
/// [GarageController.addExpense]) — only the label shown to the user is
/// localized.
String _categoryLabel(AppLocalizations l10n, String category) => switch (category) {
      'All Expenses' => l10n.categoryAllExpenses,
      'Fuel' => l10n.categoryFuel,
      'Repairs' => l10n.categoryRepairs,
      'Accessories' => l10n.categoryAccessories,
      'Riding Gear' => l10n.categoryRidingGear,
      'Food' => l10n.categoryFood,
      'Stay' => l10n.categoryStay,
      'Transport' => l10n.categoryTransport,
      'Others' => l10n.categoryOthers,
      _ => category,
    };

class _ExpensePeriod {
  const _ExpensePeriod(this.label, this.fileLabel, {this.start, this.end});
  final String label;
  final String fileLabel;
  final DateTime? start;
  final DateTime? end;

  bool includes(int dateMs) {
    final d = DateTime.fromMillisecondsSinceEpoch(dateMs);
    if (start != null && d.isBefore(start!)) return false;
    if (end != null && !d.isBefore(end!)) return false;
    return true;
  }
}

List<_ExpensePeriod> _expensePeriods(AppLocalizations l10n, Locale locale) {
  final now = DateTime.now();
  final monthFormat = DateFormat.yMMMM(locale.toString());
  return [
    _ExpensePeriod(l10n.expensesAllTime, 'all-time'),
    for (var m = 1; m <= 12; m++)
      _ExpensePeriod(
        monthFormat.format(DateTime(now.year, m)),
        DateFormat('yyyy-MM').format(DateTime(now.year, m)),
        start: DateTime(now.year, m, 1),
        end: DateTime(now.year, m + 1, 1),
      ),
  ];
}

/// Expense list/add/filter/export. Ports `GarageViewModel` (expenses half) +
/// `ExpensesScreen.kt` — category + month/all-time filters, CSV/HTML export
/// via `share_plus`.
class ExpensesScreen extends ConsumerStatefulWidget {
  const ExpensesScreen({super.key});

  @override
  ConsumerState<ExpensesScreen> createState() => _ExpensesScreenState();
}

class _ExpensesScreenState extends ConsumerState<ExpensesScreen> {
  String _category = 'All Expenses';
  List<_ExpensePeriod>? _periods;
  _ExpensePeriod? _period;

  @override
  Widget build(BuildContext context) {
    final ui = ref.watch(garageControllerProvider);
    final currency = ref.watch(currencySettingsProvider);
    final l10n = AppLocalizations.of(context)!;
    final locale = Localizations.localeOf(context);

    final periods = _periods ??= _expensePeriods(l10n, locale);
    final period = _period ??= periods.first;

    final periodExpenses = ui.expenses.where((e) => period.includes(e.dateMs)).toList();
    final shown = _category == 'All Expenses'
        ? periodExpenses
        : periodExpenses.where((e) => e.category == _category).toList();
    final total = shown.fold(0.0, (sum, e) => sum + e.amount);
    final dateFormat = DateFormat.yMd(locale.toString());

    return Scaffold(
      appBar: AppBar(title: Text(l10n.expensesTitle)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.expensesTotalLabel, style: Theme.of(context).textTheme.bodySmall),
                    Text(formatCurrencyAmount(total, currency), style: Theme.of(context).textTheme.headlineSmall),
                  ],
                ),
                const Spacer(),
                DropdownButton<_ExpensePeriod>(
                  value: period,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final p in periods) DropdownMenuItem(value: p, child: Text(p.label)),
                  ],
                  onChanged: (v) => setState(() => _period = v ?? period),
                ),
                const SizedBox(width: 8),
                IconButton(
                  icon: const Icon(Icons.ios_share),
                  tooltip: l10n.expensesExportTooltip,
                  onPressed: shown.isEmpty ? null : () => _showExportSheet(context, l10n, shown, period, currency),
                ),
              ],
            ),
          ),
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              children: [
                for (final c in _categories)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: ChoiceChip(
                      label: Text(_categoryLabel(l10n, c)),
                      selected: _category == c,
                      onSelected: (_) => setState(() => _category = c),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: shown.isEmpty
                ? Center(child: Text(l10n.expensesNoneForFilter))
                : ListView.separated(
                    padding: const EdgeInsets.all(16),
                    itemCount: shown.length,
                    separatorBuilder: (context, index) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final e = shown[i];
                      final date = DateTime.fromMillisecondsSinceEpoch(e.dateMs);
                      return ListTile(
                        title: Text(_categoryLabel(l10n, e.category)),
                        subtitle: Text(
                          e.note.isNotEmpty ? e.note : dateFormat.format(date),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(formatCurrencyAmount(e.amount, currency, decimals: 2)),
                            IconButton(
                              icon: const Icon(Icons.delete_outline, size: 18),
                              onPressed: () => ref.read(garageControllerProvider.notifier).deleteExpense(e),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _showAddExpense(context, l10n, ref, ui.activeVehicleName, currency),
        child: const Icon(Icons.add),
      ),
    );
  }

  void _showExportSheet(
    BuildContext context,
    AppLocalizations l10n,
    List<Expense> shown,
    _ExpensePeriod period,
    OpenDashCurrency currency,
  ) {
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Wrap(children: [
          ListTile(
            leading: const Icon(Icons.table_chart_outlined),
            title: Text(l10n.expensesExportCsv),
            onTap: () async {
              Navigator.pop(sheetContext);
              final file = await ExpenseExporter.exportCsv(shown, period.fileLabel, currency);
              await SharePlus.instance.share(ShareParams(files: [XFile(file.path)], subject: 'SnatchDash expenses'));
            },
          ),
          ListTile(
            leading: const Icon(Icons.description_outlined),
            title: Text(l10n.expensesExportDocument),
            onTap: () async {
              Navigator.pop(sheetContext);
              final file = await ExpenseExporter.exportDoc(shown, period.label, currency);
              await SharePlus.instance.share(ShareParams(files: [XFile(file.path)], subject: 'SnatchDash expenses'));
            },
          ),
        ]),
      ),
    );
  }

  void _showAddExpense(
    BuildContext context,
    AppLocalizations l10n,
    WidgetRef ref,
    String vehicleName,
    OpenDashCurrency currency,
  ) {
    var category = _categories.length > 1 ? _categories[1] : 'Fuel';
    final amount = TextEditingController();
    final note = TextEditingController();
    showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setState) => AlertDialog(
          title: Text(l10n.expensesAddTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: category,
                items: [
                  for (final c in _categories.skip(1)) DropdownMenuItem(value: c, child: Text(_categoryLabel(l10n, c))),
                ],
                onChanged: (v) => setState(() => category = v ?? category),
                decoration: InputDecoration(labelText: l10n.expensesCategoryLabel),
              ),
              TextField(
                controller: amount,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(labelText: l10n.expensesAmountLabel(currency.symbol)),
              ),
              TextField(controller: note, decoration: InputDecoration(labelText: l10n.expensesNoteOptionalLabel)),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext), child: Text(l10n.actionCancel)),
            TextButton(
              onPressed: () {
                final a = double.tryParse(amount.text);
                if (a != null && a > 0) {
                  ref.read(garageControllerProvider.notifier).addExpense(category, a, note.text.trim());
                }
                Navigator.pop(dialogContext);
              },
              child: Text(l10n.actionSave),
            ),
          ],
        ),
      ),
    );
  }
}
