import 'dart:convert';
import 'dart:developer' as developer;

import 'package:http/http.dart' as http;

import '../models/shared_location.dart';

/// Parses a Google Maps share (text/URL/`geo:`) into a [SharedLocation].
/// Ported from the original app's `util/LocationParser.kt` — same regex set,
/// same allow-listed hosts, same consent-bypass/redirect-follow behavior.
class LocationParser {
  LocationParser._();

  static const _maxBodyBytes = 256 * 1024;
  static const _userAgent = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';

  static const _allowedMapHosts = {
    'maps.google.com',
    'www.google.com',
    'google.com',
    'maps.app.goo.gl',
    'goo.gl',
    'g.co',
  };

  static final _urlRegex = RegExp(r'https?://[^\s)]+');
  static final _coord3d4d = RegExp(r'!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)');
  static final _coordAt = RegExp(r'@(-?\d+\.\d+),(-?\d+\.\d+)');
  static final _coordQ = RegExp(r'[?&](?:q|query|destination|daddr)=(-?\d+\.\d+),\s*(-?\d+\.\d+)');
  static final _coordLl = RegExp(r'[?&]ll=(-?\d+\.\d+),(-?\d+\.\d+)');
  static final _coordGeo = RegExp(r'geo:(-?\d+\.\d+),(-?\d+\.\d+)');
  static final _coordSearch = RegExp(r'/search/(-?\d+\.\d+),\+?(-?\d+\.\d+)');
  static final _placePath = RegExp(r'/place/([^/@?]+)');
  static final _placeQ = RegExp(r'[?&]q=([^&0-9\-@][^&]*)');

  static final _bodyPatterns = [
    _coord3d4d,
    RegExp(r'\[null,null,(-?\d+\.\d{3,}),(-?\d+\.\d{3,})\]'),
    RegExp(r'center=(-?\d+\.\d+)(?:%2C|,)(-?\d+\.\d+)'),
    RegExp(r'"latitude":\s*(-?\d+\.\d+)[^}]{0,60}?"longitude":\s*(-?\d+\.\d+)'),
    _coordAt,
  ];

  /// Synchronous parse of the raw shared text/URI (no network).
  static SharedLocation parse(String text) {
    final trimmed = text.trim();
    _log('parse(): input received');

    final urlMatch = _urlRegex.firstMatch(trimmed)?.group(0)?.let(_trimTrailingPunctuation);
    final candidateUrl = urlMatch ?? (trimmed.startsWith('geo:') ? trimmed : null);
    final url = (candidateUrl != null && _isAllowedShareUri(candidateUrl)) ? candidateUrl : null;
    final rejectedUrl = candidateUrl != null && url == null;

    final isShort = url != null &&
        (url.contains('maps.app.goo.gl') ||
            url.contains('goo.gl/maps') ||
            url.contains('g.co/kgs') ||
            url.contains('//goo.gl/'));

    final textBefore = url != null ? trimmed.substring(0, trimmed.indexOf(url)).trim() : null;
    final lines = textBefore?.split('\n').where((l) => l.trim().isNotEmpty);
    final textName = lines?.isNotEmpty == true
        ? lines!.last.trim().let((s) {
            var out = s;
            if (out.endsWith(':')) out = out.substring(0, out.length - 1);
            if (out.startsWith('Check out')) out = out.substring('Check out'.length);
            return out.trim();
          })
        : null;

    final coords = url != null && !isShort
        ? extractCoords(url)
        : (rejectedUrl ? null : extractCoords(trimmed));

    final name = (textName != null && textName.isNotEmpty && textName != 'Check out')
        ? textName
        : (url != null && !isShort
            ? (extractPlaceName(url) ?? 'Shared location')
            : (coords != null ? 'Dropped pin' : 'Loading…'));

    _log('parse() -> name=\'$name\' hasCoords=${coords != null} short=$isShort acceptedUrl=${url != null}');
    return SharedLocation(
      name: name,
      lat: coords?.$1,
      lng: coords?.$2,
      url: url,
      needsExpansion: coords == null && url != null,
    );
  }

  /// Network resolve of a Maps URL → (coords, place-name). Follows redirects,
  /// bypasses consent interstitials, and scans the page body for the
  /// coordinates Google embeds there.
  static Future<(double, double)?> resolveCoords(String url) async {
    final (coords, _) = await resolve(url);
    return coords;
  }

  static Future<((double, double)?, String?)> resolve(String url) async {
    if (!_isAllowedNetworkUrl(url)) {
      _log('resolve(): rejected URL');
      return (null, null);
    }
    final (finalUrl, body) = await _fetchFollowing(url);
    var coords = extractCoords(finalUrl);
    final name = extractPlaceName(finalUrl);
    coords ??= _scanBody(body);
    _log('resolve() -> hasCoords=${coords != null} hasName=${(name ?? '').isNotEmpty} '
        'finalHost=${_hostOf(finalUrl) ?? 'unknown'}');
    return (coords, name);
  }

  static (double, double)? extractCoords(String s) {
    for (final regex in [_coord3d4d, _coordGeo, _coordSearch, _coordAt, _coordQ, _coordLl]) {
      final m = regex.firstMatch(s);
      if (m == null) continue;
      final lat = double.tryParse(m.group(1) ?? '');
      final lng = double.tryParse(m.group(2) ?? '');
      if (lat == null || lng == null) continue;
      if (_valid(lat, lng)) return (lat, lng);
    }
    return null;
  }

  static String? extractPlaceName(String s) {
    final placeMatch = _placePath.firstMatch(s);
    if (placeMatch != null) {
      final raw = placeMatch.group(1)!.replaceAll('+', ' ');
      final decoded = Uri.decodeComponent(raw).replaceAll('_', ' ').trim();
      return decoded.isEmpty ? null : decoded;
    }
    final qMatch = _placeQ.firstMatch(s);
    if (qMatch != null) {
      final raw = qMatch.group(1)!.replaceAll('+', ' ');
      final decoded = Uri.decodeComponent(raw).trim();
      return decoded.isEmpty ? null : decoded;
    }
    return null;
  }

  // ── Internals ─────────────────────────────────────────────────────────

  static bool _valid(double lat, double lng) =>
      lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0 && !(lat == 0.0 && lng == 0.0);

  static (double, double)? _scanBody(String body) {
    if (body.trim().isEmpty) return null;
    for (final p in _bodyPatterns) {
      final m = p.firstMatch(body);
      if (m == null) continue;
      final lat = double.tryParse(m.group(1) ?? '');
      final lng = double.tryParse(m.group(2) ?? '');
      if (lat == null || lng == null) continue;
      if (_valid(lat, lng)) {
        _log('scanBody matched ${p.pattern.substring(0, p.pattern.length.clamp(0, 24))}');
        return (lat, lng);
      }
    }
    return null;
  }

  /// Follow redirects manually (incl. http↔https), bypass consent, return (finalUrl, body).
  static Future<(String, String)> _fetchFollowing(String start) async {
    var url = start;
    var body = '';
    try {
      for (var hop = 0; hop < 8; hop++) {
        if (url.contains('consent.google') || url.contains('/sorry/')) {
          final m = RegExp(r'continue=([^&]+)').firstMatch(url);
          if (m != null) {
            url = Uri.decodeComponent(m.group(1)!);
            if (!_isAllowedNetworkUrl(url)) return (url, body);
            _log('consent bypass -> host=${_hostOf(url) ?? 'unknown'}');
          }
        }
        if (!_isAllowedNetworkUrl(url)) return (url, body);

        final request = http.Request('GET', Uri.parse(url))
          ..followRedirects = false
          ..headers['User-Agent'] = _userAgent
          ..headers['Accept-Language'] = 'en-US,en;q=0.9';
        final streamed = await request.send().timeout(const Duration(seconds: 9));
        final code = streamed.statusCode;
        final loc = streamed.headers['location'];
        _log('hop $hop: $code locationHost=${loc != null ? _hostOf(_resolvedRedirectUrl(url, loc)) ?? '' : ''}');

        if (code >= 300 && code < 400 && loc != null && loc.isNotEmpty) {
          url = _resolvedRedirectUrl(url, loc);
          if (!_isAllowedNetworkUrl(url)) return (url, '');
          if (extractCoords(url) != null) return (url, '');
        } else {
          final bytes = await streamed.stream.toBytes();
          final limited = bytes.length > _maxBodyBytes ? bytes.sublist(0, _maxBodyBytes) : bytes;
          body = utf8.decode(limited, allowMalformed: true);
          return (url, body);
        }
      }
    } catch (e) {
      _warn('fetchFollowing failed: ${e.runtimeType}');
    }
    return (url, body);
  }

  static bool _isAllowedShareUri(String value) =>
      value.startsWith('geo:') || _isAllowedNetworkUrl(value);

  static bool _isAllowedNetworkUrl(String value) {
    final uri = Uri.tryParse(value);
    if (uri == null || uri.scheme.toLowerCase() != 'https') return false;
    final host = uri.host.toLowerCase();
    if (host.isEmpty) return false;
    return _allowedMapHosts.contains(host);
  }

  static String _resolvedRedirectUrl(String base, String location) {
    if (location.toLowerCase().startsWith('http')) return location;
    return Uri.parse(base).resolve(location).toString();
  }

  static String? _hostOf(String value) => Uri.tryParse(value)?.host.toLowerCase();

  static const _trailingPunctuation = {'.', ',', ';', '!', '?', ')', ']', '"', "'"};

  /// Strips trailing sentence punctuation that often clings to a shared link
  /// ("...goo.gl/abc." / "(...)") so the redirect/resolve doesn't 404.
  static String _trimTrailingPunctuation(String v) {
    var end = v.length;
    while (end > 0 && _trailingPunctuation.contains(v[end - 1])) {
      end--;
    }
    return v.substring(0, end);
  }

  static void _log(String message) => developer.log(message, name: 'LocationParser');
  static void _warn(String message) => developer.log(message, name: 'LocationParser', level: 900);
}

extension _Let<T> on T {
  R let<R>(R Function(T) block) => block(this);
}
