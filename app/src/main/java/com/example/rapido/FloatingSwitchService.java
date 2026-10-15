package com.example.rapido;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingSwitchService extends Service {

    private WindowManager windowManager;
    private TextView floatingView;
    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createFloatingSwitch();
    }

    private void createFloatingSwitch() {
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

        boolean isEnabled = prefs.getBoolean("is_auto_accept_on", true);

        floatingView = new TextView(this);
        floatingView.setText(isEnabled ? "⚡ RAPIDO: ON" : "⏸ RAPIDO: OFF");
        floatingView.setBackgroundColor(isEnabled ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
        floatingView.setTextColor(Color.WHITE);
        floatingView.setTextSize(15);
        floatingView.setPadding(36, 24, 36, 24);

        floatingView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean current = prefs.getBoolean("is_auto_accept_on", true);
                boolean next = !current;
                prefs.edit().putBoolean("is_auto_accept_on", next).apply();
                floatingView.setText(next ? "⚡ RAPIDO: ON" : "⏸ RAPIDO: OFF");
                floatingView.setBackgroundColor(next ? Color.parseColor("#10B981") : Color.parseColor("#EF4444"));
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
                            windowManager.updateViewLayout(floatingView, params);
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
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
    }
}
