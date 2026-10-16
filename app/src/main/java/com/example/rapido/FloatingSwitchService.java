package com.example.rapido;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Pure Background Keep-Alive Service
 * Keeps CPU active 24/7 without showing any floating overlay buttons on screen.
 */
public class FloatingSwitchService extends Service {

    private static final String CHANNEL_ID = "rapido_fg_channel";
    private static final int NOTIFICATION_ID = 1001;

    private SharedPreferences storage;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);

        // Keep CPU awake in background so video watching never sleeps order scanner
        acquireWakeLock();

        // Start Foreground Service with persistent status notification
        startForegroundServiceWithNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundServiceWithNotification();
        return START_STICKY;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RapidoAutoClicker:WakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12 * 60 * 60 * 1000L); // 12 hours
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception ignored) {}
    }

    private void startForegroundServiceWithNotification() {
        try {
            boolean isRunning = storage != null && storage.getBoolean("isRunning", false);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Rapido Background Monitor",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Background active order monitoring while watching videos");
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            int iconRes = getApplicationInfo().icon;
            if (iconRes == 0) iconRes = android.R.drawable.ic_dialog_info;

            Intent openApp = new Intent(this, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    openApp,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );

            Notification notification = builder
                    .setSmallIcon(iconRes)
                    .setContentTitle("⚡ Nura Auto Clicker: " + (isRunning ? "ONLINE (ஆர்டர் ஸ்கேனிங் ஆன்)" : "OFFLINE"))
                    .setContentText(isRunning ? "வீடியோ பார்க்கும் போதும் பின்னணியில் ஆர்டர்களை நொடியில் ஏற்கும்" : "ஆப்பில் ஸ்டார்ட் செய்யவும்")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
    }
}
