import 'package:flutter/services.dart';

/// Phone me jitni SIM active hain unke subscription ids (dual SIM me 2).
/// READ_PHONE_STATE permission na ho ya kuch bhi galat ho to khali list aati hai.
class SimSubs {
  static const MethodChannel _channel = MethodChannel('sim_subs');

  static Future<List<int>> activeSubscriptionIds() async {
    try {
      final r = await _channel.invokeMethod<List<dynamic>>('getSubscriptionIds');
      return (r ?? const <dynamic>[]).map((e) => (e as num).toInt()).toList();
    } catch (_) {
      return <int>[];
    }
  }
}
