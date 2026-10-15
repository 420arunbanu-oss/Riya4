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
import android.view.accessibility.AccessibilityWindowInfo;
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
        if (event == null) return;
        if (prefs == null) {
            prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE);
        }

        boolean isEnabled = prefs.getBoolean("is_auto_accept_on", true);
        if (!isEnabled) return;

        long now = System.currentTimeMillis();
        if (now - lastClickTime < 500) return;

        // 1. Scan event.getSource() - direct node that triggered notification/order popup
        AccessibilityNodeInfo sourceNode = event.getSource();
        if (sourceNode != null) {
            if (handleRapidoOffer(sourceNode)) {
                lastClickTime = now;
                vibratePhone();
                return;
            }
        }

        // 2. Scan all active windows (crucial while watching video / YouTube where Rapido is an overlay or notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window != null) {
                            AccessibilityNodeInfo winRoot = window.getRoot();
                            if (winRoot != null) {
                                if (handleRapidoOffer(winRoot)) {
                                    lastClickTime = now;
                                    vibratePhone();
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback to active window root
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            if (handleRapidoOffer(rootNode)) {
                lastClickTime = now;
                vibratePhone();
            }
        }
    }

    private boolean handleRapidoOffer(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Priority 1: Specific Rapido Captain Accept View IDs
        String[] viewIds = new String[] {
            "com.rapido.captain:id/btn_accept",
            "com.rapido.captain:id/accept_order_btn",
            "com.rapido.captain:id/btnAccept",
            "com.rapido.captain:id/accept_button",
            "com.rapido.captain:id/primary_action",
            "com.rapido.captain:id/btn_ride_accept",
            "com.rapido.captain:id/layout_accept",
            "com.rapido.captain:id/acceptButton",
            "com.rapido.captain:id/slide_accept",
            "com.rapido.captain:id/swipe_accept",
            "com.rapido.captain:id/btn_action",
            "com.rapido.captain:id/action_button",
            "com.rapido.rider:id/btn_accept",
            "btn_accept",
            "accept_order_btn",
            "accept_button"
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

        // Priority 2: Exact Accept button text labels in English & Tamil
        String[] acceptKeywords = new String[] {
            "Accept",
            "ACCEPT",
            "Accept Order",
            "ACCEPT ORDER",
            "Accept Ride",
            "ACCEPT RIDE",
            "Tap to accept",
            "TAP TO ACCEPT",
            "Slide to accept",
            "SLIDE TO ACCEPT",
            "Swipe to accept",
            "SWIPE TO ACCEPT",
            "ஏற்றுக்கொள்",
            "ஏற்கவும்",
            "स्वीकार करें",
            "स्वीकार",
            "CONFIRM",
            "Confirm"
        };
        for (String kw : acceptKeywords) {
            List<AccessibilityNodeInfo> textNodes = root.findAccessibilityNodeInfosByText(kw);
            if (textNodes != null && !textNodes.isEmpty()) {
                for (AccessibilityNodeInfo btn : textNodes) {
                    if (btn != null && triggerAccept(btn)) {
                        return true;
                    }
                }
            }
        }

        // Priority 3: Deep recursive search to find exact button or custom slider matching Accept criteria
        AccessibilityNodeInfo recursiveMatch = findAcceptNodeRecursive(root, 0);
        if (recursiveMatch != null) {
            if (triggerAccept(recursiveMatch)) {
                return true;
            }
        }

        return false;
    }

    private AccessibilityNodeInfo findAcceptNodeRecursive(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return null;
        if (isAcceptCandidate(node)) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findAcceptNodeRecursive(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isAcceptCandidate(AccessibilityNodeInfo node) {
        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        String t = (textSeq != null ? textSeq.toString() : "").trim().toLowerCase();
        String d = (descSeq != null ? descSeq.toString() : "").trim().toLowerCase();

        // Safety: Exclude reject, decline, cancel
        if (t.contains("reject") || t.contains("decline") || t.contains("cancel") || t.contains("skip") || t.contains("ignore")) return false;
        if (d.contains("reject") || d.contains("decline") || d.contains("cancel") || d.contains("skip") || d.contains("ignore")) return false;

        if (t.equals("accept") || t.startsWith("accept") || t.contains("tap to accept") || t.contains("slide to accept") ||
            t.contains("swipe to accept") || t.contains("ஏற்றுக்கொள்") || t.contains("ஏற்கவும்") || t.contains("स्वीकार")) {
            return true;
        }

        if (d.equals("accept") || d.startsWith("accept") || d.contains("tap to accept") || d.contains("slide to accept") ||
            d.contains("swipe to accept") || d.contains("ஏற்றுக்கொள்") || d.contains("ஏற்கவும்") || d.contains("स्वीकार")) {
            return true;
        }

        return false;
    }

    private boolean triggerAccept(AccessibilityNodeInfo btn) {
        if (btn == null) return false;

        Rect rect = new Rect();
        btn.getBoundsInScreen(rect);

        // Safety: Node must have real on-screen dimensions
        if (rect.width() <= 0 || rect.height() <= 0) return false;

        // 1. Accessibility click on button or closest clickable parent button container
        boolean clicked = false;
        if (btn.isClickable() && btn.isEnabled()) {
            clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        if (!clicked) {
            AccessibilityNodeInfo parent = btn.getParent();
            int depth = 0;
            while (parent != null && depth < 3) {
                Rect pRect = new Rect();
                parent.getBoundsInScreen(pRect);
                // Must be a button container (height < 300), not the full card or entire screen
                if (parent.isClickable() && pRect.height() < 300) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                parent = parent.getParent();
                depth++;
            }
        }

        // 2. Hardware Touch Gesture Click at exact (X, Y) center of the button!
        // Directly triggers the touch hardware layer even if accessibility click is intercepted
        float clickX = (float) rect.centerX();
        float clickY = (float) rect.centerY();
        boolean gestureOk = clickXY(clickX, clickY);
        if (gestureOk) clicked = true;

        // 3. Slider Support: If button is a "Slide to accept" or "Swipe to accept" slider
        CharSequence textSeq = btn.getText();
        CharSequence descSeq = btn.getContentDescription();
        String combined = ((textSeq != null ? textSeq.toString() : "") + " " + (descSeq != null ? descSeq.toString() : "")).toLowerCase();
        if (combined.contains("slide") || combined.contains("swipe") || rect.width() > 350) {
            float startX = rect.left + (rect.width() * 0.15f);
            float endX = rect.left + (rect.width() * 0.85f);
            swipeXY(startX, clickY, endX, clickY);
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
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
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
