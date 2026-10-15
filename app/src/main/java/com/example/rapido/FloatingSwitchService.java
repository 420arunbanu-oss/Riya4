package com.example.rapido;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingSwitchService extends Service {

    private static final String CHANNEL_ID = "rapido_fg_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private TextView floatingView;
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
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Keep CPU awake in background so video watching never sleeps order scanner
        acquireWakeLock();

        // Start Foreground Service with persistent status notification
        startForegroundServiceWithNotification();

        // Create draggable floating bubble overlay
        createFloatingSwitch();
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
                    .setContentTitle("⚡ Nura Auto Clicker: " + (isRunning ? "ONLINE (இயங்குகிறது)" : "OFFLINE (நிறுத்தப்பட்டது)"))
                    .setContentText(isRunning ? "வீடியோ பார்க்கும் போதும் பின்னணியில் ஆர்டர்களை ஏற்கும்" : "மிதக்கும் சுவிட்சைத் தொட்டு ஆன் செய்யவும்")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {}
    }

    private void createFloatingSwitch() {
        if (floatingView != null) return;

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 250;

        boolean isRunning = storage != null && storage.getBoolean("isRunning", false);

        floatingView = new TextView(this);
        floatingView.setText(isRunning ? "⚡ RAPIDO: ON" : "⏸ RAPIDO: OFF");
        floatingView.setBackgroundColor(isRunning ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
        floatingView.setTextColor(Color.WHITE);
        floatingView.setTextSize(15);
        floatingView.setPadding(36, 24, 36, 24);

        floatingView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean cur = storage.getBoolean("isRunning", false);
                boolean next = !cur;
                storage.edit().putBoolean("isRunning", next).apply();
                RapidoAutoClickService.isAutoAcceptOn = next;

                floatingView.setText(next ? "⚡ RAPIDO: ON" : "⏸ RAPIDO: OFF");
                floatingView.setBackgroundColor(next ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
                startForegroundServiceWithNotification();
                MainActivity.syncStaticUI();
            }
        });

        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        if (windowManager != null && floatingView != null) {
                            try {
                                windowManager.updateViewLayout(floatingView, params);
                            } catch (Exception ignored) {}
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            if (windowManager != null) {
                windowManager.addView(floatingView, params);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
    }
}
