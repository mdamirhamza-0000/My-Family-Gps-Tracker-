package com.myfamily.sim_subs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.List;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Returns the subscription ids of all active SIMs. */
public class SimSubsPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {
    private MethodChannel channel;
    private Context context;

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "sim_subs");
        channel.setMethodCallHandler(this);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        if (!"getSubscriptionIds".equals(call.method)) {
            result.notImplemented();
            return;
        }
        List<Integer> ids = new ArrayList<>();
        try {
            SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> list = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (list != null) {
                for (SubscriptionInfo info : list) {
                    ids.add(info.getSubscriptionId());
                }
            }
        } catch (Exception e) {
            // READ_PHONE_STATE nahi mila etc. -> khali list, app default SIM use karega.
        }
        result.success(ids);
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
    }
}
