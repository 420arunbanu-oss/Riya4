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
        if (now - lastClickTime < 700) return;

        boolean accepted = scanAndTriggerAccept(rootNode);
        if (accepted) {
            lastClickTime = now;
            vibratePhone();
        }
    }

    private boolean scanAndTriggerAccept(AccessibilityNodeInfo root) {
        String[] textCandidates = new String[] {
            "Accept", "ACCEPT", "accept",
            "ஏற்றுக்கொள்", "स्वीकार करें",
            "Tap to accept", "TAP TO ACCEPT",
            "Accept Order", "ACCEPT ORDER"
        };

        for (String candidate : textCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(candidate);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickByBoundsXY(node)) {
                        return true;
                    }
                }
            }
        }

        String[] idCandidates = new String[] {
            "com.rapido.captain:id/btn_accept",
            "com.rapido.captain:id/accept_order_btn",
            "com.rapido.captain:id/btnAccept",
            "com.rapido.captain:id/accept_button",
            "com.rapido.captain:id/action_button",
            "com.rapido.captain:id/primary_action",
            "com.rapido.rider:id/btn_accept",
            "btn_accept", "accept", "accept_order"
        };

        for (String id : idCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickByBoundsXY(node)) {
                        return true;
                    }
                }
            }
        }

        // Rupee Symbol ₹ detection - find Accept in active offer card
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo rNode : rupeeNodes) {
                AccessibilityNodeInfo currentParent = rNode.getParent();
                int depth = 0;
                while (currentParent != null && depth < 4) {
                    Rect rect = new Rect();
                    currentParent.getBoundsInScreen(rect);
                    if (currentParent.isClickable() && rect.width() > 100) {
                        return clickByBoundsXY(currentParent);
                    }
                    currentParent = currentParent.getParent();
                    depth++;
                }
            }
        }

        return false;
    }

    /**
     * Exact (X, Y) Bounds Millisecond Click:
     * Reads node.getBoundsInScreen(rect) -> calculates exact center (x, y) pixels.
     * Dispatches hardware touch directly to screen coordinates in milliseconds.
     * Also triggers accessibility ACTION_CLICK simultaneously.
     */
    private boolean clickByBoundsXY(AccessibilityNodeInfo node) {
        if (node == null) return false;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        boolean gestureDispatched = false;

        // 1. Direct Hardware Screen (X, Y) Bounds Tap in 50 Milliseconds
        if (rect.width() > 0 && rect.height() > 0) {
            float clickX = (float) rect.centerX();
            float clickY = (float) rect.centerY();
            gestureDispatched = clickXY(clickX, clickY);

            // If wide button/slider, also trigger horizontal swipe gesture!
            if (rect.width() > 180) {
                float startX = rect.left + 50f;
                float endX = rect.right - 50f;
                float midY = (float) rect.centerY();
                swipeXY(startX, midY, endX, midY);
            }
        }

        // 2. Parallel Node Accessibility Click
        boolean actionClicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!actionClicked) {
            AccessibilityNodeInfo p1 = node.getParent();
            if (p1 != null) {
                actionClicked = p1.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!actionClicked) {
                    AccessibilityNodeInfo p2 = p1.getParent();
                    if (p2 != null) {
                        actionClicked = p2.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }

        return gestureDispatched || actionClicked;
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
