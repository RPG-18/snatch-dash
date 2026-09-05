import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_ru.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('ru'),
  ];

  /// No description provided for @navHome.
  ///
  /// In en, this message translates to:
  /// **'Home'**
  String get navHome;

  /// No description provided for @navVehicles.
  ///
  /// In en, this message translates to:
  /// **'Vehicles'**
  String get navVehicles;

  /// No description provided for @navExpenses.
  ///
  /// In en, this message translates to:
  /// **'Expenses'**
  String get navExpenses;

  /// No description provided for @navGarage.
  ///
  /// In en, this message translates to:
  /// **'Garage'**
  String get navGarage;

  /// No description provided for @navMore.
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get navMore;

  /// No description provided for @actionCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get actionCancel;

  /// No description provided for @actionSave.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get actionSave;

  /// No description provided for @actionDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get actionDelete;

  /// No description provided for @actionYes.
  ///
  /// In en, this message translates to:
  /// **'Yes'**
  String get actionYes;

  /// No description provided for @actionNo.
  ///
  /// In en, this message translates to:
  /// **'No'**
  String get actionNo;

  /// No description provided for @unitKm.
  ///
  /// In en, this message translates to:
  /// **'{value} km'**
  String unitKm(String value);

  /// No description provided for @unitM.
  ///
  /// In en, this message translates to:
  /// **'{value} m'**
  String unitM(String value);

  /// No description provided for @unitMin.
  ///
  /// In en, this message translates to:
  /// **'{value} min'**
  String unitMin(String value);

  /// No description provided for @unitHourMin.
  ///
  /// In en, this message translates to:
  /// **'{hours} h {minutes} min'**
  String unitHourMin(String hours, String minutes);

  /// No description provided for @unitDayHourMin.
  ///
  /// In en, this message translates to:
  /// **'{days} d {hours} h {minutes} min'**
  String unitDayHourMin(String days, String hours, String minutes);

  /// No description provided for @homeStatusConnected.
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get homeStatusConnected;

  /// No description provided for @homeStatusConnectedSub.
  ///
  /// In en, this message translates to:
  /// **'Streaming to Tripper Dash'**
  String get homeStatusConnectedSub;

  /// No description provided for @homeStatusSearching.
  ///
  /// In en, this message translates to:
  /// **'Searching…'**
  String get homeStatusSearching;

  /// No description provided for @homeStatusSearchingSub.
  ///
  /// In en, this message translates to:
  /// **'Looking for Tripper Dash'**
  String get homeStatusSearchingSub;

  /// No description provided for @homeStatusOffline.
  ///
  /// In en, this message translates to:
  /// **'Offline'**
  String get homeStatusOffline;

  /// No description provided for @homeStatusOfflineSub.
  ///
  /// In en, this message translates to:
  /// **'Dash not detected'**
  String get homeStatusOfflineSub;

  /// No description provided for @homeStartNavigation.
  ///
  /// In en, this message translates to:
  /// **'Start navigation'**
  String get homeStartNavigation;

  /// No description provided for @homeStartNavigationSub.
  ///
  /// In en, this message translates to:
  /// **'Pick a destination and send it to the dash'**
  String get homeStartNavigationSub;

  /// No description provided for @homeDashView.
  ///
  /// In en, this message translates to:
  /// **'Dash view'**
  String get homeDashView;

  /// No description provided for @homeConnectToDash.
  ///
  /// In en, this message translates to:
  /// **'Connect to dash'**
  String get homeConnectToDash;

  /// No description provided for @homeDashViewSub.
  ///
  /// In en, this message translates to:
  /// **'Open the live projection controls'**
  String get homeDashViewSub;

  /// No description provided for @homeConnectToDashSub.
  ///
  /// In en, this message translates to:
  /// **'Pair, authenticate, and start streaming'**
  String get homeConnectToDashSub;

  /// No description provided for @homeSavedDestinations.
  ///
  /// In en, this message translates to:
  /// **'Saved destinations'**
  String get homeSavedDestinations;

  /// No description provided for @homeNoSavedDestinations.
  ///
  /// In en, this message translates to:
  /// **'No saved destinations yet. Search for a place on the Route tab and tap the bookmark icon to save it.'**
  String get homeNoSavedDestinations;

  /// No description provided for @homeRidesLabel.
  ///
  /// In en, this message translates to:
  /// **'Rides'**
  String get homeRidesLabel;

  /// No description provided for @homeNoRidesYet.
  ///
  /// In en, this message translates to:
  /// **'No rides recorded yet'**
  String get homeNoRidesYet;

  /// No description provided for @homeRidesAutoSub.
  ///
  /// In en, this message translates to:
  /// **'Connected rides appear here automatically'**
  String get homeRidesAutoSub;

  /// No description provided for @homeRidesSummary.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, one{{count} ride} few{{count} rides} many{{count} rides} other{{count} rides}} · {km} km'**
  String homeRidesSummary(num count, String km);

  /// No description provided for @routeTitle.
  ///
  /// In en, this message translates to:
  /// **'Route'**
  String get routeTitle;

  /// No description provided for @routeSearchHint.
  ///
  /// In en, this message translates to:
  /// **'Where to'**
  String get routeSearchHint;

  /// No description provided for @routeSearchPrompt.
  ///
  /// In en, this message translates to:
  /// **'Type at least 3 characters'**
  String get routeSearchPrompt;

  /// No description provided for @routeSearchNoResults.
  ///
  /// In en, this message translates to:
  /// **'No results found'**
  String get routeSearchNoResults;

  /// No description provided for @routeSearchError.
  ///
  /// In en, this message translates to:
  /// **'Search failed'**
  String get routeSearchError;

  /// No description provided for @routeResolveError.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t resolve that destination, try another result'**
  String get routeResolveError;

  /// No description provided for @routeResolvePickTitle.
  ///
  /// In en, this message translates to:
  /// **'Multiple places match — pick one'**
  String get routeResolvePickTitle;

  /// No description provided for @routePreviewTitle.
  ///
  /// In en, this message translates to:
  /// **'Routes'**
  String get routePreviewTitle;

  /// No description provided for @routePreviewLoadingRoutes.
  ///
  /// In en, this message translates to:
  /// **'Building routes…'**
  String get routePreviewLoadingRoutes;

  /// No description provided for @routePreviewGo.
  ///
  /// In en, this message translates to:
  /// **'Let\'s go'**
  String get routePreviewGo;

  /// No description provided for @routeVoiceGuidanceTooltip.
  ///
  /// In en, this message translates to:
  /// **'Voice guidance: {mode}'**
  String routeVoiceGuidanceTooltip(String mode);

  /// No description provided for @routeSaveDestinationTooltip.
  ///
  /// In en, this message translates to:
  /// **'Save this destination'**
  String get routeSaveDestinationTooltip;

  /// No description provided for @routeDestinationCloseTooltip.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get routeDestinationCloseTooltip;

  /// No description provided for @routeDestinationGo.
  ///
  /// In en, this message translates to:
  /// **'Route'**
  String get routeDestinationGo;

  /// No description provided for @routeDestinationSaved.
  ///
  /// In en, this message translates to:
  /// **'Destination saved'**
  String get routeDestinationSaved;

  /// No description provided for @routePreview.
  ///
  /// In en, this message translates to:
  /// **'Preview'**
  String get routePreview;

  /// No description provided for @routePreviewPrompt.
  ///
  /// In en, this message translates to:
  /// **'Preview a destination to see the route'**
  String get routePreviewPrompt;

  /// No description provided for @routeSendToDash.
  ///
  /// In en, this message translates to:
  /// **'Send to Dash'**
  String get routeSendToDash;

  /// No description provided for @routeErrorNoGpsFix.
  ///
  /// In en, this message translates to:
  /// **'No GPS fix for route planning yet'**
  String get routeErrorNoGpsFix;

  /// No description provided for @routeErrorRoutingFailedConnection.
  ///
  /// In en, this message translates to:
  /// **'Routing failed — check your connection'**
  String get routeErrorRoutingFailedConnection;

  /// No description provided for @voiceModeOff.
  ///
  /// In en, this message translates to:
  /// **'off'**
  String get voiceModeOff;

  /// No description provided for @voiceModeChime.
  ///
  /// In en, this message translates to:
  /// **'chime'**
  String get voiceModeChime;

  /// No description provided for @voiceModeFull.
  ///
  /// In en, this message translates to:
  /// **'full'**
  String get voiceModeFull;

  /// No description provided for @dashStageOffline.
  ///
  /// In en, this message translates to:
  /// **'Offline'**
  String get dashStageOffline;

  /// No description provided for @dashStageConnecting.
  ///
  /// In en, this message translates to:
  /// **'Connecting…'**
  String get dashStageConnecting;

  /// No description provided for @dashStageAuthenticating.
  ///
  /// In en, this message translates to:
  /// **'Authenticating…'**
  String get dashStageAuthenticating;

  /// No description provided for @dashStageReady.
  ///
  /// In en, this message translates to:
  /// **'Ready'**
  String get dashStageReady;

  /// No description provided for @dashStageConnected.
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get dashStageConnected;

  /// No description provided for @dashStageError.
  ///
  /// In en, this message translates to:
  /// **'Error'**
  String get dashStageError;

  /// No description provided for @dashHeadingUpTooltip.
  ///
  /// In en, this message translates to:
  /// **'Heading up'**
  String get dashHeadingUpTooltip;

  /// No description provided for @dashGpsLost.
  ///
  /// In en, this message translates to:
  /// **'GPS lost'**
  String get dashGpsLost;

  /// No description provided for @dashGpsWeak.
  ///
  /// In en, this message translates to:
  /// **'GPS weak'**
  String get dashGpsWeak;

  /// No description provided for @dashDistanceRemaining.
  ///
  /// In en, this message translates to:
  /// **'{km} km remaining'**
  String dashDistanceRemaining(String km);

  /// No description provided for @dashExitNavigationTooltip.
  ///
  /// In en, this message translates to:
  /// **'Exit navigation'**
  String get dashExitNavigationTooltip;

  /// No description provided for @dashConnect.
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get dashConnect;

  /// No description provided for @dashDisconnect.
  ///
  /// In en, this message translates to:
  /// **'Disconnect'**
  String get dashDisconnect;

  /// No description provided for @dashGpsPermissionRequired.
  ///
  /// In en, this message translates to:
  /// **'GPS permission is required to connect'**
  String get dashGpsPermissionRequired;

  /// No description provided for @garageTitle.
  ///
  /// In en, this message translates to:
  /// **'Garage · {km} km'**
  String garageTitle(int km);

  /// No description provided for @garageTabFuel.
  ///
  /// In en, this message translates to:
  /// **'Fuel'**
  String get garageTabFuel;

  /// No description provided for @garageTabMaintenance.
  ///
  /// In en, this message translates to:
  /// **'Maintenance'**
  String get garageTabMaintenance;

  /// No description provided for @garageSetOdometerTooltip.
  ///
  /// In en, this message translates to:
  /// **'Set odometer'**
  String get garageSetOdometerTooltip;

  /// No description provided for @garageSetOdometerTitle.
  ///
  /// In en, this message translates to:
  /// **'Set odometer'**
  String get garageSetOdometerTitle;

  /// No description provided for @garageAddFillUpTitle.
  ///
  /// In en, this message translates to:
  /// **'Add fill-up'**
  String get garageAddFillUpTitle;

  /// No description provided for @garageLitresLabel.
  ///
  /// In en, this message translates to:
  /// **'Litres'**
  String get garageLitresLabel;

  /// No description provided for @garageCostLabel.
  ///
  /// In en, this message translates to:
  /// **'Cost'**
  String get garageCostLabel;

  /// No description provided for @garageOdometerKmLabel.
  ///
  /// In en, this message translates to:
  /// **'Odometer (km)'**
  String get garageOdometerKmLabel;

  /// No description provided for @garageLocationOptionalLabel.
  ///
  /// In en, this message translates to:
  /// **'Location (optional)'**
  String get garageLocationOptionalLabel;

  /// No description provided for @garageAddIntervalTitle.
  ///
  /// In en, this message translates to:
  /// **'Add interval'**
  String get garageAddIntervalTitle;

  /// No description provided for @garageNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get garageNameLabel;

  /// No description provided for @garageIntervalKmLabel.
  ///
  /// In en, this message translates to:
  /// **'Interval (km)'**
  String get garageIntervalKmLabel;

  /// No description provided for @garageNoFillUpsYet.
  ///
  /// In en, this message translates to:
  /// **'No fill-ups logged yet'**
  String get garageNoFillUpsYet;

  /// No description provided for @garageAvg30Day.
  ///
  /// In en, this message translates to:
  /// **'30-day avg'**
  String get garageAvg30Day;

  /// No description provided for @garageFills30d.
  ///
  /// In en, this message translates to:
  /// **'Fills (30d)'**
  String get garageFills30d;

  /// No description provided for @garageLitres30d.
  ///
  /// In en, this message translates to:
  /// **'Litres (30d)'**
  String get garageLitres30d;

  /// No description provided for @garageNoMaintenanceYet.
  ///
  /// In en, this message translates to:
  /// **'No maintenance intervals yet'**
  String get garageNoMaintenanceYet;

  /// No description provided for @garageKmOverdue.
  ///
  /// In en, this message translates to:
  /// **'{km} km overdue'**
  String garageKmOverdue(int km);

  /// No description provided for @garageMarkDoneToday.
  ///
  /// In en, this message translates to:
  /// **'Mark done today'**
  String get garageMarkDoneToday;

  /// No description provided for @expensesTitle.
  ///
  /// In en, this message translates to:
  /// **'Expenses'**
  String get expensesTitle;

  /// No description provided for @categoryAllExpenses.
  ///
  /// In en, this message translates to:
  /// **'All Expenses'**
  String get categoryAllExpenses;

  /// No description provided for @categoryFuel.
  ///
  /// In en, this message translates to:
  /// **'Fuel'**
  String get categoryFuel;

  /// No description provided for @categoryRepairs.
  ///
  /// In en, this message translates to:
  /// **'Repairs'**
  String get categoryRepairs;

  /// No description provided for @categoryAccessories.
  ///
  /// In en, this message translates to:
  /// **'Accessories'**
  String get categoryAccessories;

  /// No description provided for @categoryRidingGear.
  ///
  /// In en, this message translates to:
  /// **'Riding Gear'**
  String get categoryRidingGear;

  /// No description provided for @categoryFood.
  ///
  /// In en, this message translates to:
  /// **'Food'**
  String get categoryFood;

  /// No description provided for @categoryStay.
  ///
  /// In en, this message translates to:
  /// **'Stay'**
  String get categoryStay;

  /// No description provided for @categoryTransport.
  ///
  /// In en, this message translates to:
  /// **'Transport'**
  String get categoryTransport;

  /// No description provided for @categoryOthers.
  ///
  /// In en, this message translates to:
  /// **'Others'**
  String get categoryOthers;

  /// No description provided for @expensesTotalLabel.
  ///
  /// In en, this message translates to:
  /// **'Total'**
  String get expensesTotalLabel;

  /// No description provided for @expensesExportTooltip.
  ///
  /// In en, this message translates to:
  /// **'Export'**
  String get expensesExportTooltip;

  /// No description provided for @expensesNoneForFilter.
  ///
  /// In en, this message translates to:
  /// **'No expenses for this category and period.'**
  String get expensesNoneForFilter;

  /// No description provided for @expensesExportCsv.
  ///
  /// In en, this message translates to:
  /// **'Excel / CSV'**
  String get expensesExportCsv;

  /// No description provided for @expensesExportDocument.
  ///
  /// In en, this message translates to:
  /// **'Document'**
  String get expensesExportDocument;

  /// No description provided for @expensesAddTitle.
  ///
  /// In en, this message translates to:
  /// **'Add expense'**
  String get expensesAddTitle;

  /// No description provided for @expensesCategoryLabel.
  ///
  /// In en, this message translates to:
  /// **'Category'**
  String get expensesCategoryLabel;

  /// No description provided for @expensesAmountLabel.
  ///
  /// In en, this message translates to:
  /// **'Amount ({symbol})'**
  String expensesAmountLabel(String symbol);

  /// No description provided for @expensesNoteOptionalLabel.
  ///
  /// In en, this message translates to:
  /// **'Note (optional)'**
  String get expensesNoteOptionalLabel;

  /// No description provided for @expensesAllTime.
  ///
  /// In en, this message translates to:
  /// **'All time'**
  String get expensesAllTime;

  /// No description provided for @ridesTitle.
  ///
  /// In en, this message translates to:
  /// **'Rides'**
  String get ridesTitle;

  /// No description provided for @rideDurationSummary.
  ///
  /// In en, this message translates to:
  /// **'{km} km · {min}m'**
  String rideDurationSummary(String km, int min);

  /// No description provided for @rideDateAvgSpeed.
  ///
  /// In en, this message translates to:
  /// **'{date} · avg {speed} km/h'**
  String rideDateAvgSpeed(String date, String speed);

  /// No description provided for @offlineMapsTitle.
  ///
  /// In en, this message translates to:
  /// **'Offline maps'**
  String get offlineMapsTitle;

  /// No description provided for @offlineMapsSearchHint.
  ///
  /// In en, this message translates to:
  /// **'Search regions'**
  String get offlineMapsSearchHint;

  /// No description provided for @offlineMapsDownloadedSection.
  ///
  /// In en, this message translates to:
  /// **'Downloaded'**
  String get offlineMapsDownloadedSection;

  /// No description provided for @offlineMapsNothingDownloaded.
  ///
  /// In en, this message translates to:
  /// **'No maps downloaded'**
  String get offlineMapsNothingDownloaded;

  /// No description provided for @offlineMapsNothingDownloadedSub.
  ///
  /// In en, this message translates to:
  /// **'Without maps the dash cannot show navigation'**
  String get offlineMapsNothingDownloadedSub;

  /// No description provided for @offlineMapsServerUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Map server unavailable'**
  String get offlineMapsServerUnavailable;

  /// No description provided for @offlineMapsRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get offlineMapsRetry;

  /// No description provided for @offlineMapsStaleCache.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t check for updates'**
  String get offlineMapsStaleCache;

  /// No description provided for @offlineMapsAppTooOld.
  ///
  /// In en, this message translates to:
  /// **'Update the app to keep using maps'**
  String get offlineMapsAppTooOld;

  /// No description provided for @offlineMapsLoadingRegions.
  ///
  /// In en, this message translates to:
  /// **'Loading the list of regions…'**
  String get offlineMapsLoadingRegions;

  /// No description provided for @offlineMapsNoResults.
  ///
  /// In en, this message translates to:
  /// **'Nothing found'**
  String get offlineMapsNoResults;

  /// No description provided for @offlineMapsCancelTitle.
  ///
  /// In en, this message translates to:
  /// **'Cancel download'**
  String get offlineMapsCancelTitle;

  /// No description provided for @offlineMapsUpdateAvailable.
  ///
  /// In en, this message translates to:
  /// **'Update available'**
  String get offlineMapsUpdateAvailable;

  /// No description provided for @offlineMapsDeleteAction.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get offlineMapsDeleteAction;

  /// No description provided for @offlineMapsDeleteTitle.
  ///
  /// In en, this message translates to:
  /// **'Delete {name}?'**
  String offlineMapsDeleteTitle(String name);

  /// No description provided for @offlineMapsExitNavigationFirst.
  ///
  /// In en, this message translates to:
  /// **'Exit navigation mode first'**
  String get offlineMapsExitNavigationFirst;

  /// No description provided for @offlineMapsNoSpace.
  ///
  /// In en, this message translates to:
  /// **'Not enough space on the device'**
  String get offlineMapsNoSpace;

  /// No description provided for @offlineMapsEnqueueFailed.
  ///
  /// In en, this message translates to:
  /// **'The system downloader is unavailable'**
  String get offlineMapsEnqueueFailed;

  /// No description provided for @offlineMapsDownloadFailed.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t download the map'**
  String get offlineMapsDownloadFailed;

  /// No description provided for @offlineMapsPackCorrupt.
  ///
  /// In en, this message translates to:
  /// **'The map on the server is damaged — try again later'**
  String get offlineMapsPackCorrupt;

  /// No description provided for @offlineMapsDeleteFailed.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t delete the map'**
  String get offlineMapsDeleteFailed;

  /// No description provided for @settingsOfflineMapsTitle.
  ///
  /// In en, this message translates to:
  /// **'Offline maps'**
  String get settingsOfflineMapsTitle;

  /// No description provided for @settingsOfflineMapsSubtitle.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{Nothing downloaded} one{{count} map, {size}} other{{count} maps, {size}}}'**
  String settingsOfflineMapsSubtitle(num count, String size);

  /// No description provided for @settingsMapTheme.
  ///
  /// In en, this message translates to:
  /// **'Map theme'**
  String get settingsMapTheme;

  /// No description provided for @settingsMapThemeLight.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get settingsMapThemeLight;

  /// No description provided for @settingsMapThemeDark.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get settingsMapThemeDark;

  /// No description provided for @homeNoOfflineMaps.
  ///
  /// In en, this message translates to:
  /// **'You have no downloaded maps'**
  String get homeNoOfflineMaps;

  /// No description provided for @homeNoOfflineMapsSub.
  ///
  /// In en, this message translates to:
  /// **'Download a region to enable navigation'**
  String get homeNoOfflineMapsSub;

  /// No description provided for @homeNeedMapsForNavigation.
  ///
  /// In en, this message translates to:
  /// **'Downloaded maps required'**
  String get homeNeedMapsForNavigation;

  /// No description provided for @dashNoOfflineMaps.
  ///
  /// In en, this message translates to:
  /// **'No maps downloaded'**
  String get dashNoOfflineMaps;

  /// No description provided for @settingsTitle.
  ///
  /// In en, this message translates to:
  /// **'More'**
  String get settingsTitle;

  /// No description provided for @settingsDashConnection.
  ///
  /// In en, this message translates to:
  /// **'Dash connection'**
  String get settingsDashConnection;

  /// No description provided for @settingsNotPaired.
  ///
  /// In en, this message translates to:
  /// **'Not paired — discovers by prefix \"{prefix}\"'**
  String settingsNotPaired(String prefix);

  /// No description provided for @settingsStageWifi.
  ///
  /// In en, this message translates to:
  /// **'Stage: {stage} · WiFi: {wifi}'**
  String settingsStageWifi(String stage, String wifi);

  /// No description provided for @settingsSetExactSsid.
  ///
  /// In en, this message translates to:
  /// **'Set exact dash SSID'**
  String get settingsSetExactSsid;

  /// No description provided for @settingsSetWifiPassword.
  ///
  /// In en, this message translates to:
  /// **'Set WiFi password'**
  String get settingsSetWifiPassword;

  /// No description provided for @settingsDefaultPasswordSub.
  ///
  /// In en, this message translates to:
  /// **'Default: 12345678 (factory)'**
  String get settingsDefaultPasswordSub;

  /// No description provided for @settingsForgetDash.
  ///
  /// In en, this message translates to:
  /// **'Forget dash'**
  String get settingsForgetDash;

  /// No description provided for @settingsForgetDashSub.
  ///
  /// In en, this message translates to:
  /// **'Re-run prefix discovery on next connect'**
  String get settingsForgetDashSub;

  /// No description provided for @settingsCurrency.
  ///
  /// In en, this message translates to:
  /// **'Currency'**
  String get settingsCurrency;

  /// No description provided for @settingsLogsTitle.
  ///
  /// In en, this message translates to:
  /// **'Debug logs'**
  String get settingsLogsTitle;

  /// No description provided for @settingsLogsSubtitle.
  ///
  /// In en, this message translates to:
  /// **'View app logs'**
  String get settingsLogsSubtitle;

  /// No description provided for @settingsLogsShareFile.
  ///
  /// In en, this message translates to:
  /// **'Share saved log file'**
  String get settingsLogsShareFile;

  /// No description provided for @settingsMediaAccessTitle.
  ///
  /// In en, this message translates to:
  /// **'Media & call notifications'**
  String get settingsMediaAccessTitle;

  /// No description provided for @settingsMediaAccessGranted.
  ///
  /// In en, this message translates to:
  /// **'Access granted'**
  String get settingsMediaAccessGranted;

  /// No description provided for @settingsMediaAccessNotGranted.
  ///
  /// In en, this message translates to:
  /// **'Access not granted — tap to open settings'**
  String get settingsMediaAccessNotGranted;

  /// No description provided for @settingsMapCacheTitle.
  ///
  /// In en, this message translates to:
  /// **'Map cache'**
  String get settingsMapCacheTitle;

  /// No description provided for @settingsMapCacheSize.
  ///
  /// In en, this message translates to:
  /// **'{size} on disk'**
  String settingsMapCacheSize(String size);

  /// No description provided for @settingsMapCacheUnknown.
  ///
  /// In en, this message translates to:
  /// **'Unable to determine size'**
  String get settingsMapCacheUnknown;

  /// No description provided for @settingsMapCacheClearButton.
  ///
  /// In en, this message translates to:
  /// **'Clear'**
  String get settingsMapCacheClearButton;

  /// No description provided for @settingsMapCacheUnitBytes.
  ///
  /// In en, this message translates to:
  /// **'B'**
  String get settingsMapCacheUnitBytes;

  /// No description provided for @settingsMapCacheUnitKb.
  ///
  /// In en, this message translates to:
  /// **'KB'**
  String get settingsMapCacheUnitKb;

  /// No description provided for @settingsMapCacheUnitMb.
  ///
  /// In en, this message translates to:
  /// **'MB'**
  String get settingsMapCacheUnitMb;

  /// No description provided for @settingsMapCacheUnitGb.
  ///
  /// In en, this message translates to:
  /// **'GB'**
  String get settingsMapCacheUnitGb;

  /// No description provided for @settingsAboutTitle.
  ///
  /// In en, this message translates to:
  /// **'SnatchDash'**
  String get settingsAboutTitle;

  /// No description provided for @settingsAboutSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Flutter port · maps/nav via Yandex MapKit'**
  String get settingsAboutSubtitle;

  /// No description provided for @settingsAboutYandexTermsLink.
  ///
  /// In en, this message translates to:
  /// **'Yandex Maps terms of use'**
  String get settingsAboutYandexTermsLink;

  /// No description provided for @settingsAboutMapDataTitle.
  ///
  /// In en, this message translates to:
  /// **'Map on the dash'**
  String get settingsAboutMapDataTitle;

  /// No description provided for @settingsAboutOsmLink.
  ///
  /// In en, this message translates to:
  /// **'© OpenStreetMap contributors (ODbL)'**
  String get settingsAboutOsmLink;

  /// No description provided for @settingsAboutOpenMapTilesLink.
  ///
  /// In en, this message translates to:
  /// **'© OpenMapTiles — style and schema (CC BY 4.0)'**
  String get settingsAboutOpenMapTilesLink;

  /// No description provided for @settingsAboutMapLibreLink.
  ///
  /// In en, this message translates to:
  /// **'MapLibre GL Native (BSD-2-Clause)'**
  String get settingsAboutMapLibreLink;

  /// No description provided for @settingsAboutFontsLink.
  ///
  /// In en, this message translates to:
  /// **'Noto Sans (SIL Open Font License 1.1)'**
  String get settingsAboutFontsLink;

  /// No description provided for @settingsUpdatesTitle.
  ///
  /// In en, this message translates to:
  /// **'Updates'**
  String get settingsUpdatesTitle;

  /// No description provided for @settingsUpdatesCurrentVersion.
  ///
  /// In en, this message translates to:
  /// **'Current version: {version}'**
  String settingsUpdatesCurrentVersion(String version);

  /// No description provided for @settingsUpdatesAutoUpdate.
  ///
  /// In en, this message translates to:
  /// **'Automatic updates'**
  String get settingsUpdatesAutoUpdate;

  /// No description provided for @settingsUpdatesChannelNightly.
  ///
  /// In en, this message translates to:
  /// **'Nightly builds'**
  String get settingsUpdatesChannelNightly;

  /// No description provided for @settingsUpdatesChannelNightlySub.
  ///
  /// In en, this message translates to:
  /// **'Daily builds from main — less stable, may break'**
  String get settingsUpdatesChannelNightlySub;

  /// No description provided for @settingsUpdatesCheckButton.
  ///
  /// In en, this message translates to:
  /// **'Check for updates'**
  String get settingsUpdatesCheckButton;

  /// No description provided for @settingsUpdatesStatusChecking.
  ///
  /// In en, this message translates to:
  /// **'Checking…'**
  String get settingsUpdatesStatusChecking;

  /// No description provided for @settingsUpdatesStatusUpToDate.
  ///
  /// In en, this message translates to:
  /// **'You\'re on the latest version'**
  String get settingsUpdatesStatusUpToDate;

  /// No description provided for @settingsUpdatesStatusAvailable.
  ///
  /// In en, this message translates to:
  /// **'Version {version} available'**
  String settingsUpdatesStatusAvailable(String version);

  /// No description provided for @settingsUpdatesStatusError.
  ///
  /// In en, this message translates to:
  /// **'Check failed: {message}'**
  String settingsUpdatesStatusError(String message);

  /// No description provided for @settingsUpdatesDownloadButton.
  ///
  /// In en, this message translates to:
  /// **'Download & install'**
  String get settingsUpdatesDownloadButton;

  /// No description provided for @settingsUpdatesDownloading.
  ///
  /// In en, this message translates to:
  /// **'Downloading… {percent}%'**
  String settingsUpdatesDownloading(String percent);

  /// No description provided for @settingsUpdatesDownloadingIndeterminate.
  ///
  /// In en, this message translates to:
  /// **'Downloading…'**
  String get settingsUpdatesDownloadingIndeterminate;

  /// No description provided for @settingsUpdatesNeedsPermission.
  ///
  /// In en, this message translates to:
  /// **'Allow installing apps from this source to continue'**
  String get settingsUpdatesNeedsPermission;

  /// No description provided for @settingsUpdatesGrantPermissionButton.
  ///
  /// In en, this message translates to:
  /// **'Grant permission'**
  String get settingsUpdatesGrantPermissionButton;

  /// No description provided for @updateAvailableTitle.
  ///
  /// In en, this message translates to:
  /// **'Update available: {version}'**
  String updateAvailableTitle(String version);

  /// No description provided for @updateAvailableBody.
  ///
  /// In en, this message translates to:
  /// **'A new version is ready to download.'**
  String get updateAvailableBody;

  /// No description provided for @updateLater.
  ///
  /// In en, this message translates to:
  /// **'Later'**
  String get updateLater;

  /// No description provided for @updateDownload.
  ///
  /// In en, this message translates to:
  /// **'Download'**
  String get updateDownload;

  /// No description provided for @settingsSsidDialogTitle.
  ///
  /// In en, this message translates to:
  /// **'Dash SSID'**
  String get settingsSsidDialogTitle;

  /// No description provided for @settingsExactSsidLabel.
  ///
  /// In en, this message translates to:
  /// **'Exact SSID'**
  String get settingsExactSsidLabel;

  /// No description provided for @settingsWifiPasswordDialogTitle.
  ///
  /// In en, this message translates to:
  /// **'WiFi password'**
  String get settingsWifiPasswordDialogTitle;

  /// No description provided for @settingsPasswordLabel.
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get settingsPasswordLabel;

  /// No description provided for @currencyNameInr.
  ///
  /// In en, this message translates to:
  /// **'Indian rupee'**
  String get currencyNameInr;

  /// No description provided for @currencyNameUsd.
  ///
  /// In en, this message translates to:
  /// **'US dollar'**
  String get currencyNameUsd;

  /// No description provided for @currencyNameEur.
  ///
  /// In en, this message translates to:
  /// **'Euro'**
  String get currencyNameEur;

  /// No description provided for @currencyNameGbp.
  ///
  /// In en, this message translates to:
  /// **'British pound'**
  String get currencyNameGbp;

  /// No description provided for @currencyNameAud.
  ///
  /// In en, this message translates to:
  /// **'Australian dollar'**
  String get currencyNameAud;

  /// No description provided for @currencyNameCad.
  ///
  /// In en, this message translates to:
  /// **'Canadian dollar'**
  String get currencyNameCad;

  /// No description provided for @currencyNameSgd.
  ///
  /// In en, this message translates to:
  /// **'Singapore dollar'**
  String get currencyNameSgd;

  /// No description provided for @currencyNameAed.
  ///
  /// In en, this message translates to:
  /// **'UAE dirham'**
  String get currencyNameAed;

  /// No description provided for @currencyNameRub.
  ///
  /// In en, this message translates to:
  /// **'Russian ruble'**
  String get currencyNameRub;

  /// No description provided for @vehiclesTitle.
  ///
  /// In en, this message translates to:
  /// **'Vehicles'**
  String get vehiclesTitle;

  /// No description provided for @vehiclesAdd.
  ///
  /// In en, this message translates to:
  /// **'Add vehicle'**
  String get vehiclesAdd;

  /// No description provided for @vehiclesPucLabel.
  ///
  /// In en, this message translates to:
  /// **'PUC'**
  String get vehiclesPucLabel;

  /// No description provided for @vehiclesInsuranceLabel.
  ///
  /// In en, this message translates to:
  /// **'Insurance'**
  String get vehiclesInsuranceLabel;

  /// No description provided for @vehiclesServiceLabel.
  ///
  /// In en, this message translates to:
  /// **'Service'**
  String get vehiclesServiceLabel;

  /// No description provided for @vehiclesCurrentChip.
  ///
  /// In en, this message translates to:
  /// **'Current vehicle'**
  String get vehiclesCurrentChip;

  /// No description provided for @vehiclesSetCurrent.
  ///
  /// In en, this message translates to:
  /// **'Set current'**
  String get vehiclesSetCurrent;

  /// No description provided for @vehiclesEditTitle.
  ///
  /// In en, this message translates to:
  /// **'Edit vehicle'**
  String get vehiclesEditTitle;

  /// No description provided for @vehiclesAddDialogTitle.
  ///
  /// In en, this message translates to:
  /// **'Add vehicle'**
  String get vehiclesAddDialogTitle;

  /// No description provided for @vehiclesNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Vehicle name'**
  String get vehiclesNameLabel;

  /// No description provided for @vehiclesNicknameLabel.
  ///
  /// In en, this message translates to:
  /// **'Nickname'**
  String get vehiclesNicknameLabel;

  /// No description provided for @vehiclesPucExpiryLabel.
  ///
  /// In en, this message translates to:
  /// **'PUC expiry (DD-MMM-YYYY)'**
  String get vehiclesPucExpiryLabel;

  /// No description provided for @vehiclesInsuranceExpiryLabel.
  ///
  /// In en, this message translates to:
  /// **'Insurance expiry (DD-MMM-YYYY)'**
  String get vehiclesInsuranceExpiryLabel;

  /// No description provided for @vehiclesNotSet.
  ///
  /// In en, this message translates to:
  /// **'Not set'**
  String get vehiclesNotSet;

  /// No description provided for @maintChannelName.
  ///
  /// In en, this message translates to:
  /// **'Maintenance reminders'**
  String get maintChannelName;

  /// No description provided for @maintChannelDesc.
  ///
  /// In en, this message translates to:
  /// **'Alerts when a service interval is due'**
  String get maintChannelDesc;

  /// No description provided for @maintNotifTitleSingle.
  ///
  /// In en, this message translates to:
  /// **'Maintenance due'**
  String get maintNotifTitleSingle;

  /// No description provided for @maintNotifTitleMultiple.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, one{{count} service due} few{{count} services due} many{{count} services due} other{{count} services due}}'**
  String maintNotifTitleMultiple(num count);

  /// No description provided for @maintLineOverdueByDate.
  ///
  /// In en, this message translates to:
  /// **'{name} — overdue by date'**
  String maintLineOverdueByDate(String name);

  /// No description provided for @maintLineOverdueKm.
  ///
  /// In en, this message translates to:
  /// **'{name} — overdue {km} km'**
  String maintLineOverdueKm(String name, int km);

  /// No description provided for @maintLineDueKmDays.
  ///
  /// In en, this message translates to:
  /// **'{name} — due in {km} km or {days} days'**
  String maintLineDueKmDays(String name, int km, int days);

  /// No description provided for @maintLineDueKm.
  ///
  /// In en, this message translates to:
  /// **'{name} — due in {km} km'**
  String maintLineDueKm(String name, int km);

  /// No description provided for @voiceArrived.
  ///
  /// In en, this message translates to:
  /// **'You have arrived at your destination'**
  String get voiceArrived;

  /// No description provided for @voiceNow.
  ///
  /// In en, this message translates to:
  /// **'Now, {phrase}'**
  String voiceNow(String phrase);

  /// No description provided for @voiceIn.
  ///
  /// In en, this message translates to:
  /// **'In {distance}, {phrase}'**
  String voiceIn(String distance, String phrase);

  /// No description provided for @voiceTurnArrive.
  ///
  /// In en, this message translates to:
  /// **'you have arrived'**
  String get voiceTurnArrive;

  /// No description provided for @voiceTurnContinue.
  ///
  /// In en, this message translates to:
  /// **'continue straight'**
  String get voiceTurnContinue;

  /// No description provided for @voiceTurnLeft.
  ///
  /// In en, this message translates to:
  /// **'turn left'**
  String get voiceTurnLeft;

  /// No description provided for @voiceTurnRight.
  ///
  /// In en, this message translates to:
  /// **'turn right'**
  String get voiceTurnRight;

  /// No description provided for @voiceTurnUturnLeft.
  ///
  /// In en, this message translates to:
  /// **'make a U-turn to the left'**
  String get voiceTurnUturnLeft;

  /// No description provided for @voiceTurnUturnRight.
  ///
  /// In en, this message translates to:
  /// **'make a U-turn to the right'**
  String get voiceTurnUturnRight;

  /// No description provided for @voiceDistanceKm.
  ///
  /// In en, this message translates to:
  /// **'{km} kilometers'**
  String voiceDistanceKm(String km);

  /// No description provided for @voiceDistance500m.
  ///
  /// In en, this message translates to:
  /// **'500 meters'**
  String get voiceDistance500m;

  /// No description provided for @voiceDistance200m.
  ///
  /// In en, this message translates to:
  /// **'200 meters'**
  String get voiceDistance200m;

  /// No description provided for @voiceDistanceM.
  ///
  /// In en, this message translates to:
  /// **'{m} meters'**
  String voiceDistanceM(int m);

  /// No description provided for @voiceDashDisconnected.
  ///
  /// In en, this message translates to:
  /// **'Dash disconnected. Restart the dash to reconnect.'**
  String get voiceDashDisconnected;

  /// No description provided for @voiceDashReconnected.
  ///
  /// In en, this message translates to:
  /// **'Dash reconnected'**
  String get voiceDashReconnected;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'ru'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'ru':
      return AppLocalizationsRu();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
