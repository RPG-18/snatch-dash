// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Russian (`ru`).
class AppLocalizationsRu extends AppLocalizations {
  AppLocalizationsRu([String locale = 'ru']) : super(locale);

  @override
  String get navHome => 'Главная';

  @override
  String get navVehicles => 'Техника';

  @override
  String get navExpenses => 'Расходы';

  @override
  String get navGarage => 'Гараж';

  @override
  String get navMore => 'Ещё';

  @override
  String get actionCancel => 'Отмена';

  @override
  String get actionSave => 'Сохранить';

  @override
  String get actionDelete => 'Удалить';

  @override
  String unitKm(String value) {
    return '$value км';
  }

  @override
  String unitM(String value) {
    return '$value м';
  }

  @override
  String unitMin(String value) {
    return '$value мин';
  }

  @override
  String unitHourMin(String hours, String minutes) {
    return '$hours ч $minutes мин';
  }

  @override
  String unitDayHourMin(String days, String hours, String minutes) {
    return '$days дн $hours ч $minutes мин';
  }

  @override
  String get homeStatusConnected => 'Подключено';

  @override
  String get homeStatusConnectedSub => 'Идёт трансляция на Tripper Dash';

  @override
  String get homeStatusSearching => 'Поиск…';

  @override
  String get homeStatusSearchingSub => 'Поиск Tripper Dash';

  @override
  String get homeStatusOffline => 'Не в сети';

  @override
  String get homeStatusOfflineSub => 'Dash не обнаружен';

  @override
  String get homeStartNavigation => 'Начать навигацию';

  @override
  String get homeStartNavigationSub =>
      'Выберите пункт назначения и отправьте на dash';

  @override
  String get homeDashView => 'Экран dash';

  @override
  String get homeConnectToDash => 'Подключиться к dash';

  @override
  String get homeDashViewSub => 'Открыть управление трансляцией';

  @override
  String get homeConnectToDashSub =>
      'Сопряжение, аутентификация и запуск трансляции';

  @override
  String get homeSavedDestinations => 'Сохранённые места';

  @override
  String get homeNoSavedDestinations =>
      'Пока нет сохранённых мест. Найдите место на вкладке «Маршрут» и нажмите на значок закладки, чтобы сохранить его.';

  @override
  String get homeRidesLabel => 'Поездки';

  @override
  String get homeNoRidesYet => 'Поездок пока нет';

  @override
  String get homeRidesAutoSub =>
      'Поездки при подключении появляются здесь автоматически';

  @override
  String homeRidesSummary(num count, String km) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count поездки',
      many: '$count поездок',
      few: '$count поездки',
      one: '$count поездка',
    );
    return '$_temp0 · $km км';
  }

  @override
  String get routeTitle => 'Маршрут';

  @override
  String get routeSearchHint => 'Куда';

  @override
  String get routeSearchPrompt => 'Введите не менее 3 символов';

  @override
  String get routeSearchNoResults => 'Ничего не найдено';

  @override
  String get routeSearchError => 'Не удалось выполнить поиск';

  @override
  String get routeResolveError =>
      'Не удалось определить координаты, попробуйте другой вариант';

  @override
  String get routeResolvePickTitle =>
      'Нашлось несколько мест — выберите нужное';

  @override
  String get routePreviewTitle => 'Маршруты';

  @override
  String get routePreviewLoadingRoutes => 'Строим маршруты…';

  @override
  String get routePreviewGo => 'Поехали';

  @override
  String routeVoiceGuidanceTooltip(String mode) {
    return 'Голосовые подсказки: $mode';
  }

  @override
  String get routeSaveDestinationTooltip => 'Сохранить это место';

  @override
  String get routeDestinationCloseTooltip => 'Закрыть';

  @override
  String get routeDestinationGo => 'Маршрут';

  @override
  String get routeDestinationSaved => 'Место сохранено';

  @override
  String get routeShareLinkHint =>
      'Вставьте ссылку Google Maps или geo:широта,долгота';

  @override
  String get routePreview => 'Просмотр';

  @override
  String get routePreviewPrompt => 'Выберите место, чтобы увидеть маршрут';

  @override
  String get routeSendToDash => 'Отправить на Dash';

  @override
  String get routeErrorNoCoordinates =>
      'Не удалось найти координаты по этой ссылке';

  @override
  String get routeErrorNoGpsFix =>
      'Пока нет GPS-сигнала для построения маршрута';

  @override
  String get routeErrorRoutingFailedConnection =>
      'Не удалось построить маршрут — проверьте соединение';

  @override
  String routeErrorRoutingFailedDetail(String detail) {
    return 'Не удалось построить маршрут: $detail';
  }

  @override
  String get voiceModeOff => 'выкл';

  @override
  String get voiceModeChime => 'сигнал';

  @override
  String get voiceModeFull => 'голос';

  @override
  String get dashStageOffline => 'Не в сети';

  @override
  String get dashStageConnecting => 'Подключение…';

  @override
  String get dashStageAuthenticating => 'Аутентификация…';

  @override
  String get dashStageReady => 'Готово';

  @override
  String get dashStageConnected => 'Подключено';

  @override
  String get dashStageError => 'Ошибка';

  @override
  String get dashHeadingUpTooltip => 'По направлению движения';

  @override
  String get dashGpsLost => 'GPS потерян';

  @override
  String get dashGpsWeak => 'Слабый GPS';

  @override
  String dashDistanceRemaining(String km) {
    return 'Осталось $km км';
  }

  @override
  String get dashExitNavigationTooltip => 'Завершить навигацию';

  @override
  String get dashConnect => 'Подключить';

  @override
  String get dashDisconnect => 'Отключить';

  @override
  String get dashGpsPermissionRequired => 'Для подключения нужен доступ к GPS';

  @override
  String garageTitle(int km) {
    return 'Гараж · $km км';
  }

  @override
  String get garageTabFuel => 'Топливо';

  @override
  String get garageTabMaintenance => 'Обслуживание';

  @override
  String get garageSetOdometerTooltip => 'Задать пробег';

  @override
  String get garageSetOdometerTitle => 'Задать пробег';

  @override
  String get garageAddFillUpTitle => 'Добавить заправку';

  @override
  String get garageLitresLabel => 'Литры';

  @override
  String get garageCostLabel => 'Стоимость';

  @override
  String get garageOdometerKmLabel => 'Пробег (км)';

  @override
  String get garageLocationOptionalLabel => 'Место (необязательно)';

  @override
  String get garageAddIntervalTitle => 'Добавить интервал';

  @override
  String get garageNameLabel => 'Название';

  @override
  String get garageIntervalKmLabel => 'Интервал (км)';

  @override
  String get garageNoFillUpsYet => 'Заправок пока нет';

  @override
  String get garageAvg30Day => 'Среднее за 30 дней';

  @override
  String get garageFills30d => 'Заправок (30 дн.)';

  @override
  String get garageLitres30d => 'Литров (30 дн.)';

  @override
  String get garageNoMaintenanceYet => 'Интервалов обслуживания пока нет';

  @override
  String garageKmOverdue(int km) {
    return 'просрочено на $km км';
  }

  @override
  String get garageMarkDoneToday => 'Отметить выполненным сегодня';

  @override
  String get expensesTitle => 'Расходы';

  @override
  String get categoryAllExpenses => 'Все расходы';

  @override
  String get categoryFuel => 'Топливо';

  @override
  String get categoryRepairs => 'Ремонт';

  @override
  String get categoryAccessories => 'Аксессуары';

  @override
  String get categoryRidingGear => 'Экипировка';

  @override
  String get categoryFood => 'Еда';

  @override
  String get categoryStay => 'Проживание';

  @override
  String get categoryTransport => 'Транспорт';

  @override
  String get categoryOthers => 'Прочее';

  @override
  String get expensesTotalLabel => 'Итого';

  @override
  String get expensesExportTooltip => 'Экспорт';

  @override
  String get expensesNoneForFilter =>
      'Нет расходов за этот период и категорию.';

  @override
  String get expensesExportCsv => 'Excel / CSV';

  @override
  String get expensesExportDocument => 'Документ';

  @override
  String get expensesAddTitle => 'Добавить расход';

  @override
  String get expensesCategoryLabel => 'Категория';

  @override
  String expensesAmountLabel(String symbol) {
    return 'Сумма ($symbol)';
  }

  @override
  String get expensesNoteOptionalLabel => 'Заметка (необязательно)';

  @override
  String get expensesAllTime => 'Всё время';

  @override
  String get ridesTitle => 'Поездки';

  @override
  String rideDurationSummary(String km, int min) {
    return '$km км · $min мин';
  }

  @override
  String rideDateAvgSpeed(String date, String speed) {
    return '$date · ср. $speed км/ч';
  }

  @override
  String get settingsTitle => 'Ещё';

  @override
  String get settingsDashConnection => 'Подключение dash';

  @override
  String settingsNotPaired(String prefix) {
    return 'Не сопряжено — поиск по префиксу «$prefix»';
  }

  @override
  String settingsStageWifi(String stage, String wifi) {
    return 'Статус: $stage · WiFi: $wifi';
  }

  @override
  String get settingsSetExactSsid => 'Задать точный SSID dash';

  @override
  String get settingsSetWifiPassword => 'Задать пароль WiFi';

  @override
  String get settingsDefaultPasswordSub => 'По умолчанию: 12345678 (заводской)';

  @override
  String get settingsForgetDash => 'Забыть dash';

  @override
  String get settingsForgetDashSub =>
      'Повторить поиск по префиксу при следующем подключении';

  @override
  String get settingsDashWallpaper => 'Обои dash';

  @override
  String get settingsCurrency => 'Валюта';

  @override
  String get settingsLogsTitle => 'Логи отладки';

  @override
  String get settingsLogsSubtitle => 'Просмотр логов приложения';

  @override
  String get settingsLogsShareFile => 'Поделиться сохранённым логом';

  @override
  String get settingsMediaAccessTitle => 'Уведомления медиа и звонков';

  @override
  String get settingsMediaAccessGranted => 'Доступ предоставлен';

  @override
  String get settingsMediaAccessNotGranted =>
      'Доступ не предоставлен — нажмите, чтобы открыть настройки';

  @override
  String get settingsAboutTitle => 'SnatchDash';

  @override
  String get settingsAboutSubtitle =>
      'Порт на Flutter · карты и навигация через Yandex MapKit';

  @override
  String get settingsSsidDialogTitle => 'SSID dash';

  @override
  String get settingsExactSsidLabel => 'Точный SSID';

  @override
  String get settingsWifiPasswordDialogTitle => 'Пароль WiFi';

  @override
  String get settingsPasswordLabel => 'Пароль';

  @override
  String get settingsClearAll => 'Очистить всё';

  @override
  String get currencyNameInr => 'Индийская рупия';

  @override
  String get currencyNameUsd => 'Доллар США';

  @override
  String get currencyNameEur => 'Евро';

  @override
  String get currencyNameGbp => 'Фунт стерлингов';

  @override
  String get currencyNameAud => 'Австралийский доллар';

  @override
  String get currencyNameCad => 'Канадский доллар';

  @override
  String get currencyNameSgd => 'Сингапурский доллар';

  @override
  String get currencyNameAed => 'Дирхам ОАЭ';

  @override
  String get currencyNameRub => 'Российский рубль';

  @override
  String get vehiclesTitle => 'Техника';

  @override
  String get vehiclesAdd => 'Добавить технику';

  @override
  String get vehiclesPucLabel => 'Техосмотр';

  @override
  String get vehiclesInsuranceLabel => 'Страховка';

  @override
  String get vehiclesServiceLabel => 'ТО';

  @override
  String get vehiclesCurrentChip => 'Текущая техника';

  @override
  String get vehiclesSetCurrent => 'Сделать текущей';

  @override
  String get vehiclesEditTitle => 'Изменить технику';

  @override
  String get vehiclesAddDialogTitle => 'Добавить технику';

  @override
  String get vehiclesNameLabel => 'Название техники';

  @override
  String get vehiclesNicknameLabel => 'Прозвище';

  @override
  String get vehiclesPucExpiryLabel => 'Техосмотр до (ДД-МММ-ГГГГ)';

  @override
  String get vehiclesInsuranceExpiryLabel => 'Страховка до (ДД-МММ-ГГГГ)';

  @override
  String get vehiclesNotSet => 'Не задано';

  @override
  String get maintChannelName => 'Напоминания об обслуживании';

  @override
  String get maintChannelDesc => 'Уведомления о наступлении срока обслуживания';

  @override
  String get maintNotifTitleSingle => 'Требуется обслуживание';

  @override
  String maintNotifTitleMultiple(num count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count услуги требуют внимания',
      many: '$count услуг требуют внимания',
      few: '$count услуги требуют внимания',
      one: '$count услуга требует внимания',
    );
    return '$_temp0';
  }

  @override
  String maintLineOverdueByDate(String name) {
    return '$name — просрочено по дате';
  }

  @override
  String maintLineOverdueKm(String name, int km) {
    return '$name — просрочено на $km км';
  }

  @override
  String maintLineDueKmDays(String name, int km, int days) {
    return '$name — через $km км или $days дн.';
  }

  @override
  String maintLineDueKm(String name, int km) {
    return '$name — через $km км';
  }

  @override
  String get voiceArrived => 'Вы прибыли в пункт назначения';

  @override
  String voiceNow(String phrase) {
    return 'Сейчас $phrase';
  }

  @override
  String voiceIn(String distance, String phrase) {
    return 'Через $distance $phrase';
  }

  @override
  String get voiceTurnArrive => 'вы прибыли';

  @override
  String get voiceTurnContinue => 'продолжайте движение прямо';

  @override
  String voiceDistanceKm(String km) {
    return '$km километра';
  }

  @override
  String get voiceDistance500m => '500 метров';

  @override
  String get voiceDistance200m => '200 метров';

  @override
  String voiceDistanceM(int m) {
    return '$m метров';
  }
}
