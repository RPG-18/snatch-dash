import 'vehicle_profile.dart';

/// A single fuel fill-up. Mileage (km/l) is derived from the odometer gap to
/// the prior fill. Ported from `data/GarageModels.kt`.
class FuelFillup {
  const FuelFillup({
    this.id,
    required this.dateMs,
    required this.litres,
    required this.cost,
    required this.odometerKm,
    this.location = '',
    this.vehicleId = VehicleStore.defaultVehicleId,
  });

  final int? id;
  final int dateMs;
  final double litres;
  final double cost;
  final int odometerKm;
  final String location;
  final String vehicleId;

  FuelFillup copyWith({int? id}) => FuelFillup(
        id: id ?? this.id,
        dateMs: dateMs,
        litres: litres,
        cost: cost,
        odometerKm: odometerKm,
        location: location,
        vehicleId: vehicleId,
      );
}

/// A manually logged expense for the bike or ride.
class Expense {
  const Expense({
    this.id,
    required this.dateMs,
    required this.category,
    required this.amount,
    this.note = '',
    this.vehicleId = VehicleStore.defaultVehicleId,
  });

  final int? id;
  final int dateMs;
  final String category;
  final double amount;
  final String note;
  final String vehicleId;

  Expense copyWith({int? id}) => Expense(
        id: id ?? this.id,
        dateMs: dateMs,
        category: category,
        amount: amount,
        note: note,
        vehicleId: vehicleId,
      );
}

/// One recorded ride = one connect→disconnect session with the dash.
class Ride {
  const Ride({
    this.id,
    required this.startMs,
    required this.endMs,
    required this.distanceMeters,
    required this.durationSec,
    required this.avgSpeedMps,
    required this.maxSpeedMps,
    this.trackPolyline = '',
    this.startLat = 0,
    this.startLng = 0,
    this.endLat = 0,
    this.endLng = 0,
  });

  final int? id;
  final int startMs;
  final int endMs;
  final double distanceMeters;
  final int durationSec;
  final double avgSpeedMps;
  final double maxSpeedMps;
  final String trackPolyline;
  final double startLat;
  final double startLng;
  final double endLat;
  final double endLng;

  double get avgSpeedKmh => avgSpeedMps * 3.6;
  double get maxSpeedKmh => maxSpeedMps * 3.6;
  double get distanceKm => distanceMeters / 1000.0;
}

/// A recurring maintenance item with its interval and when it was last serviced.
class MaintenanceItem {
  const MaintenanceItem({
    this.id,
    required this.name,
    required this.iconKey, // "chain" | "drop" | "wrench" | "gauge" | "thermo" | "fuel"
    required this.intervalKm,
    required this.lastDoneOdoKm,
    required this.lastDoneDateMs,
    this.vehicleId = VehicleStore.defaultVehicleId,
  });

  final int? id;
  final String name;
  final String iconKey;
  final int intervalKm;
  final int lastDoneOdoKm;
  final int lastDoneDateMs;
  final String vehicleId;

  MaintenanceItem copyWith({
    int? id,
    int? lastDoneOdoKm,
    int? lastDoneDateMs,
    int? intervalKm,
  }) =>
      MaintenanceItem(
        id: id ?? this.id,
        name: name,
        iconKey: iconKey,
        intervalKm: intervalKm ?? this.intervalKm,
        lastDoneOdoKm: lastDoneOdoKm ?? this.lastDoneOdoKm,
        lastDoneDateMs: lastDoneDateMs ?? this.lastDoneDateMs,
        vehicleId: vehicleId,
      );
}

enum MaintenanceAction {
  replace('Replacement interval (km)', 'Replace'),
  inspect('Inspection interval (km)', 'Inspect'),
  maintain('Maintenance interval (km)', 'Maintain');

  const MaintenanceAction(this.intervalLabel, this.verb);
  final String intervalLabel;
  final String verb;
}

class OfficialMaintenanceSchedule {
  const OfficialMaintenanceSchedule({
    required this.action,
    required this.intervalKm,
    this.intervalMonths,
    required this.guidance,
    required this.manualPages,
  });

  final MaintenanceAction action;
  final int intervalKm;
  final int? intervalMonths;
  final String guidance;
  final String manualPages;
}

/// Official UK Himalayan 450 owner's manual, Periodical Maintenance, printed pp. 122-127.
class Himalayan450MaintenanceSchedule {
  Himalayan450MaintenanceSchedule._();

  static const _sourcePages = "Owner's Manual, Periodical Maintenance, pp. 122-127";

  static OfficialMaintenanceSchedule? forItem(MaintenanceItem item) {
    if (item.vehicleId != VehicleStore.defaultVehicleId) return null;
    switch (item.name.trim().toLowerCase()) {
      case 'engine oil':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.replace,
          intervalKm: 10000,
          intervalMonths: 12,
          guidance:
              'Replace every 10,000 km or 12 months after the initial 500 km service. Check the oil level every 1,000 km.',
          manualPages: _sourcePages,
        );
      case 'oil filter':
      case 'engine oil filter':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.replace,
          intervalKm: 10000,
          intervalMonths: 12,
          guidance: 'Replace every 10,000 km or 12 months after the initial 500 km service.',
          manualPages: _sourcePages,
        );
      case 'air filter':
      case 'air filter element':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.replace,
          intervalKm: 10000,
          intervalMonths: 12,
          guidance:
              'Replace every 10,000 km or 12 months. Clean or replace more frequently in dusty conditions.',
          manualPages: _sourcePages,
        );
      case 'brake pads - front':
      case 'brake pads - rear':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.inspect,
          intervalKm: 10000,
          intervalMonths: 12,
          guidance:
              'Inspect at each scheduled service and replace if necessary; the manual specifies no fixed replacement distance.',
          manualPages: _sourcePages,
        );
      case 'front tyre':
      case 'rear tyre':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.inspect,
          intervalKm: 10000,
          intervalMonths: 12,
          guidance:
              'Inspect tyre wear at each scheduled service and replace if necessary; the manual specifies no fixed replacement distance.',
          manualPages: _sourcePages,
        );
      case 'chain sprocket':
      case 'drive chain':
      case 'rear wheel drive chain':
        return const OfficialMaintenanceSchedule(
          action: MaintenanceAction.maintain,
          intervalKm: 500,
          intervalMonths: null,
          guidance:
              'Clean, lubricate, and adjust every 500 km or earlier, and after wet, dusty, or muddy riding. Replace worn parts as necessary.',
          manualPages: _sourcePages,
        );
      default:
        return null;
    }
  }
}
