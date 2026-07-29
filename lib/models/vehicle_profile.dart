class VehicleProfile {
  const VehicleProfile({
    required this.id,
    required this.title,
    required this.nickname,
    required this.puc,
    required this.insurance,
    required this.service,
  });

  final String id;
  final String title;
  final String nickname;
  final String puc;
  final String insurance;
  final String service;

  VehicleProfile copyWith({
    String? title,
    String? nickname,
    String? puc,
    String? insurance,
    String? service,
  }) =>
      VehicleProfile(
        id: id,
        title: title ?? this.title,
        nickname: nickname ?? this.nickname,
        puc: puc ?? this.puc,
        insurance: insurance ?? this.insurance,
        service: service ?? this.service,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'nickname': nickname,
        'puc': puc,
        'insurance': insurance,
        'service': service,
      };

  factory VehicleProfile.fromJson(Map<String, dynamic> json) => VehicleProfile(
        id: json['id'] as String,
        title: json['title'] as String? ?? 'Motorcycle',
        nickname: json['nickname'] as String? ?? '',
        puc: json['puc'] as String? ?? 'Not set',
        insurance: json['insurance'] as String? ?? 'Not set',
        service: json['service'] as String? ?? 'Not set',
      );
}

/// Namespaced constant, mirroring the original `VehicleStore.DEFAULT_VEHICLE_ID`
/// — kept as a plain holder class so `garage_models.dart` doesn't need to
/// import the Riverpod-aware store.
class VehicleStore {
  VehicleStore._();
  static const defaultVehicleId = 'default';
}
