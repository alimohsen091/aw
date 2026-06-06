package com.awdesigns.cashierprint;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrintService extends Service {
    public static final String ACTION_START = "com.awdesigns.cashierprint.START";
    public static final String ACTION_STOP = "com.awdesigns.cashierprint.STOP";
    public static final String ACTION_POLL_NOW = "com.awdesigns.cashierprint.POLL_NOW";
    public static final String CHANNEL_ID = "aw_print_service_channel";
    private static final int NOTIFICATION_ID = 2026;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean(false);
    private PowerManager.WakeLock serviceWakeLock;
    private WifiManager.WifiLock serviceWifiLock;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            runOneCycle(false);
            int seconds = AppConfig.FIXED_POLL_INTERVAL_SECONDS;
            handler.postDelayed(this, Math.max(5, seconds) * 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopServiceWork();
            return START_NOT_STICKY;
        }

        startInForeground();
        acquireServiceLocks();
        AppConfig.prefs(this).edit().putBoolean(AppConfig.KEY_SERVICE_ENABLED, true).apply();

        if (ACTION_POLL_NOW.equals(action)) {
            runOneCycle(true);
            handler.removeCallbacks(loop);
            int seconds = AppConfig.FIXED_POLL_INTERVAL_SECONDS;
            handler.postDelayed(loop, Math.max(5, seconds) * 1000L);
        } else {
            handler.removeCallbacks(loop);
            handler.post(loop);
            AppLog.add(this, "تم تشغيل الطباعة");
        }

        return START_STICKY;
    }

    private void startInForeground() {
        Notification notification = buildNotification("جاهز لاستقبال الطلبات");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent stopIntent = new Intent(this, PrintService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("AW Print")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(0xFFC7921E);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AW Print",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("خدمة كاشير الطباعة");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }


    private void acquireServiceLocks() {
        try {
            if (serviceWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AWPrint:ServiceLock");
                    serviceWakeLock.setReferenceCounted(false);
                }
            }
            if (serviceWakeLock != null && !serviceWakeLock.isHeld()) {
                serviceWakeLock.acquire();
            }
        } catch (Exception ignored) {}

        try {
            if (serviceWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    serviceWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AWPrint:WifiLock");
                    serviceWifiLock.setReferenceCounted(false);
                }
            }
            if (serviceWifiLock != null && !serviceWifiLock.isHeld()) {
                serviceWifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseServiceLocks() {
        try {
            if (serviceWifiLock != null && serviceWifiLock.isHeld()) serviceWifiLock.release();
        } catch (Exception ignored) {}
        try {
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) serviceWakeLock.release();
        } catch (Exception ignored) {}
    }

    private void runOneCycle(boolean manual) {
        if (!working.compareAndSet(false, true)) {
            if (manual) AppLog.add(this, "الفحص يعمل الآن، انتظر قليلاً");
            return;
        }

        executor.execute(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AWPrint:PrintCycle");
                    wakeLock.acquire(45000L);
                }
                PrinterCore.pollAndPrint(this);
            } catch (Exception ex) {
                AppLog.add(this, "خطأ خدمة الطباعة: " + ex.getMessage());
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
                working.set(false);
            }
        });
    }

    private void stopServiceWork() {
        SharedPreferences prefs = AppConfig.prefs(this);
        prefs.edit().putBoolean(AppConfig.KEY_SERVICE_ENABLED, false).apply();
        handler.removeCallbacksAndMessages(null);
        AppLog.add(this, "تم إيقاف الطباعة");
        releaseServiceLocks();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseServiceLocks();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
