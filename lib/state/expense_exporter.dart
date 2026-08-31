import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/garage_models.dart';
import 'currency_settings.dart';

/// CSV/HTML(.doc) export for the Expenses screen's "Share" sheet. Ported
/// from `GarageViewModel.kt`'s `exportExpensesCsv`/`exportExpensesDoc`.
class ExpenseExporter {
  ExpenseExporter._();

  static Future<File> exportCsv(
    List<Expense> expenses,
    String periodFileLabel,
    OpenDashCurrency currency,
  ) async {
    final file = await _exportFile('opendash-expenses-$periodFileLabel.csv');
    final buffer = StringBuffer()..writeln('Date,Category,Amount_${currency.code},Note');
    for (final e in expenses) {
      final row = [
        _exportDate(e.dateMs),
        e.category,
        e.amount.toStringAsFixed(2),
        e.note,
      ].map(_csvCell).join(',');
      buffer.writeln(row);
    }
    await file.writeAsString(buffer.toString());
    return file;
  }

  static Future<File> exportDoc(
    List<Expense> expenses,
    String periodLabel,
    OpenDashCurrency currency,
  ) async {
    final fileLabel = periodLabel.toLowerCase().replaceAll(' ', '-');
    final file = await _exportFile('opendash-expenses-$fileLabel.doc');
    final total = expenses.fold(0.0, (sum, e) => sum + e.amount);
    final buffer = StringBuffer()
      ..writeln('<html><head><meta charset="utf-8"><title>OpenDash Expenses</title></head><body>')
      ..writeln('<h1>OpenDash Expenses - ${_html(periodLabel)}</h1>')
      ..writeln('<p>Total: ${_html(formatCurrencyAmount(total, currency, decimals: 2))}</p>')
      ..writeln('<table border="1" cellspacing="0" cellpadding="6">')
      ..writeln('<tr><th>Date</th><th>Category</th><th>Amount</th><th>Note</th></tr>');
    for (final e in expenses) {
      buffer.writeln(
        '<tr><td>${_exportDate(e.dateMs)}</td><td>${_html(e.category)}</td>'
        '<td>${_html(formatCurrencyAmount(e.amount, currency, decimals: 2))}</td>'
        '<td>${_html(e.note)}</td></tr>',
      );
    }
    buffer.writeln('</table></body></html>');
    await file.writeAsString(buffer.toString());
    return file;
  }

  static Future<File> _exportFile(String name) async {
    final dir = Directory(p.join((await getTemporaryDirectory()).path, 'exports'));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return File(p.join(dir.path, name));
  }

  static String _exportDate(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  /// Quotes a CSV field, and neutralises the leading characters that make
  /// Excel/Sheets treat a cell as a formula rather than text — a free-text
  /// note or category starting with `=`, `+`, `-` or `@` would otherwise be
  /// evaluated on open. Prefixing an apostrophe is the standard defence; it
  /// isn't shown by the spreadsheet, and quoting alone does not prevent this.
  static String _csvCell(String value) {
    final escaped = value.replaceAll('"', '""');
    final needsGuard = escaped.isNotEmpty && '=+-@\t\r'.contains(escaped[0]);
    return '"${needsGuard ? "'" : ''}$escaped"';
  }

  static String _html(String value) =>
      value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}
