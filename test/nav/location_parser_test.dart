import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/util/location_parser.dart';

void main() {
  test('parse_acceptsHttpsKnownGoogleMapsHost', () {
    final location = LocationParser.parse('Meet here https://maps.google.com/?q=12.9716,77.5946');

    expect(location.name, 'Meet here');
    expect(location.lat, closeTo(12.9716, 0.000001));
    expect(location.lng, closeTo(77.5946, 0.000001));
    expect(location.needsExpansion, isFalse);
  });

  test('parse_acceptsGeoUriWithoutNetworkUrl', () {
    final location = LocationParser.parse('geo:12.9716,77.5946');

    expect(location.lat, closeTo(12.9716, 0.000001));
    expect(location.lng, closeTo(77.5946, 0.000001));
    expect(location.url, 'geo:12.9716,77.5946');
    expect(location.needsExpansion, isFalse);
  });

  test('parse_rejectsHttpMapUrlEvenWithCoordinates', () {
    final location = LocationParser.parse('http://maps.google.com/?q=12.9716,77.5946');

    expect(location.url, isNull);
    expect(location.lat, isNull);
    expect(location.lng, isNull);
    expect(location.needsExpansion, isFalse);
  });

  test('parse_rejectsUnknownHttpsHost', () {
    final location = LocationParser.parse('https://example.com/?q=12.9716,77.5946');

    expect(location.url, isNull);
    expect(location.lat, isNull);
    expect(location.lng, isNull);
    expect(location.needsExpansion, isFalse);
  });

  test('parse_acceptsKnownShortMapHostForExpansion', () {
    final location = LocationParser.parse('https://maps.app.goo.gl/abc123');

    expect(location.name, 'Loading…');
    expect(location.url, 'https://maps.app.goo.gl/abc123');
    expect(location.needsExpansion, isTrue);
  });
}
