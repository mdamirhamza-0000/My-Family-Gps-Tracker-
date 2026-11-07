package com.myfamily.sim_subs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aane wale SMS ko seedha native me handle karta hai (manifest receiver, isliye
 * app band ho ya service mari ho tab bhi chalta hai; Flutter/Dart ki zaroorat nahi).
 *
 *  - "[FGT:REQ]"            (dusre member ne location maangi) -> agar yahan
 *    sharing ON hai aur bhejne wala family registry ka number hai, apni aakhri
 *    GPS location "[FGT:LOC:lat,lng] ..." SMS se wapas bhej do.
 *  - "[FGT:LOC:lat,lng]"    (humari maangi hui location aayi) -> agar humne pichhle
 *    15 minute me request ki thi, Google Maps khol do (+ notification backup).
 *
 * Data Flutter ke SharedPreferences se (prefix "flutter."):
 *   sharing_on, sms_contacts_cache, last_fix, sms_pending_until, sms_last_reply, sms_log
 */
public final class SmsRequestReceiver extends BroadcastReceiver {
    private static final String PREFS = "FlutterSharedPreferences";
    private static final String REQ_TAG = "[FGT:REQ]";
    private static final Pattern LOC_RE =
            Pattern.compile("\\[FGT:LOC:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)\\]");
    private static final long REPLY_GAP_MS = 120_000;
    private static final String CHANNEL_ID = "fgt_sms_location";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }
        try {
            handle(context, intent);
        } catch (Exception e) {
            // kuch bhi galat ho, SMS delivery me dakhal nahi dena.
        }
    }

    private static void handle(Context context, Intent intent) throws Exception {
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m != null && m.getMessageBody() != null) sb.append(m.getMessageBody());
        }
        String body = sb.toString();
        boolean isReq = body.contains(REQ_TAG);
        Matcher loc = LOC_RE.matcher(body);
        boolean isLoc = loc.find();
        if (!isReq && !isLoc) return; // hamara SMS nahi

        String from = last10(msgs[0].getOriginatingAddress());
        if (from.length() != 10) return;

        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!inFamily(sp, from)) {
            log(sp, from + " ka SMS ignore kiya (family registry me nahi)");
            return;
        }
        if (isReq) {
            answerRequest(context, sp, from);
        } else {
            useReply(context, sp, from,
                    Double.parseDouble(loc.group(1)), Double.parseDouble(loc.group(2)));
        }
    }

    /** B ka phone: request aayi -> apni aakhri GPS location SMS se wapas bhejo. */
    private static void answerRequest(Context context, SharedPreferences sp, String from) {
        if (!sp.getBoolean("flutter.sharing_on", false)) {
            log(sp, from + " ki request aayi, par aapki sharing OFF hai");
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences gate = context.getSharedPreferences("fgt_native", Context.MODE_PRIVATE);
        if (now - gate.getLong("reply_at_" + from, 0) < REPLY_GAP_MS) return; // dono SIM se aayi duplicate

        double[] fix = PowerEventSms.bestFix(context, sp); // {lat, lng, timeMs}
        if (fix == null) {
            log(sp, from + " ki request aayi, par GPS location nahi mili");
            return;
        }
        gate.edit().putLong("reply_at_" + from, now).apply();

        long ageMin = Math.max(0, (now - (long) fix[2]) / 60000);
        String lat = String.format(Locale.US, "%.6f", fix[0]);
        String lng = String.format(Locale.US, "%.6f", fix[1]);
        String msg = "[FGT:LOC:" + lat + "," + lng + "] Family GPS Tracker: meri GPS location "
                + "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng
                + (ageMin >= 2 ? " (" + ageMin + " min purani)" : "");

        int ok = 0;
        for (SmsManager m : PowerEventSms.activeSmsManagers(context)) {
            try {
                ArrayList<String> parts = m.divideMessage(msg);
                if (parts.size() > 1) {
                    m.sendMultipartTextMessage(from, null, parts, null, null);
                } else {
                    m.sendTextMessage(from, null, msg, null, null);
                }
                ok++;
            } catch (Exception e) {
                // ye SIM fail, agla try karo
            }
        }
        log(sp, ok > 0 ? from + " ko aapki location SMS se bheji"
                : from + " ko location SMS nahi ja paya");
    }

    /** A ka phone: jawab aaya -> Google Maps kholo. */
    private static void useReply(Context context, SharedPreferences sp, String from,
                                 double lat, double lng) throws Exception {
        long now = System.currentTimeMillis();
        long until = sp.getLong("flutter.sms_pending_until", 0);
        if (now > until) {
            // Dono SIM se bheji request ke do jawab aa sakte hain: dusra chupchaap chhod do.
            try {
                JSONObject r = new JSONObject(sp.getString("flutter.sms_last_reply", "{}"));
                if (from.equals(r.optString("phone")) && now - r.optLong("t", 0) < 120_000) return;
            } catch (Exception ignored) {
            }
            log(sp, from + " ka location SMS aaya, par aapne request nahi ki thi");
            return;
        }
        JSONObject reply = new JSONObject();
        reply.put("phone", from);
        reply.put("lat", lat);
        reply.put("lng", lng);
        reply.put("t", now);
        sp.edit()
                .putLong("flutter.sms_pending_until", 0)
                .putString("flutter.sms_last_reply", reply.toString())
                .apply();
        log(sp, from + " ki location SMS se mili — Google Maps khul raha hai");
        openMaps(context, lat, lng);
    }

    /** Maps seedha kholo (background se tab khulta hai jab "Display over other apps" ON ho)
     *  aur backup ke liye notification bhi do: dabane par Maps khulega. */
    private static void openMaps(Context context, double lat, double lng) {
        String geo = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng;
        Intent maps = new Intent(Intent.ACTION_VIEW, Uri.parse(geo));
        maps.setPackage("com.google.android.apps.maps");
        maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean started = false;
        try {
            context.startActivity(maps);
            started = true;
        } catch (Exception e) {
            // Google Maps app nahi mila -> browser link
        }
        Intent target = maps;
        if (!started) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(web);
            } catch (Exception e) {
                // kuch nahi
            }
            target = web;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "SMS se mili location", NotificationManager.IMPORTANCE_HIGH));
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(context, 4711, target, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            b.setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("Family GPS Tracker")
                    .setContentText("SMS se location mili - Google Maps kholne ke liye dabao")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify(4711, b.build());
        } catch (Exception e) {
            // notification permission nahi etc.
        }
    }

    private static boolean inFamily(SharedPreferences sp, String from) {
        try {
            JSONArray arr = new JSONArray(sp.getString("flutter.sms_contacts_cache", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (from.equals(last10(arr.optString(i, "")))) return true;
            }
        } catch (Exception e) {
            // cache kharab -> family nahi maano
        }
        return false;
    }

    /** Sirf digits, aakhri 10 (taaki "+91 98765 43210" aur "9876543210" match ho). */
    static String last10(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** App ke "Recent SMS activity" log me line jodo (sabse naya upar, max 10). */
    static void log(SharedPreferences sp, String text) {
        try {
            JSONArray old = new JSONArray(sp.getString("flutter.sms_log", "[]"));
            JSONArray out = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("m", text);
            out.put(o);
            for (int i = 0; i < Math.min(old.length(), 9); i++) out.put(old.get(i));
            sp.edit().putString("flutter.sms_log", out.toString()).apply();
        } catch (Exception e) {
            // log na ho to koi baat nahi
        }
    }
}
