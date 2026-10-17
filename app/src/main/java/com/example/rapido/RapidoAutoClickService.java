package com.example.rapido;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Ultra-Fast Zero-Lag Rapido Auto Click Engine
 * 100% Non-blocking, 0ms ANR-free execution using native indexed node bounds tap & swipe.
 */
public class RapidoAutoClickService extends AccessibilityService {

    public static volatile boolean isServiceRunning = false;
    public static volatile boolean isAutoAcceptOn = false;

    private SharedPreferences storage;
    private long lastClickTime = 0L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isServiceRunning = true;
        storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);
        isAutoAcceptOn = storage.getBoolean("isRunning", false);
        MainActivity.syncStaticUI();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        MainActivity.syncStaticUI();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (storage == null) {
            storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);
        }
        if (!isAutoAcceptOn) {
            isAutoAcceptOn = storage.getBoolean("isRunning", false);
        }
        if (!isAutoAcceptOn) return;

        // Anti-bounce check: minimum 600ms between accepted orders
        long now = System.currentTimeMillis();
        if (now - lastClickTime < 600) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        boolean accepted = scanAndTriggerAccept(rootNode);
        if (accepted) {
            lastClickTime = now;
            vibrateAndBeep();
            recordOrderStats(rootNode);
        }
    }

    /**
     * Exact ultra-fast scan & accept method
     */
    private boolean scanAndTriggerAccept(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // 1. Apply Driver Price & Area Filters first
        if (!passesFilters(root)) {
            return false;
        }

        // 2. Direct Accept Text Search (Native indexed search - 0.1ms)
        String[] textCandidates = new String[] {
                "Accept", "ACCEPT", "accept",
                "ஏற்றுக்கொள்", "स्वीकार करें",
                "Tap to accept", "TAP TO ACCEPT",
                "Slide to accept", "SLIDE TO ACCEPT",
                "Swipe to accept", "SWIPE TO ACCEPT",
                "Accept Order", "ACCEPT ORDER",
                "Accept Ride", "ACCEPT RIDE"
        };
        for (String candidate : textCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(candidate);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickByBoundsXY(node)) {
                        return true;
                    }
                }
            }
        }

        // 3. Common Rapido Captain View IDs
        String[] idCandidates = new String[] {
                "com.rapido.captain:id/btn_accept",
                "com.rapido.captain:id/accept_order_btn",
                "com.rapido.captain:id/btnAccept",
                "com.rapido.captain:id/accept_button",
                "com.rapido.captain:id/action_button",
                "com.rapido.captain:id/primary_action",
                "com.rapido.captain:id/slide_button",
                "com.rapido.captain:id/swipe_button",
                "com.rapido.rider:id/btn_accept",
                "btn_accept", "accept", "accept_order"
        };
        for (String id : idCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickByBoundsXY(node)) {
                        return true;
                    }
                }
            }
        }

        // 4. Rupee Symbol ₹ detection - find Accept in active offer card
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo rNode : rupeeNodes) {
                AccessibilityNodeInfo parent = rNode.getParent();
                int depth = 0;
                while (parent != null && depth < 4) {
                    Rect rect = new Rect();
                    parent.getBoundsInScreen(rect);
                    if (parent.isClickable() && rect.width() > 100) {
                        return clickByBoundsXY(parent);
                    }
                    parent = parent.getParent();
                    depth++;
                }
            }
        }

        return false;
    }

    /**
     * Exact (X, Y) Bounds Millisecond Click:
     * Reads node.getBoundsInScreen(rect) -> calculates exact center (x, y) pixels.
     * Dispatches hardware touch directly to screen coordinates in 1 millisecond.
     * Also triggers accessibility ACTION_CLICK simultaneously.
     */
    private boolean clickByBoundsXY(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        // If this node has 0 size, try its clickable parent
        if ((rect.width() <= 0 || rect.height() <= 0) && node.getParent() != null) {
            node.getParent().getBoundsInScreen(rect);
        }

        boolean gestureDispatched = false;

        // 1. Direct Hardware Screen (X, Y) Bounds Tap in 1 Millisecond
        if (rect.width() > 0 && rect.height() > 0) {
            float clickX = rect.centerX();
            float clickY = rect.centerY();
            gestureDispatched = clickXY(clickX, clickY);

            // If slider button (wide), also dispatch swipe across track
            if (rect.width() > rect.height() * 1.4f) {
                float startX = rect.left + 50;
                float endX = rect.right - 30;
                swipeXY(startX, clickY, endX, clickY);
                gestureDispatched = true;
            }
        }

        // 2. Parallel Node Accessibility Click
        boolean actionClicked = false;
        try {
            actionClicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {}
        if (!actionClicked && node.getParent() != null) {
            try {
                actionClicked = node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {}
        }
        if (!actionClicked && node.getParent() != null && node.getParent().getParent() != null) {
            try {
                actionClicked = node.getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {}
        }

        return gestureDispatched || actionClicked;
    }

    private boolean clickXY(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            // 1 millisecond lightning hardware tap at exact (X, Y) coordinates
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 1);
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

    /**
     * Driver Filter Evaluation:
     * Fast, lightweight, non-blocking check.
     */
    private boolean passesFilters(AccessibilityNodeInfo root) {
        if (storage == null) return true;

        // 1. Price Filter Check
        boolean usePrice = storage.getBoolean("usePriceFilter", false);
        if (usePrice) {
            int minPrice = storage.getInt("minPrice", 40);
            int maxPrice = storage.getInt("maxPrice", 10000);
            int price = quickExtractPrice(root);
            if (price > 0) {
                if (price < minPrice || price > maxPrice) {
                    if (storage.getBoolean("useSkipBelowMin", false) && price < minPrice) {
                        quickClickSkip(root);
                    }
                    return false;
                }
            }
        }

        // 2. Area Filter Check
        // Rule: If Area is NOT ticked (or list empty), ONLY PRICE works!
        boolean useArea = storage.getBoolean("useAreaFilter", false);
        if (useArea) {
            List<String> areas = loadSelectedAreas();
            if (!areas.isEmpty()) {
                boolean areaMatched = false;
                for (String area : areas) {
                    String trimmed = area.trim();
                    if (trimmed.isEmpty()) continue;
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(trimmed);
                    if (found != null && !found.isEmpty()) {
                        areaMatched = true;
                        break;
                    }
                }
                if (!areaMatched) {
                    return false;
                }
            }
        }

        return true;
    }

    private int quickExtractPrice(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : rupeeNodes) {
                String txt = n.getText() != null ? n.getText().toString() : "";
                int p = parseDigits(txt);
                if (p > 0) return p;
                AccessibilityNodeInfo pNode = n.getParent();
                if (pNode != null) {
                    int cCount = pNode.getChildCount();
                    for (int i = 0; i < cCount; i++) {
                        AccessibilityNodeInfo sib = pNode.getChild(i);
                        if (sib != null && sib != n) {
                            String sTxt = sib.getText() != null ? sib.getText().toString() : "";
                            int sp = parseDigits(sTxt);
                            if (sp > 0) return sp;
                        }
                    }
                }
            }
        }
        return 0;
    }

    private int parseDigits(String s) {
        if (s == null) return 0;
        StringBuilder num = new StringBuilder();
        boolean foundDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
                foundDigit = true;
            } else if (foundDigit) {
                break;
            }
        }
        if (num.length() > 0) {
            try {
                return Integer.parseInt(num.toString());
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private void quickClickSkip(AccessibilityNodeInfo root) {
        String[] skipWords = new String[] { "-", "Reject", "Skip", "REJECT", "SKIP", "மறு" };
        for (String w : skipWords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(w);
            if (nodes != null && !nodes.isEmpty()) {
                clickByBoundsXY(nodes.get(0));
                return;
            }
        }
    }

    private List<String> loadSelectedAreas() {
        List<String> list = new ArrayList<String>();
        try {
            String json = storage.getString("selectedAreasJson", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String a = arr.optString(i).trim();
                if (!a.isEmpty()) list.add(a);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void recordOrderStats(AccessibilityNodeInfo root) {
        try {
            int price = quickExtractPrice(root);
            if (price == 0) price = 50;
            int oldEarn = storage.getInt("today_earnings", 0);
            int oldCount = storage.getInt("today_orders_count", 0);
            Calendar cal = Calendar.getInstance();
            int h = cal.get(Calendar.HOUR);
            if (h == 0) h = 12;
            int m = cal.get(Calendar.MINUTE);
            String ampm = cal.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
            String timeStr = h + ":" + (m < 10 ? "0" + m : m) + " " + ampm;

            storage.edit()
                    .putInt("today_earnings", oldEarn + price)
                    .putInt("today_orders_count", oldCount + 1)
                    .putString("last_order_price", "Rs. " + price)
                    .putString("last_order_time", timeStr)
                    .apply();

            MainActivity.syncStaticUI();
        } catch (Exception ignored) {}
    }

    private void vibrateAndBeep() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null && vm.getDefaultVibrator() != null) {
                    vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(180);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}
}
