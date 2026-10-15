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
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RapidoAutoClickService extends AccessibilityService {

    public static boolean isServiceRunning = false;
    public static boolean isAutoAcceptOn = false;

    private SharedPreferences storage;
    private ExecutorService scanExecutor;
    private AtomicBoolean isScanning = new AtomicBoolean(false);
    private Handler mainHandler;
    private long lastAcceptTime = 0L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isServiceRunning = true;
        storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);
        isAutoAcceptOn = storage.getBoolean("isRunning", false);
        scanExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        isAutoAcceptOn = false;
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (storage == null) {
            storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);
        }

        // Fast state check
        if (!isAutoAcceptOn && !storage.getBoolean("isRunning", false)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgStr = pkg.toString().toLowerCase();

        // Instant package filter: Only Rapido or Ola packages
        if (!pkgStr.contains("rapido") && !pkgStr.contains("ola")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAcceptTime < 600) return;

        // Prevent queue buildup: if scan is currently running in background, skip!
        if (isScanning.get()) return;

        // Run scanning strictly on background worker thread -> ZERO MAIN THREAD ANR!
        scanExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (!isScanning.compareAndSet(false, true)) return;
                try {
                    processOrderScan();
                } catch (Exception ignored) {
                } finally {
                    isScanning.set(false);
                }
            }
        });
    }

    private void processOrderScan() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // AutoX.js Box 09 Logic:
        // 1. Detect Ride Offer
        boolean hasOrder = hasTextOrDesc(root, "Auto") ||
                hasTextOrDesc(root, "Bike") ||
                hasTextOrDesc(root, "Cab") ||
                hasTextOrDesc(root, "OLA") ||
                hasTextOrDesc(root, "Ola") ||
                hasTextOrDesc(root, "ACCEPT IN") ||
                hasTextOrDesc(root, "Accept");

        if (!hasOrder) return;

        // 2. Extract Price
        String priceText = findNodeTextWithSymbol(root, "₹");
        int basePrice = 0;
        int tipPrice = 0;
        int totalPrice = 0;
        String priceDisplayStr = "Rs. 0";

        if (!priceText.isEmpty()) {
            Pattern multiPattern = Pattern.compile("₹?\s*(\d+)\s*\+\s*₹?\s*(\d+)");
            Matcher mMulti = multiPattern.matcher(priceText);
            if (mMulti.find()) {
                basePrice = Integer.parseInt(mMulti.group(1));
                tipPrice = Integer.parseInt(mMulti.group(2));
                totalPrice = basePrice + tipPrice;
                priceDisplayStr = "Rs. " + basePrice + " + Rs. " + tipPrice;
            } else {
                Pattern singlePattern = Pattern.compile("₹?\s*(\d+)");
                Matcher mSingle = singlePattern.matcher(priceText);
                if (mSingle.find()) {
                    basePrice = Integer.parseInt(mSingle.group(1));
                    totalPrice = basePrice;
                    priceDisplayStr = "Rs. " + basePrice;
                }
            }
        }

        int minPrice = storage.getInt("minPrice", 40);
        int maxPrice = storage.getInt("maxPrice", 10000);
        boolean usePriceFilter = storage.getBoolean("usePriceFilter", false);
        boolean useSkipBelowMin = storage.getBoolean("useSkipBelowMin", false);
        boolean allowPricePermission = storage.getBoolean("allow_price_perm", true);
        boolean useAreaFilter = storage.getBoolean("useAreaFilter", false);
        boolean allowAreaPermission = storage.getBoolean("allow_area_perm", true);

        // 3. Skip Order Below Min Price (AutoX.js Logic)
        if (useSkipBelowMin && allowPricePermission && basePrice > 0 && basePrice < minPrice) {
            AccessibilityNodeInfo skipBtn = findSkipButton(root);
            if (skipBtn != null) {
                Rect b = new Rect();
                skipBtn.getBoundsInScreen(b);
                if (b.width() > 0) {
                    clickXY(b.centerX(), b.centerY());
                    skipBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    try { Thread.sleep(600); } catch (Exception ignored) {}
                    return;
                }
            }
        }

        // 4. Price Condition
        boolean priceConditionMet = true;
        if (usePriceFilter && allowPricePermission) {
            priceConditionMet = (basePrice >= minPrice && basePrice <= maxPrice);
        }
        if (!priceConditionMet) return;

        // 5. Area Condition (AutoX.js Logic)
        boolean areaConditionMet = true;
        String matchedAreaName = "General";
        String foundDropKm = "--";

        if (useAreaFilter && allowAreaPermission) {
            List<String> areasToCheck = loadSelectedAreas();
            if (!areasToCheck.isEmpty()) {
                DropAreaResult areaRes = checkDropArea(root, areasToCheck);
                areaConditionMet = areaRes.matched;
                matchedAreaName = areaRes.areaName;
                foundDropKm = areaRes.dropKm;
            }
        }

        if (!areaConditionMet) return;

        // 6. Find and Click Accept Button
        AccessibilityNodeInfo acceptBtn = findAcceptButton(root);
        if (acceptBtn != null) {
            Rect b = new Rect();
            acceptBtn.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                // Hardware 1ms touch gesture
                clickXY(b.centerX(), b.centerY());
                // Accessibility action
                acceptBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);

                // If wide slider bar, swipe across
                CharSequence txt = acceptBtn.getText();
                CharSequence desc = acceptBtn.getContentDescription();
                String full = ((txt != null ? txt.toString() : "") + " " + (desc != null ? desc.toString() : "")).toLowerCase();
                if (full.contains("slide") || full.contains("swipe") || b.width() > 380) {
                    swipeXY(b.left + 50, b.centerY(), b.right - 50, b.centerY());
                }

                lastAcceptTime = System.currentTimeMillis();

                // Sound / Beep Notification
                playBeepOrVibrate();

                // Update Stats in Storage
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
        List<String> list = new ArrayList<>();
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

        // Match distance
        Pattern distPattern = Pattern.compile("(\d+(\.\d+)?)\s*(km|m)", Pattern.CASE_INSENSITIVE);
        Matcher mDist = distPattern.matcher(fullText);
        if (mDist.find()) {
            dropKm = mDist.group(0);
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
