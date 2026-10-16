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
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class RapidoAutoClickService extends AccessibilityService {

    public static volatile boolean isServiceRunning = false;
    public static volatile boolean isAutoAcceptOn = false;

    private SharedPreferences storage;
    private long lastClickTimestamp = 0;

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
        if (!isAutoAcceptOn) return;
        if (event == null) return;

        // Anti-bounce check (min 1500ms between accepted orders)
        long now = System.currentTimeMillis();
        if (now - lastClickTimestamp < 1500) return;

        // Try primary root in active window
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            handleRapidoOffer(root);
            root.recycle();
            return;
        }

        // Fallback: Event source node
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            handleRapidoOffer(source);
            source.recycle();
            return;
        }

        // Multi-window scan (floating overlay / HUD / PIP mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo winRoot = window.getRoot();
                    if (winRoot != null) {
                        handleRapidoOffer(winRoot);
                        winRoot.recycle();
                        if (System.currentTimeMillis() - lastClickTimestamp < 1500) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void handleRapidoOffer(AccessibilityNodeInfo root) {
        if (root == null) return;

        // 1. Check if offer screen is present (Rupee symbol or Accept button)
        boolean hasRupee = hasTextOrDesc(root, "₹") || hasTextOrDesc(root, "Rs");
        AccessibilityNodeInfo acceptBtn = findAcceptButton(root);

        if (!hasRupee && acceptBtn == null) {
            return;
        }

        // 2. Extract Price
        String priceText = findNodeTextWithSymbol(root, "₹");
        int basePrice = 0;
        int tipPrice = 0;
        int totalPrice = 0;
        String priceDisplayStr = "Rs. 0";

        if (!priceText.isEmpty()) {
            if (priceText.contains("+")) {
                int plusIdx = priceText.indexOf("+");
                basePrice = extractFirstNumber(priceText.substring(0, plusIdx));
                tipPrice = extractFirstNumber(priceText.substring(plusIdx + 1));
                totalPrice = basePrice + tipPrice;
                priceDisplayStr = "Rs. " + basePrice + " + Rs. " + tipPrice;
            } else {
                basePrice = extractFirstNumber(priceText);
                totalPrice = basePrice;
                priceDisplayStr = "Rs. " + basePrice;
            }
        }

        // 3. Admin Permission Flags
        boolean allowPrice = storage.getBoolean("allow_price_perm", true);
        boolean allowArea = storage.getBoolean("allow_area_perm", true);

        // 4. Price Filtering Logic
        boolean usePrice = allowPrice && storage.getBoolean("usePriceFilter", false);
        int minPrice = storage.getInt("minPrice", 40);
        int maxPrice = storage.getInt("maxPrice", 10000);
        boolean useSkipBelowMin = storage.getBoolean("useSkipBelowMin", false);

        if (usePrice && totalPrice > 0) {
            if (totalPrice < minPrice) {
                if (useSkipBelowMin) {
                    AccessibilityNodeInfo skipBtn = findSkipButton(root);
                    if (skipBtn != null) {
                        skipBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
                return;
            }
            if (totalPrice > maxPrice) {
                return;
            }
        }

        // 5. Area Filtering Logic
        boolean useArea = allowArea && storage.getBoolean("useAreaFilter", false);
        List<String> selectedAreas = loadSelectedAreas();
        String matchedAreaName = "General Ride";
        String foundDropKm = "--";

        if (useArea && !selectedAreas.isEmpty()) {
            DropAreaResult areaRes = checkDropArea(root, selectedAreas);
            if (!areaRes.matched) {
                return;
            }
            matchedAreaName = areaRes.areaName;
            foundDropKm = areaRes.dropKm;
        } else {
            DropAreaResult areaRes = checkDropArea(root, new ArrayList<String>());
            foundDropKm = areaRes.dropKm;
        }

        // 6. Trigger Order Acceptance
        if (acceptBtn != null) {
            boolean accepted = triggerAccept(acceptBtn);
            if (accepted) {
                lastClickTimestamp = System.currentTimeMillis();
                playBeepOrVibrate();

                // Save earnings and stats
                int oldEarn = storage.getInt("today_earnings", 0);
                int oldCount = storage.getInt("today_orders_count", 0);
                Calendar cal = Calendar.getInstance();
                int h = cal.get(Calendar.HOUR);
                if (h == 0) h = 12;
                int m = cal.get(Calendar.MINUTE);
                String ampm = cal.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
                String timeStr = h + ":" + (m < 10 ? "0" + m : m) + " " + ampm;

                storage.edit()
                        .putInt("today_earnings", oldEarn + totalPrice)
                        .putInt("today_orders_count", oldCount + 1)
                        .putString("last_order_price", priceDisplayStr)
                        .putString("last_order_area", matchedAreaName + " (" + foundDropKm + ")")
                        .putString("last_order_time", timeStr)
                        .apply();

                MainActivity.syncStaticUI();
                try { Thread.sleep(400); } catch (Exception ignored) {}
            }
        }
    }

    private boolean triggerAccept(AccessibilityNodeInfo btn) {
        if (btn == null) return false;

        // Method 1: Standard accessibility click
        boolean clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (clicked) return true;

        // Method 2: Parent click
        AccessibilityNodeInfo p = btn.getParent();
        if (p != null) {
            if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        // Method 3: Coordinate click or slider swipe
        Rect bounds = new Rect();
        btn.getBoundsInScreen(bounds);
        if (bounds.width() > 0 && bounds.height() > 0) {
            // Check if slider (wide button)
            if (bounds.width() > bounds.height() * 2) {
                float startX = bounds.left + (bounds.height() / 2f);
                float centerY = bounds.centerY();
                float endX = bounds.right - 20;
                boolean swipeOk = swipeXY(startX, centerY, endX, centerY);
                if (swipeOk) return true;
            }
            return clickXY(bounds.centerX(), bounds.centerY());
        }

        return false;
    }

    private int extractFirstNumber(String s) {
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

    private boolean hasTextOrDesc(AccessibilityNodeInfo root, String query) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(query);
        return nodes != null && !nodes.isEmpty();
    }

    private String findNodeTextWithSymbol(AccessibilityNodeInfo root, String symbol) {
        if (root == null) return "";
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(symbol);
        if (nodes != null) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.getText() != null && n.getText().toString().contains(symbol)) {
                    return n.getText().toString();
                }
                if (n.getContentDescription() != null && n.getContentDescription().toString().contains(symbol)) {
                    return n.getContentDescription().toString();
                }
            }
        }
        return "";
    }

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> minusNodes = root.findAccessibilityNodeInfosByText("-");
        if (minusNodes != null && !minusNodes.isEmpty()) {
            return minusNodes.get(0);
        }
        List<AccessibilityNodeInfo> skipNodes = root.findAccessibilityNodeInfosByText("Reject");
        if (skipNodes != null && !skipNodes.isEmpty()) {
            return skipNodes.get(0);
        }
        return null;
    }

    private AccessibilityNodeInfo findAcceptButton(AccessibilityNodeInfo root) {
        // View ID check
        String[] ids = new String[] {
                "com.rapido.captain:id/btn_accept",
                "com.rapido.captain:id/accept_order_btn",
                "com.rapido.captain:id/btnAccept",
                "com.rapido.captain:id/primary_action",
                "btn_accept"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                return nodes.get(0);
            }
        }

        // Text check
        String[] keywords = new String[] { "ACCEPT IN", "ACCEPT", "Accept", "ஏற்றுக்கொள்", "Tap to accept", "Slide to accept" };
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable()) return n;
                    if (n.getParent() != null && n.getParent().isClickable()) return n.getParent();
                    return n;
                }
            }
        }
        return null;
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

    private static class DropAreaResult {
        boolean matched;
        String areaName;
        String dropKm;
        DropAreaResult(boolean m, String a, String k) {
            matched = m; areaName = a; dropKm = k;
        }
    }

    private DropAreaResult checkDropArea(AccessibilityNodeInfo root, List<String> areasToCheck) {
        String dropKm = "--";
        String matchedArea = "";
        boolean isMatched = false;
        StringBuilder fullText = new StringBuilder();

        collectAllText(root, fullText);
        String lower = fullText.toString().toLowerCase();

        // Safe substring extraction for distance
        int kmIdx = lower.indexOf("km");
        if (kmIdx != -1) {
            int start = Math.max(0, kmIdx - 5);
            dropKm = fullText.substring(start, kmIdx + 2).trim();
        } else {
            int mIdx = lower.indexOf(" m");
            if (mIdx != -1) {
                int start = Math.max(0, mIdx - 5);
                dropKm = fullText.substring(start, mIdx + 2).trim();
            }
        }

        for (String a : areasToCheck) {
            String clean = a.trim();
            if (!clean.isEmpty() && lower.contains(clean.toLowerCase())) {
                matchedArea = clean;
                isMatched = true;
                break;
            }
        }
        return new DropAreaResult(isMatched, matchedArea, dropKm);
    }

    private void collectAllText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        if (node.getText() != null) sb.append(" ").append(node.getText());
        if (node.getContentDescription() != null) sb.append(" ").append(node.getContentDescription());
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectAllText(node.getChild(i), sb);
        }
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

    private void playBeepOrVibrate() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
        } catch (Exception e) {
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
    }

    @Override
    public void onInterrupt() {}
}
