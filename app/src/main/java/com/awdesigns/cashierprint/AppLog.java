package com.awdesigns.cashierprint;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    public static final String ACTION_LOG_UPDATED = "com.awdesigns.cashierprint.LOG_UPDATED";
    public static final String KEY_LOG = "app_log";
    public static final int MAX_LOG_CHARS = 9000;

    private AppLog() {}

    public static void add(Context context, String message) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = AppConfig.prefs(app);
        String now = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String old = prefs.getString(KEY_LOG, "");
        String line = now + " - " + message + "\n";
        String merged = line + (old == null ? "" : old);
        if (merged.length() > MAX_LOG_CHARS) {
            merged = merged.substring(0, MAX_LOG_CHARS);
        }
        prefs.edit().putString(KEY_LOG, merged).apply();
        Intent intent = new Intent(ACTION_LOG_UPDATED);
        intent.setPackage(app.getPackageName());
        app.sendBroadcast(intent);
    }

    public static String read(Context context) {
        return AppConfig.prefs(context).getString(KEY_LOG, "السجل فارغ");
    }

    public static void clear(Context context) {
        AppConfig.prefs(context).edit().putString(KEY_LOG, "").apply();
        Intent intent = new Intent(ACTION_LOG_UPDATED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
