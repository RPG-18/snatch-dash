/// Display names for tile packs, keyed by `index.json`'s `code`.
///
/// Deliberately **not** in the ARB files: these are data, not interface
/// strings — the manifest carries no names on purpose (the tile generator
/// must not know about localisation, see spec/remote_map_server.md), and the
/// set changes with the corpus rather than with the app's copy.
///
/// Two entries are merged agglomerations rather than single subjects
/// (`ru-len-spe`, `ru-mos-mow`) — that is exactly why the name comes from this
/// table instead of being derived from the code.
library;

typedef _Name = ({String ru, String en});

/// Codes are matched verbatim against the manifest. A code missing here is not
/// an error — see [mapRegionName].
const Map<String, _Name> _names = {
  'ru-ad': (ru: 'Адыгея', en: 'Adygea'),
  'ru-al': (ru: 'Республика Алтай', en: 'Altai Republic'),
  'ru-alt': (ru: 'Алтайский край', en: 'Altai Krai'),
  'ru-amu': (ru: 'Амурская область', en: 'Amur Oblast'),
  'ru-ark': (ru: 'Архангельская область', en: 'Arkhangelsk Oblast'),
  'ru-ast': (ru: 'Астраханская область', en: 'Astrakhan Oblast'),
  'ru-ba': (ru: 'Башкортостан', en: 'Bashkortostan'),
  'ru-bel': (ru: 'Белгородская область', en: 'Belgorod Oblast'),
  'ru-bry': (ru: 'Брянская область', en: 'Bryansk Oblast'),
  'ru-bu': (ru: 'Бурятия', en: 'Buryatia'),
  'ru-ce': (ru: 'Чечня', en: 'Chechnya'),
  'ru-che': (ru: 'Челябинская область', en: 'Chelyabinsk Oblast'),
  'ru-cu': (ru: 'Чувашия', en: 'Chuvashia'),
  'ru-da': (ru: 'Дагестан', en: 'Dagestan'),
  'ru-in': (ru: 'Ингушетия', en: 'Ingushetia'),
  'ru-irk': (ru: 'Иркутская область', en: 'Irkutsk Oblast'),
  'ru-iva': (ru: 'Ивановская область', en: 'Ivanovo Oblast'),
  'ru-kam': (ru: 'Камчатский край', en: 'Kamchatka Krai'),
  'ru-kb': (ru: 'Кабардино-Балкария', en: 'Kabardino-Balkaria'),
  'ru-kc': (ru: 'Карачаево-Черкесия', en: 'Karachay-Cherkessia'),
  'ru-kda': (ru: 'Краснодарский край', en: 'Krasnodar Krai'),
  'ru-kem': (ru: 'Кемеровская область', en: 'Kemerovo Oblast'),
  'ru-kgd': (ru: 'Калининградская область', en: 'Kaliningrad Oblast'),
  'ru-kgn': (ru: 'Курганская область', en: 'Kurgan Oblast'),
  'ru-kha': (ru: 'Хабаровский край', en: 'Khabarovsk Krai'),
  'ru-khm': (ru: 'Ханты-Мансийский автономный округ', en: 'Khanty-Mansi Autonomous Okrug'),
  'ru-kir': (ru: 'Кировская область', en: 'Kirov Oblast'),
  'ru-kk': (ru: 'Хакасия', en: 'Khakassia'),
  'ru-kl': (ru: 'Калмыкия', en: 'Kalmykia'),
  'ru-klu': (ru: 'Калужская область', en: 'Kaluga Oblast'),
  'ru-ko': (ru: 'Коми', en: 'Komi Republic'),
  'ru-kos': (ru: 'Костромская область', en: 'Kostroma Oblast'),
  'ru-kr': (ru: 'Карелия', en: 'Karelia'),
  'ru-krs': (ru: 'Курская область', en: 'Kursk Oblast'),
  'ru-kya': (ru: 'Красноярский край', en: 'Krasnoyarsk Krai'),
  'ru-len-spe': (ru: 'Санкт-Петербург и Ленинградская область', en: 'Saint Petersburg and Leningrad Oblast'),
  'ru-lip': (ru: 'Липецкая область', en: 'Lipetsk Oblast'),
  'ru-mag': (ru: 'Магаданская область', en: 'Magadan Oblast'),
  'ru-me': (ru: 'Марий Эл', en: 'Mari El'),
  'ru-mo': (ru: 'Мордовия', en: 'Mordovia'),
  'ru-mos-mow': (ru: 'Москва и Московская область', en: 'Moscow and Moscow Oblast'),
  'ru-mur': (ru: 'Мурманская область', en: 'Murmansk Oblast'),
  'ru-nen': (ru: 'Ненецкий автономный округ', en: 'Nenets Autonomous Okrug'),
  'ru-ngr': (ru: 'Новгородская область', en: 'Novgorod Oblast'),
  'ru-niz': (ru: 'Нижегородская область', en: 'Nizhny Novgorod Oblast'),
  'ru-nvs': (ru: 'Новосибирская область', en: 'Novosibirsk Oblast'),
  'ru-oms': (ru: 'Омская область', en: 'Omsk Oblast'),
  'ru-ore': (ru: 'Оренбургская область', en: 'Orenburg Oblast'),
  'ru-orl': (ru: 'Орловская область', en: 'Oryol Oblast'),
  'ru-per': (ru: 'Пермский край', en: 'Perm Krai'),
  'ru-pnz': (ru: 'Пензенская область', en: 'Penza Oblast'),
  'ru-pri': (ru: 'Приморский край', en: 'Primorsky Krai'),
  'ru-psk': (ru: 'Псковская область', en: 'Pskov Oblast'),
  'ru-ros': (ru: 'Ростовская область', en: 'Rostov Oblast'),
  'ru-rya': (ru: 'Рязанская область', en: 'Ryazan Oblast'),
  'ru-sa': (ru: 'Якутия', en: 'Sakha (Yakutia)'),
  'ru-sak': (ru: 'Сахалинская область', en: 'Sakhalin Oblast'),
  'ru-sam': (ru: 'Самарская область', en: 'Samara Oblast'),
  'ru-sar': (ru: 'Саратовская область', en: 'Saratov Oblast'),
  'ru-se': (ru: 'Северная Осетия — Алания', en: 'North Ossetia–Alania'),
  'ru-smo': (ru: 'Смоленская область', en: 'Smolensk Oblast'),
  'ru-sta': (ru: 'Ставропольский край', en: 'Stavropol Krai'),
  'ru-sve': (ru: 'Свердловская область', en: 'Sverdlovsk Oblast'),
  'ru-ta': (ru: 'Татарстан', en: 'Tatarstan'),
  'ru-tam': (ru: 'Тамбовская область', en: 'Tambov Oblast'),
  'ru-tom': (ru: 'Томская область', en: 'Tomsk Oblast'),
  'ru-tul': (ru: 'Тульская область', en: 'Tula Oblast'),
  'ru-tve': (ru: 'Тверская область', en: 'Tver Oblast'),
  'ru-ty': (ru: 'Тыва', en: 'Tuva'),
  'ru-tyu': (ru: 'Тюменская область', en: 'Tyumen Oblast'),
  'ru-ud': (ru: 'Удмуртия', en: 'Udmurtia'),
  'ru-uly': (ru: 'Ульяновская область', en: 'Ulyanovsk Oblast'),
  'ru-vgg': (ru: 'Волгоградская область', en: 'Volgograd Oblast'),
  'ru-vla': (ru: 'Владимирская область', en: 'Vladimir Oblast'),
  'ru-vlg': (ru: 'Вологодская область', en: 'Vologda Oblast'),
  'ru-vor': (ru: 'Воронежская область', en: 'Voronezh Oblast'),
  'ru-yan': (ru: 'Ямало-Ненецкий автономный округ', en: 'Yamalo-Nenets Autonomous Okrug'),
  'ru-yar': (ru: 'Ярославская область', en: 'Yaroslavl Oblast'),
  'ru-yev': (ru: 'Еврейская автономная область', en: 'Jewish Autonomous Oblast'),
  'ru-zab': (ru: 'Забайкальский край', en: 'Zabaykalsky Krai'),
};

/// Localised pack name, or the raw [code] when the corpus has grown a pack this
/// build of the app doesn't know about.
///
/// Falling back to the code is deliberate: the manifest is the only source of
/// truth for what exists, so a new pack must remain downloadable rather than
/// disappear from the list because its name is missing.
String mapRegionName(String code, String languageCode) {
  final name = _names[code];
  if (name == null) return code;
  return languageCode == 'ru' ? name.ru : name.en;
}

/// Whether [code] has a translation — for tests and for the search index,
/// which has nothing to match on when it doesn't.
bool hasMapRegionName(String code) => _names.containsKey(code);

/// Every code this build knows a name for. Used by the test that pins the
/// table against the published manifest.
Iterable<String> get knownMapRegionCodes => _names.keys;
