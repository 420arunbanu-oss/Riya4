package com.example.rapido;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class RapidoAutoClickService extends AccessibilityService {

    public static boolean isServiceRunning = false;
    private SharedPreferences prefs;
    private long lastClickTime = 0L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE);
        isServiceRunning = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || prefs == null) return;

        boolean isEnabled = prefs.getBoolean("is_auto_accept_on", true);
        if (!isEnabled) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        long now = System.currentTimeMillis();
        if (now - lastClickTime < 600) return;

        // Exact scanning & accept engine from user's file:
        boolean accepted = handleRapidoOffer(rootNode);
        if (accepted) {
            lastClickTime = now;
            vibratePhone();
        }
    }

    private boolean handleRapidoOffer(AccessibilityNodeInfo root) {
        // 1. Search for Accept button ("Accept" or "ஏற்றுக்கொள்")
        List<AccessibilityNodeInfo> acceptButtons = root.findAccessibilityNodeInfosByText("Accept");
        if (acceptButtons == null || acceptButtons.isEmpty()) {
            acceptButtons = root.findAccessibilityNodeInfosByText("ஏற்றுக்கொள்");
        }
        if (acceptButtons == null || acceptButtons.isEmpty()) {
            acceptButtons = root.findAccessibilityNodeInfosByText("ACCEPT");
        }
        if (acceptButtons == null || acceptButtons.isEmpty()) {
            acceptButtons = root.findAccessibilityNodeInfosByText("Tap to accept");
        }

        if (acceptButtons != null && !acceptButtons.isEmpty()) {
            for (AccessibilityNodeInfo btn : acceptButtons) {
                if (btn != null) {
                    if (triggerAccept(btn)) {
                        return true;
                    }
                }
            }
        }

        // 2. Rapido Captain View IDs Search
        String[] viewIds = new String[] {
            "com.rapido.captain:id/btn_accept",
            "com.rapido.captain:id/accept_order_btn",
            "com.rapido.captain:id/btnAccept",
            "com.rapido.captain:id/accept_button",
            "com.rapido.captain:id/primary_action",
            "btn_accept"
        };
        for (String id : viewIds) {
            List<AccessibilityNodeInfo> idNodes = root.findAccessibilityNodeInfosByViewId(id);
            if (idNodes != null && !idNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : idNodes) {
                    if (node != null && triggerAccept(node)) {
                        return true;
                    }
                }
            }
        }

        // 3. Rupee ₹ Symbol active offer card search
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo rNode : rupeeNodes) {
                AccessibilityNodeInfo parent = rNode.getParent();
                int depth = 0;
                while (parent != null && depth < 4) {
                    Rect rect = new Rect();
                    parent.getBoundsInScreen(rect);
                    if (parent.isClickable() && rect.width() > 100) {
                        if (triggerAccept(parent)) {
                            return true;
                        }
                    }
                    parent = parent.getParent();
                    depth++;
                }
            }
        }

        return false;
    }

    private boolean triggerAccept(AccessibilityNodeInfo btn) {
        // Direct Click as in user's file
        boolean clicked = false;
        if (btn.isClickable() && btn.isEnabled()) {
            clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        if (!clicked) {
            AccessibilityNodeInfo parent = btn.getParent();
            if (parent != null && parent.isClickable()) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        // (X, Y) Bounds Millisecond Click
        Rect rect = new Rect();
        btn.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.height() > 0) {
            float clickX = (float) rect.centerX();
            float clickY = (float) rect.centerY();
            boolean gestureOk = clickXY(clickX, clickY);
            if (gestureOk) clicked = true;
        }

        return clicked;
    }

    private boolean clickXY(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private boolean swipeXY(float startX, float startY, float endX, float endY) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 100);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private void vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null && vibratorManager.getDefaultVibrator() != null) {
                    vibratorManager.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    vibrator.vibrate(180);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}
}
