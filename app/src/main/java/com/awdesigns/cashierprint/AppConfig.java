package com.awdesigns.cashierprint;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {
    public static final String PREFS = "aw_cashier_print_settings";

    public static final String DEFAULT_SITE = "https://cornflowerblue-falcon-944374.hostingersite.com";
    public static final String DEFAULT_TOKEN = "lp_TFo2Vyy0V2B74xVje1284vPZPRGCz88Gr38M1QWs";
    public static final String DEFAULT_PRINTER_IP = "192.168.3.144";
    public static final String DEFAULT_PRINTER_PORT = "9100";
    public static final String DEFAULT_INTERVAL = "10";
    public static final String DEFAULT_PAPER_WIDTH = "384";
    public static final int FIXED_POLL_INTERVAL_SECONDS = 10;
    public static final int FIXED_PAPER_WIDTH = 384;

    public static final String KEY_SITE = "site_url";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_IP = "printer_ip";
    public static final String KEY_PORT = "printer_port";
    public static final String KEY_INTERVAL = "interval";
    public static final String KEY_WIDTH = "paper_width";
    public static final String KEY_SERVICE_ENABLED = "service_enabled";

    private AppConfig() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(SharedPreferences prefs, String key, String fallback) {
        String value = prefs.getString(key, fallback);
        if (value == null) return fallback;
        value = value.trim();
        return value.isEmpty() ? fallback : value;
    }

    public static int getInt(SharedPreferences prefs, String key, String fallback, int def) {
        try {
            return Integer.parseInt(get(prefs, key, fallback).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static String trimSlash(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("/+$", "");
    }
}
