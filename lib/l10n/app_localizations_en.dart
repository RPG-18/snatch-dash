// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get navHome => 'Home';

  @override
  String get navVehicles => 'Vehicles';

  @override
  String get navExpenses => 'Expenses';

  @override
  String get navGarage => 'Garage';

  @override
  String get navMore => 'More';

  @override
  String get actionCancel => 'Cancel';

  @override
  String get actionSave => 'Save';

  @override
  String get actionDelete => 'Delete';

  @override
  String unitKm(String value) {
    return '$value km';
  }

  @override
  String unitM(String value) {
    return '$value m';
  }

  @override
  String unitMin(String value) {
    return '$value min';
  }

  @override
  String unitHourMin(String hours, String minutes) {
    return '$hours h $minutes min';
  }

  @override
  String unitDayHourMin(String days, String hours, String minutes) {
    return '$days d $hours h $minutes min';
  }

  @override
  String get homeStatusConnected => 'Connected';

  @override
  String get homeStatusConnectedSub => 'Streaming to Tripper Dash';

  @override
  String get homeStatusSearching => 'Searching…';

  @override
  String get homeStatusSearchingSub => 'Looking for Tripper Dash';

  @override
  String get homeStatusOffline => 'Offline';

  @override
  String get homeStatusOfflineSub => 'Dash not detected';

  @override
  String get homeStartNavigation => 'Start navigation';

  @override
  String get homeStartNavigationSub =>
      'Pick a destination and send it to the dash';

  @override
  String get homeDashView => 'Dash view';

  @override
  String get homeConnectToDash => 'Connect to dash';

  @override
  String get homeDashViewSub => 'Open the live projection controls';

  @override
  String get homeConnectToDashSub => 'Pair, authenticate, and start streaming';

  @override
  String get homeSavedDestinations => 'Saved destinations';

  @override
  String get homeNoSavedDestinations =>
      'No saved destinations yet. Search for a place on the Route tab and tap the bookmark icon to save it.';

  @override
  String get homeRidesLabel => 'Rides';

  @override
  String get homeNoRidesYet => 'No rides recorded yet';

  @override
  String get homeRidesAutoSub => 'Connected rides appear here automatically';

  @override
  String homeRidesSummary(num count, String km) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count rides',
      many: '$count rides',
      few: '$count rides',
      one: '$count ride',
    );
    return '$_temp0 · $km km';
  }

  @override
  String get routeTitle => 'Route';

  @override
  String get routeSearchHint => 'Where to';

  @override
  String get routeSearchPrompt => 'Type at least 3 characters';

  @override
  String get routeSearchNoResults => 'No results found';

  @override
  String get routeSearchError => 'Search failed';

  @override
  String get routeResolveError =>
      'Couldn\'t resolve that destination, try another result';

  @override
  String get routeResolvePickTitle => 'Multiple places match — pick one';

  @override
  String get routePreviewTitle => 'Routes';

  @override
  String get routePreviewLoadingRoutes => 'Building routes…';

  @override
  String get routePreviewGo => 'Let\'s go';

  @override
  String routeVoiceGuidanceTooltip(String mode) {
    return 'Voice guidance: $mode';
  }

  @override
  String get routeSaveDestinationTooltip => 'Save this destination';

  @override
  String get routeDestinationCloseTooltip => 'Close';

  @override
  String get routeDestinationGo => 'Route';

  @override
  String get routeDestinationSaved => 'Destination saved';

  @override
  String get routePreview => 'Preview';

  @override
  String get routePreviewPrompt => 'Preview a destination to see the route';

  @override
  String get routeSendToDash => 'Send to Dash';

  @override
  String get routeErrorNoGpsFix => 'No GPS fix for route planning yet';

  @override
  String get routeErrorRoutingFailedConnection =>
      'Routing failed — check your connection';

  @override
  String get voiceModeOff => 'off';

  @override
  String get voiceModeChime => 'chime';

  @override
  String get voiceModeFull => 'full';

  @override
  String get dashStageOffline => 'Offline';

  @override
  String get dashStageConnecting => 'Connecting…';

  @override
  String get dashStageAuthenticating => 'Authenticating…';

  @override
  String get dashStageReady => 'Ready';

  @override
  String get dashStageConnected => 'Connected';

  @override
  String get dashStageError => 'Error';

  @override
  String get dashHeadingUpTooltip => 'Heading up';

  @override
  String get dashGpsLost => 'GPS lost';

  @override
  String get dashGpsWeak => 'GPS weak';

  @override
  String dashDistanceRemaining(String km) {
    return '$km km remaining';
  }

  @override
  String get dashExitNavigationTooltip => 'Exit navigation';

  @override
  String get dashConnect => 'Connect';

  @override
  String get dashDisconnect => 'Disconnect';

  @override
  String get dashGpsPermissionRequired =>
      'GPS permission is required to connect';

  @override
  String garageTitle(int km) {
    return 'Garage · $km km';
  }

  @override
  String get garageTabFuel => 'Fuel';

  @override
  String get garageTabMaintenance => 'Maintenance';

  @override
  String get garageSetOdometerTooltip => 'Set odometer';

  @override
  String get garageSetOdometerTitle => 'Set odometer';

  @override
  String get garageAddFillUpTitle => 'Add fill-up';

  @override
  String get garageLitresLabel => 'Litres';

  @override
  String get garageCostLabel => 'Cost';

  @override
  String get garageOdometerKmLabel => 'Odometer (km)';

  @override
  String get garageLocationOptionalLabel => 'Location (optional)';

  @override
  String get garageAddIntervalTitle => 'Add interval';

  @override
  String get garageNameLabel => 'Name';

  @override
  String get garageIntervalKmLabel => 'Interval (km)';

  @override
  String get garageNoFillUpsYet => 'No fill-ups logged yet';

  @override
  String get garageAvg30Day => '30-day avg';

  @override
  String get garageFills30d => 'Fills (30d)';

  @override
  String get garageLitres30d => 'Litres (30d)';

  @override
  String get garageNoMaintenanceYet => 'No maintenance intervals yet';

  @override
  String garageKmOverdue(int km) {
    return '$km km overdue';
  }

  @override
  String get garageMarkDoneToday => 'Mark done today';

  @override
  String get expensesTitle => 'Expenses';

  @override
  String get categoryAllExpenses => 'All Expenses';

  @override
  String get categoryFuel => 'Fuel';

  @override
  String get categoryRepairs => 'Repairs';

  @override
  String get categoryAccessories => 'Accessories';

  @override
  String get categoryRidingGear => 'Riding Gear';

  @override
  String get categoryFood => 'Food';

  @override
  String get categoryStay => 'Stay';

  @override
  String get categoryTransport => 'Transport';

  @override
  String get categoryOthers => 'Others';

  @override
  String get expensesTotalLabel => 'Total';

  @override
  String get expensesExportTooltip => 'Export';

  @override
  String get expensesNoneForFilter =>
      'No expenses for this category and period.';

  @override
  String get expensesExportCsv => 'Excel / CSV';

  @override
  String get expensesExportDocument => 'Document';

  @override
  String get expensesAddTitle => 'Add expense';

  @override
  String get expensesCategoryLabel => 'Category';

  @override
  String expensesAmountLabel(String symbol) {
    return 'Amount ($symbol)';
  }

  @override
  String get expensesNoteOptionalLabel => 'Note (optional)';

  @override
  String get expensesAllTime => 'All time';

  @override
  String get ridesTitle => 'Rides';

  @override
  String rideDurationSummary(String km, int min) {
    return '$km km · ${min}m';
  }

  @override
  String rideDateAvgSpeed(String date, String speed) {
    return '$date · avg $speed km/h';
  }

  @override
  String get settingsTitle => 'More';

  @override
  String get settingsDashConnection => 'Dash connection';

  @override
  String settingsNotPaired(String prefix) {
    return 'Not paired — discovers by prefix \"$prefix\"';
  }

  @override
  String settingsStageWifi(String stage, String wifi) {
    return 'Stage: $stage · WiFi: $wifi';
  }

  @override
  String get settingsSetExactSsid => 'Set exact dash SSID';

  @override
  String get settingsSetWifiPassword => 'Set WiFi password';

  @override
  String get settingsDefaultPasswordSub => 'Default: 12345678 (factory)';

  @override
  String get settingsForgetDash => 'Forget dash';

  @override
  String get settingsForgetDashSub => 'Re-run prefix discovery on next connect';

  @override
  String get settingsCurrency => 'Currency';

  @override
  String get settingsLogsTitle => 'Debug logs';

  @override
  String get settingsLogsSubtitle => 'View app logs';

  @override
  String get settingsLogsShareFile => 'Share saved log file';

  @override
  String get settingsMediaAccessTitle => 'Media & call notifications';

  @override
  String get settingsMediaAccessGranted => 'Access granted';

  @override
  String get settingsMediaAccessNotGranted =>
      'Access not granted — tap to open settings';

  @override
  String get settingsMapCacheTitle => 'Map cache';

  @override
  String settingsMapCacheSize(String size) {
    return '$size on disk';
  }

  @override
  String get settingsMapCacheUnknown => 'Unable to determine size';

  @override
  String get settingsMapCacheClearButton => 'Clear';

  @override
  String get settingsMapCacheUnitBytes => 'B';

  @override
  String get settingsMapCacheUnitKb => 'KB';

  @override
  String get settingsMapCacheUnitMb => 'MB';

  @override
  String get settingsMapCacheUnitGb => 'GB';

  @override
  String get settingsAboutTitle => 'SnatchDash';

  @override
  String get settingsAboutSubtitle =>
      'Flutter port · maps/nav via Yandex MapKit';

  @override
  String get settingsAboutYandexTermsLink => 'Yandex Maps terms of use';

  @override
  String get settingsUpdatesTitle => 'Updates';

  @override
  String settingsUpdatesCurrentVersion(String version) {
    return 'Current version: $version';
  }

  @override
  String get settingsUpdatesAutoUpdate => 'Automatic updates';

  @override
  String get settingsUpdatesChannelNightly => 'Nightly builds';

  @override
  String get settingsUpdatesChannelNightlySub =>
      'Daily builds from main — less stable, may break';

  @override
  String get settingsUpdatesCheckButton => 'Check for updates';

  @override
  String get settingsUpdatesStatusChecking => 'Checking…';

  @override
  String get settingsUpdatesStatusUpToDate => 'You\'re on the latest version';

  @override
  String settingsUpdatesStatusAvailable(String version) {
    return 'Version $version available';
  }

  @override
  String settingsUpdatesStatusError(String message) {
    return 'Check failed: $message';
  }

  @override
  String get settingsUpdatesDownloadButton => 'Download & install';

  @override
  String settingsUpdatesDownloading(String percent) {
    return 'Downloading… $percent%';
  }

  @override
  String get settingsUpdatesDownloadingIndeterminate => 'Downloading…';

  @override
  String get settingsUpdatesNeedsPermission =>
      'Allow installing apps from this source to continue';

  @override
  String get settingsUpdatesGrantPermissionButton => 'Grant permission';

  @override
  String updateAvailableTitle(String version) {
    return 'Update available: $version';
  }

  @override
  String get updateAvailableBody => 'A new version is ready to download.';

  @override
  String get updateLater => 'Later';

  @override
  String get updateDownload => 'Download';

  @override
  String get settingsSsidDialogTitle => 'Dash SSID';

  @override
  String get settingsExactSsidLabel => 'Exact SSID';

  @override
  String get settingsWifiPasswordDialogTitle => 'WiFi password';

  @override
  String get settingsPasswordLabel => 'Password';

  @override
  String get currencyNameInr => 'Indian rupee';

  @override
  String get currencyNameUsd => 'US dollar';

  @override
  String get currencyNameEur => 'Euro';

  @override
  String get currencyNameGbp => 'British pound';

  @override
  String get currencyNameAud => 'Australian dollar';

  @override
  String get currencyNameCad => 'Canadian dollar';

  @override
  String get currencyNameSgd => 'Singapore dollar';

  @override
  String get currencyNameAed => 'UAE dirham';

  @override
  String get currencyNameRub => 'Russian ruble';

  @override
  String get vehiclesTitle => 'Vehicles';

  @override
  String get vehiclesAdd => 'Add vehicle';

  @override
  String get vehiclesPucLabel => 'PUC';

  @override
  String get vehiclesInsuranceLabel => 'Insurance';

  @override
  String get vehiclesServiceLabel => 'Service';

  @override
  String get vehiclesCurrentChip => 'Current vehicle';

  @override
  String get vehiclesSetCurrent => 'Set current';

  @override
  String get vehiclesEditTitle => 'Edit vehicle';

  @override
  String get vehiclesAddDialogTitle => 'Add vehicle';

  @override
  String get vehiclesNameLabel => 'Vehicle name';

  @override
  String get vehiclesNicknameLabel => 'Nickname';

  @override
  String get vehiclesPucExpiryLabel => 'PUC expiry (DD-MMM-YYYY)';

  @override
  String get vehiclesInsuranceExpiryLabel => 'Insurance expiry (DD-MMM-YYYY)';

  @override
  String get vehiclesNotSet => 'Not set';

  @override
  String get maintChannelName => 'Maintenance reminders';

  @override
  String get maintChannelDesc => 'Alerts when a service interval is due';

  @override
  String get maintNotifTitleSingle => 'Maintenance due';

  @override
  String maintNotifTitleMultiple(num count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count services due',
      many: '$count services due',
      few: '$count services due',
      one: '$count service due',
    );
    return '$_temp0';
  }

  @override
  String maintLineOverdueByDate(String name) {
    return '$name — overdue by date';
  }

  @override
  String maintLineOverdueKm(String name, int km) {
    return '$name — overdue $km km';
  }

  @override
  String maintLineDueKmDays(String name, int km, int days) {
    return '$name — due in $km km or $days days';
  }

  @override
  String maintLineDueKm(String name, int km) {
    return '$name — due in $km km';
  }

  @override
  String get voiceArrived => 'You have arrived at your destination';

  @override
  String voiceNow(String phrase) {
    return 'Now, $phrase';
  }

  @override
  String voiceIn(String distance, String phrase) {
    return 'In $distance, $phrase';
  }

  @override
  String get voiceTurnArrive => 'you have arrived';

  @override
  String get voiceTurnContinue => 'continue straight';

  @override
  String get voiceTurnLeft => 'turn left';

  @override
  String get voiceTurnRight => 'turn right';

  @override
  String get voiceTurnUturnLeft => 'make a U-turn to the left';

  @override
  String get voiceTurnUturnRight => 'make a U-turn to the right';

  @override
  String voiceDistanceKm(String km) {
    return '$km kilometers';
  }

  @override
  String get voiceDistance500m => '500 meters';

  @override
  String get voiceDistance200m => '200 meters';

  @override
  String voiceDistanceM(int m) {
    return '$m meters';
  }

  @override
  String get voiceDashDisconnected =>
      'Dash disconnected. Restart the dash to reconnect.';

  @override
  String get voiceDashReconnected => 'Dash reconnected';
}
