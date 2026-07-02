import 'package:flutter/material.dart';

import '../models/ride.dart';
import 'api_client.dart';

class AppScope extends InheritedNotifier<AppState> {
  const AppScope({
    super.key,
    required AppState state,
    required super.child,
  }) : super(notifier: state);

  static AppState of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'AppScope is not available in this context');
    return scope!.notifier!;
  }
}

class AppState extends ChangeNotifier {
  final ApiClient api = ApiClient();

  bool initialized = false;
  bool loadingRides = false;
  String? error;
  List<Ride> rides = const [];

  bool get isLoggedIn => api.isLoggedIn;
  String? get userName => api.name;

  Future<void> init() async {
    await api.init();
    initialized = true;
    notifyListeners();
    await refreshRides();
  }

  Future<void> refreshRides({String? fromCity, String? toCity}) async {
    loadingRides = true;
    error = null;
    notifyListeners();
    try {
      rides = await api.getRides(fromCity: fromCity, toCity: toCity);
    } catch (e) {
      error = e.toString();
    } finally {
      loadingRides = false;
      notifyListeners();
    }
  }

  Future<String?> requestCode(String phone) => api.requestCode(phone);

  Future<void> verifyCode({
    required String phone,
    required String code,
    required String name,
  }) async {
    await api.verifyCode(phone: phone, code: code, name: name);
    notifyListeners();
  }

  Future<void> logout() async {
    await api.logout();
    notifyListeners();
  }

  Future<String> createRide({
    required String fromCity,
    required String toCity,
    required DateTime departAt,
    required int seats,
    required int price,
    String comment = '',
  }) async {
    final rideId = await api.createRide(
      fromCity: fromCity,
      toCity: toCity,
      departAt: departAt,
      seats: seats,
      price: price,
      comment: comment,
    );
    await refreshRides(fromCity: fromCity, toCity: toCity);
    return rideId;
  }

  Future<int> bookRide(Ride ride) => api.bookRide(ride.id);

  Future<List<BoostPlan>> getBoostPlans() => api.getBoostPlans();

  Future<BoostResult> createBoost({required String rideId, required String tier}) {
    return api.createBoost(rideId: rideId, tier: tier);
  }

  Future<void> sendSos({String category = 'other', String note = ''}) {
    return api.sendSos(category: category, note: note);
  }
}
