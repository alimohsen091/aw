package com.awdesigns.cashierprint;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText siteUrlEt, tokenEt, printerIpEt, printerPortEt;
    private TextView statusTv, logTv;
    private SharedPreferences prefs;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLog();
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = AppConfig.prefs(this);
        buildUi();
        loadSettings();
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(AppLog.ACTION_LOG_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        refreshLog();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(logReceiver); } catch (Exception ignored) {}
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setGravity(Gravity.RIGHT);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AW Print");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.RIGHT);
        root.addView(title, lp(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("إعدادات كاشير الطباعة");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(100, 100, 100));
        subtitle.setGravity(Gravity.RIGHT);
        subtitle.setPadding(0, dp(3), 0, dp(14));
        root.addView(subtitle, lp(-1, -2));

        statusTv = new TextView(this);
        statusTv.setTextSize(16);
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        statusTv.setGravity(Gravity.RIGHT);
        statusTv.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusTv, lp(-1, -2));

        siteUrlEt = input("رابط الموقع", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tokenEt = input("رمز التطبيق Token", InputType.TYPE_CLASS_TEXT);
        printerIpEt = input("IP الطابعة", InputType.TYPE_CLASS_TEXT);
        printerPortEt = input("Port الطابعة", InputType.TYPE_CLASS_NUMBER);
        root.addView(siteUrlEt);
        root.addView(tokenEt);
        root.addView(printerIpEt);
        root.addView(printerPortEt);

        Button saveBtn = btn("حفظ الإعدادات");
        Button startBtn = btn("تشغيل الطباعة");
        Button stopBtn = btn("إيقاف الطباعة");
        Button testBtn = btn("اختبار الطابعة");
        Button pollBtn = btn("فحص طلبات الآن");
        Button clearLogBtn = btn("مسح السجل");

        root.addView(saveBtn);
        root.addView(startBtn);
        root.addView(stopBtn);
        root.addView(testBtn);
        root.addView(pollBtn);
        root.addView(clearLogBtn);

        logTv = new TextView(this);
        logTv.setTextSize(13);
        logTv.setTextColor(Color.rgb(70, 70, 70));
        logTv.setGravity(Gravity.RIGHT);
        logTv.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(logTv, lp(-1, -2));

        setContentView(scroll);

        saveBtn.setOnClickListener(v -> saveSettings(true));
        startBtn.setOnClickListener(v -> startPrintService());
        stopBtn.setOnClickListener(v -> stopPrintService());
        testBtn.setOnClickListener(v -> new Thread(() -> PrinterCore.printTest(this)).start());
        pollBtn.setOnClickListener(v -> pollNow());
        clearLogBtn.setOnClickListener(v -> AppLog.clear(this));
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setGravity(Gravity.LEFT);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setTextSize(15);
        return e;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadSettings() {
        siteUrlEt.setText(AppConfig.get(prefs, AppConfig.KEY_SITE, AppConfig.DEFAULT_SITE));
        tokenEt.setText(AppConfig.get(prefs, AppConfig.KEY_TOKEN, AppConfig.DEFAULT_TOKEN));
        printerIpEt.setText(AppConfig.get(prefs, AppConfig.KEY_IP, AppConfig.DEFAULT_PRINTER_IP));
        printerPortEt.setText(AppConfig.get(prefs, AppConfig.KEY_PORT, AppConfig.DEFAULT_PRINTER_PORT));
        refreshStatus();
        refreshLog();
    }

    private void saveSettings(boolean showToast) {
        prefs.edit()
                .putString(AppConfig.KEY_SITE, clean(siteUrlEt.getText().toString()))
                .putString(AppConfig.KEY_TOKEN, clean(tokenEt.getText().toString()))
                .putString(AppConfig.KEY_IP, clean(printerIpEt.getText().toString()))
                .putString(AppConfig.KEY_PORT, clean(printerPortEt.getText().toString()))
                .putString(AppConfig.KEY_INTERVAL, AppConfig.DEFAULT_INTERVAL)
                .putString(AppConfig.KEY_WIDTH, AppConfig.DEFAULT_PAPER_WIDTH)
                .apply();
        AppLog.add(this, "تم حفظ الإعدادات");
        if (showToast) Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show();
    }

    private String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private void startPrintService() {
        saveSettings(false);
        requestNotificationPermission();
        Intent service = new Intent(this, PrintService.class);
        service.setAction(PrintService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        refreshStatus();
        Toast.makeText(this, "تم التشغيل", Toast.LENGTH_SHORT).show();
    }

    private void stopPrintService() {
        Intent service = new Intent(this, PrintService.class);
        service.setAction(PrintService.ACTION_STOP);
        startService(service);
        refreshStatus();
        Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show();
    }

    private void pollNow() {
        saveSettings(false);
        Intent service = new Intent(this, PrintService.class);
        service.setAction(PrintService.ACTION_POLL_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void refreshStatus() {
        boolean enabled = prefs.getBoolean(AppConfig.KEY_SERVICE_ENABLED, false);
        statusTv.setText(enabled ? "الحالة: يعمل" : "الحالة: متوقف");
        statusTv.setTextColor(enabled ? Color.rgb(30, 125, 70) : Color.rgb(150, 70, 70));
    }

    private void refreshLog() {
        if (logTv != null) logTv.setText(AppLog.read(this));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2026);
            }
        }
    }

}
