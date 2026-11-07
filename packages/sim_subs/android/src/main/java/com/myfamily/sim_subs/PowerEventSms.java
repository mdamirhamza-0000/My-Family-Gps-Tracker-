package com.myfamily.sim_subs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone band hone (switch off / restart) ya Airplane mode ON hone par family ke
 * numbers ko aakhri GPS location SMS se bhejta hai.
 *
 * Sab kuch native hai (Dart nahi), kyunki band hone se pehle sirf kuch second
 * milte hain. Data Flutter ke SharedPreferences se padhta hai:
 *   flutter.sharing_on         - sharing ON hai ya nahi
 *   flutter.sms_contacts_cache - family ke numbers (JSON list)
 *   flutter.last_fix           - "lat,lng,epochMs" (service har fix par likhti hai)
 *
 * Receiver process me sirf ek baar register hota hai (static guard), warna
 * har Flutter engine par ek SMS aur jata.
 */
public final class PowerEventSms extends BroadcastReceiver {
    private static final String PREFS = "FlutterSharedPreferences";
    private static final long MIN_GAP_MS = 60_000;

    private static boolean registered = false;
    private static long lastSentAt = 0;

    public static synchronized void register(Context appContext) {
        if (registered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SHUTDOWN);
            f.addAction("android.intent.action.QUICKBOOT_POWEROFF");
            f.addAction("com.htc.intent.action.QUICKBOOT_POWEROFF");
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            PowerEventSms r = new PowerEventSms();
            if (Build.VERSION.SDK_INT >= 33) {
                // NOT_EXPORTED: koi aur app nakli "shutdown" bhej kar SMS nahi karwa sakta.
                appContext.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(r, f);
            }
            registered = true;
        } catch (Exception e) {
            // register nahi hua to bas ye feature band rahega.
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        String reason;
        if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
            if (!intent.getBooleanExtra("state", false)) return; // OFF hua, kuch nahi karna
            reason = "phone Airplane mode me ja raha hai";
        } else {
            reason = "phone band (switch off / restart) ho raha hai";
        }
        try {
            send(context, reason);
        } catch (Exception e) {
            // kuch bhi galat ho, shutdown rokna nahi hai.
        }
    }

    private static synchronized void send(Context context, String reason) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastSentAt < MIN_GAP_MS) return;

        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("flutter.sharing_on", false)) return;

        List<String> numbers = new ArrayList<>();
        JSONArray arr = new JSONArray(sp.getString("flutter.sms_contacts_cache", "[]"));
        for (int i = 0; i < arr.length(); i++) {
            String n = arr.optString(i, "");
            if (!n.isEmpty()) numbers.add(n);
        }
        if (numbers.isEmpty()) return;

        double[] fix = bestFix(context, sp); // {lat, lng, timeMs}
        if (fix == null) return;

        long ageMin = Math.max(0, (now - (long) fix[2]) / 60000);
        String link = String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", fix[0], fix[1]);
        String msg = "Family GPS Tracker: " + reason + ". Aakhri GPS location"
                + (ageMin >= 2 ? " (" + ageMin + " min purani)" : "") + ": " + link;

        List<SmsManager> managers = activeSmsManagers(context);
        lastSentAt = now;
        for (String num : numbers) {
            for (SmsManager m : managers) {
                try {
                    ArrayList<String> parts = m.divideMessage(msg);
                    if (parts.size() > 1) {
                        m.sendMultipartTextMessage(num, null, parts, null, null);
                    } else {
                        m.sendTextMessage(num, null, msg, null, null);
                    }
                } catch (Exception e) {
                    // ye SIM / number fail hua, agla try karo.
                }
            }
        }
    }

    /** Dual SIM me dono SIM ke SmsManager; na mile to default. */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static List<SmsManager> activeSmsManagers(Context context) {
        List<SmsManager> managers = new ArrayList<>();
        try {
            SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> list = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (list != null) {
                for (SubscriptionInfo info : list) {
                    managers.add(SmsManager.getSmsManagerForSubscriptionId(info.getSubscriptionId()));
                }
            }
        } catch (Exception e) {
            // READ_PHONE_STATE nahi -> default SIM.
        }
        if (managers.isEmpty()) managers.add(SmsManager.getDefault());
        return managers;
    }

    /** Service ka saved fix aur phone ki last-known location me se jo naya ho. */
    @SuppressWarnings("MissingPermission")
    static double[] bestFix(Context context, SharedPreferences sp) {
        double[] best = null;
        try {
            String raw = sp.getString("flutter.last_fix", null);
            if (raw != null) {
                String[] p = raw.split(",");
                if (p.length == 3) {
                    best = new double[]{
                            Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
                }
            }
        } catch (Exception e) {
            best = null;
        }
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                String[] providers = {
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER};
                for (String prov : providers) {
                    try {
                        Location l = lm.getLastKnownLocation(prov);
                        if (l != null && (best == null || l.getTime() > (long) best[2])) {
                            best = new double[]{l.getLatitude(), l.getLongitude(), (double) l.getTime()};
                        }
                    } catch (Exception e) {
                        // is provider se nahi mila
                    }
                }
            }
        } catch (Exception e) {
            // location permission nahi etc.
        }
        return best;
    }
}
