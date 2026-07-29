/// One live suggestion from [SuggestApi], per the Yandex Geosuggest API
/// (api/openapi/yandex-suggest-api.yaml). Unlike [PlaceResult], this carries
/// no coordinates — the suggest endpoint only returns text/metadata; a
/// selected suggestion still needs to be resolved to a point (see
/// `RouteSearchController.resolve`).
class SuggestResult {
  const SuggestResult({
    required this.title,
    required this.subtitle,
    required this.tags,
    this.distanceMeters,
  });

  final String title;
  final String subtitle;
  final List<String> tags;

  /// Distance in meters from the `ull` passed in the request, if any.
  final double? distanceMeters;

  /// Text to feed into an exact-match place lookup when resolving this
  /// suggestion to coordinates — combining title+subtitle disambiguates
  /// suggestions that share a bare name (e.g. a chain store).
  String get resolveQuery => subtitle.isEmpty ? title : '$title, $subtitle';

  factory SuggestResult.fromJson(Map<String, dynamic> json) {
    final title = json['title'] as Map<String, dynamic>?;
    final subtitle = json['subtitle'] as Map<String, dynamic>?;
    final distance = json['distance'] as Map<String, dynamic>?;
    return SuggestResult(
      title: title?['text'] as String? ?? '',
      subtitle: subtitle?['text'] as String? ?? '',
      tags: (json['tags'] as List<dynamic>?)?.cast<String>() ?? const [],
      distanceMeters: (distance?['value'] as num?)?.toDouble(),
    );
  }
}
