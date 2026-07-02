import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../models/ride.dart';

const _baseUrl = String.fromEnvironment(
  'YULDASH_API_BASE_URL',
  defaultValue: 'https://yulbash.ru',
);

class ApiClient {
  ApiClient()
      : _dio = Dio(
          BaseOptions(
            baseUrl: _baseUrl,
            connectTimeout: const Duration(seconds: 15),
            receiveTimeout: const Duration(seconds: 15),
            headers: {'Accept': 'application/json'},
          ),
        );

  final Dio _dio;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  String? _accessToken;
  String? _refreshToken;
  String? _name;

  bool get isLoggedIn => _accessToken != null && _accessToken!.isNotEmpty;
  String? get name => _name;

  Future<void> init() async {
    _accessToken = await _storage.read(key: 'access_token');
    _refreshToken = await _storage.read(key: 'refresh_token');
    _name = await _storage.read(key: 'name');
  }

  Future<void> logout() async {
    if (isLoggedIn) {
      try {
        await _request<Object?>('POST', '/auth/logout', auth: true);
      } catch (_) {
        // Local logout must still complete when the network is unavailable.
      }
    }
    _accessToken = null;
    _refreshToken = null;
    _name = null;
    await _storage.deleteAll();
  }

  Future<String?> requestCode(String phone) async {
    final data = await _request<Map<String, dynamic>>(
      'POST',
      '/auth/request-code',
      data: {'phone': phone},
    );
    return data['dev_code']?.toString();
  }

  Future<void> verifyCode({
    required String phone,
    required String code,
    required String name,
  }) async {
    final data = await _request<Map<String, dynamic>>(
      'POST',
      '/auth/verify',
      data: {'phone': phone, 'code': code, 'name': name},
    );
    await _saveAuth(data, fallbackName: name);
  }

  Future<List<Ride>> getRides({String? fromCity, String? toCity}) async {
    final params = <String, dynamic>{
      if (fromCity != null && fromCity.trim().isNotEmpty) 'from_city': fromCity.trim(),
      if (toCity != null && toCity.trim().isNotEmpty) 'to_city': toCity.trim(),
    };
    final data = await _request<List<dynamic>>('GET', '/rides', query: params);
    return data
        .whereType<Map>()
        .map((item) => Ride.fromJson(Map<String, dynamic>.from(item)))
        .toList();
  }

  Future<String> createRide({
    required String fromCity,
    required String toCity,
    required DateTime departAt,
    required int seats,
    required int price,
    String comment = '',
  }) async {
    final data = await _request<Map<String, dynamic>>(
      'POST',
      '/rides',
      auth: true,
      data: {
        'from_city': fromCity,
        'to_city': toCity,
        'depart_at': departAt.toUtc().toIso8601String(),
        'seats_total': seats,
        'price': price,
        'comment': comment,
      },
    );
    return '${data['id'] ?? ''}';
  }

  Future<int> bookRide(String rideId, {int seats = 1}) async {
    final data = await _request<Map<String, dynamic>>(
      'POST',
      '/bookings',
      auth: true,
      data: {'ride_id': int.parse(rideId), 'seats': seats},
    );
    return _asInt(data['id']);
  }

  Future<List<BoostPlan>> getBoostPlans() async {
    final data = await _request<List<dynamic>>('GET', '/boost/plans');
    return data
        .whereType<Map>()
        .map((item) => BoostPlan.fromJson(Map<String, dynamic>.from(item)))
        .toList();
  }

  Future<BoostResult> createBoost({required String rideId, required String tier}) async {
    final data = await _request<Map<String, dynamic>>(
      'POST',
      '/boost/create',
      auth: true,
      data: {'ride_id': int.parse(rideId), 'tier': tier},
    );
    return BoostResult.fromJson(data);
  }

  Future<void> sendSos({String category = 'other', String note = ''}) async {
    await _request<Map<String, dynamic>>(
      'POST',
      '/sos',
      auth: true,
      data: {
        'category': category,
        'note': note,
      },
    );
  }

  Future<T> _request<T>(
    String method,
    String path, {
    Object? data,
    Map<String, dynamic>? query,
    bool auth = false,
    bool retrying = false,
  }) async {
    try {
      final response = await _dio.request<Object?>(
        path,
        data: data,
        queryParameters: query,
        options: Options(
          method: method,
          headers: {
            if (auth && _accessToken != null) 'Authorization': 'Bearer $_accessToken',
            if (data != null) 'Content-Type': 'application/json',
          },
        ),
      );
      return response.data as T;
    } on DioException catch (error) {
      if (auth && !retrying && error.response?.statusCode == 401 && await _refresh()) {
        return _request<T>(
          method,
          path,
          data: data,
          query: query,
          auth: auth,
          retrying: true,
        );
      }
      throw ApiException.fromDio(error);
    }
  }

  Future<bool> _refresh() async {
    final token = _refreshToken;
    if (token == null || token.isEmpty) return false;
    try {
      final data = await _request<Map<String, dynamic>>(
        'POST',
        '/auth/refresh',
        data: {'refresh_token': token},
      );
      await _saveAuth(data);
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> _saveAuth(Map<String, dynamic> data, {String fallbackName = ''}) async {
    _accessToken = data['access_token']?.toString();
    _refreshToken = data['refresh_token']?.toString();
    final user = data['user'];
    if (user is Map && user['name'] != null) {
      _name = user['name'].toString();
    } else if (fallbackName.isNotEmpty) {
      _name = fallbackName;
    }
    if (_accessToken != null) await _storage.write(key: 'access_token', value: _accessToken);
    if (_refreshToken != null) await _storage.write(key: 'refresh_token', value: _refreshToken);
    if (_name != null) await _storage.write(key: 'name', value: _name);
  }
}

class ApiException implements Exception {
  ApiException(this.message, {this.statusCode});

  factory ApiException.fromDio(DioException error) {
    final response = error.response;
    final body = response?.data;
    var message = error.message ?? 'Ошибка сети';
    if (body is Map && body['detail'] != null) {
      message = body['detail'].toString();
    }
    return ApiException(message, statusCode: response?.statusCode);
  }

  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}

class BoostPlan {
  const BoostPlan({
    required this.tier,
    required this.title,
    required this.price,
    required this.hours,
  });

  factory BoostPlan.fromJson(Map<String, dynamic> json) => BoostPlan(
        tier: '${json['tier'] ?? ''}',
        title: '${json['title'] ?? ''}',
        price: _asInt(json['price']),
        hours: _asInt(json['hours']),
      );

  final String tier;
  final String title;
  final int price;
  final int hours;
}

class BoostResult {
  const BoostResult({
    required this.status,
    required this.method,
    required this.paymentId,
    required this.amount,
    this.confirmationUrl,
    this.payeePhone,
    this.payeeBank,
    this.payeeName,
  });

  factory BoostResult.fromJson(Map<String, dynamic> json) {
    final payee = json['payee'];
    final payeeMap = payee is Map ? payee : const {};
    return BoostResult(
      status: '${json['status'] ?? ''}',
      method: '${json['method'] ?? ''}',
      paymentId: _asInt(json['payment_id']),
      amount: _asInt(json['amount']),
      confirmationUrl: json['confirmation_url']?.toString(),
      payeePhone: payeeMap['phone']?.toString(),
      payeeBank: payeeMap['bank']?.toString(),
      payeeName: payeeMap['name']?.toString(),
    );
  }

  final String status;
  final String method;
  final int paymentId;
  final int amount;
  final String? confirmationUrl;
  final String? payeePhone;
  final String? payeeBank;
  final String? payeeName;
}

int _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse('$value') ?? 0;
}
