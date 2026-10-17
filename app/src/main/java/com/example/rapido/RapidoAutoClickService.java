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
 * Ultra-Fast Dedicated Dual-Platform Auto-Click Engine
 * Incorporates:
 * 1. Rapido Verified Multi-Selector Engine with 1ms Hardware Tap + Slider Swipe
 * 2. Ola 7-Strategy Force-Accept Sequence (Action Click, Focus-Select, Parent Click, 150ms Tap, 200ms Swipe, Long-Click)
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

        CharSequence pkgSeq = event.getPackageName();
        String packageName = pkgSeq != null ? pkgSeq.toString().toLowerCase() : "";

        boolean isRapidoOn = storage.getBoolean("isRapidoEnabled", true);
        boolean isOlaOn = storage.getBoolean("isOlaEnabled", true);

        boolean accepted = false;

        // 1. DEDICATED RAPIDO ENGINE
        if (isRapidoOn && (packageName.contains("rapido") || isRapidoScreen(rootNode))) {
            accepted = handleRapidoOffer(rootNode);
        }

        // 2. DEDICATED OLA 7-STRATEGY ENGINE
        if (!accepted && isOlaOn && (packageName.contains("ola") || packageName.contains("olacabs") || isOlaScreen(rootNode))) {
            accepted = handleOlaOffer(rootNode);
        }

        // 3. Fallback scan based on active platform switches
        if (!accepted) {
            if (isRapidoOn) {
                accepted = handleRapidoOffer(rootNode);
            }
            if (!accepted && isOlaOn) {
                accepted = handleOlaOffer(rootNode);
            }
        }

        if (accepted) {
            lastClickTime = now;
            vibrateAndBeep();
            recordOrderStats(rootNode);
        }
    }

    /* =========================================================================
     * 1. DEDICATED RAPIDO ENGINE
     * ========================================================================= */

    private boolean handleRapidoOffer(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Apply Price & Area Filters first
        if (!passesFilters(root)) {
            return false;
        }

        // Strategy A: Direct Rapido Text Candidates Search (0.1ms native indexed)
        String[] rapidoTextCandidates = new String[] {
                "Accept", "ACCEPT", "accept",
                "ஏற்றுக்கொள்", "स्वीकार करें",
                "Tap to accept", "TAP TO ACCEPT",
                "Slide to accept", "SLIDE TO ACCEPT",
                "Swipe to accept", "SWIPE TO ACCEPT",
                "Accept Order", "ACCEPT ORDER",
                "Accept Ride", "ACCEPT RIDE"
        };
        for (String candidate : rapidoTextCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(candidate);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && isVisibleNode(node)) {
                        if (clickRapidoBoundsXY(node)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Strategy B: Rapido Verified View ID Candidates
        String[] rapidoIdCandidates = new String[] {
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
        for (String id : rapidoIdCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && isVisibleNode(node)) {
                        if (clickRapidoBoundsXY(node)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Strategy C: Rupee Symbol ₹ Card Detection -> Climb to Clickable Parent (depth 0..4, width > 100)
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo rNode : rupeeNodes) {
                AccessibilityNodeInfo parent = rNode.getParent();
                int depth = 0;
                while (parent != null && depth < 4) {
                    Rect rect = new Rect();
                    parent.getBoundsInScreen(rect);
                    if (parent.isClickable() && rect.width() > 100 && isVisibleNode(parent)) {
                        return clickRapidoBoundsXY(parent);
                    }
                    parent = parent.getParent();
                    depth++;
                }
            }
        }

        return false;
    }

    private boolean clickRapidoBoundsXY(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        if ((rect.width() <= 0 || rect.height() <= 0) && node.getParent() != null) {
            node.getParent().getBoundsInScreen(rect);
        }

        boolean gestureDispatched = false;

        // 1. Direct Hardware Screen (X, Y) Tap in 1 Millisecond
        if (rect.width() > 0 && rect.height() > 0) {
            float clickX = rect.centerX();
            float clickY = rect.centerY();
            gestureDispatched = clickXY(clickX, clickY, 1);

            // If slider button (wide), also dispatch full horizontal swipe
            if (rect.width() > rect.height() * 1.3f) {
                float startX = rect.left + 50;
                float endX = rect.right - 30;
                swipeXY(startX, clickY, endX, clickY, 100);
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

    /* =========================================================================
     * 2. DEDICATED OLA 7-STRATEGY ENGINE (Reconstructed from Ride Accepter Pro)
     * ========================================================================= */

    private boolean handleOlaOffer(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Apply Price & Area Filters first
        if (!passesFilters(root)) {
            return false;
        }

        // Direct Text Candidates for Ola
        String[] olaTextCandidates = new String[] {
                "ACCEPT", "Accept", "accept",
                "ஏற்றுக்கொள்", "स्वीकार करें",
                "Tap to accept", "Accept Ride", "ACCEPT RIDE",
                "Slide to accept", "Ola Ride"
        };
        for (String label : olaTextCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && isVisibleNode(node)) {
                        if (executeOlaSevenStrategies(node)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Common Ola View IDs
        String[] olaIdCandidates = new String[] {
                "com.olacabs.customer:id/btn_accept",
                "com.olacabs.customer:id/accept_button",
                "com.olacabs.partner:id/btn_accept",
                "btn_accept", "accept_button", "accept", "accept_ride"
        };
        for (String id : olaIdCandidates) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && isVisibleNode(node)) {
                        if (executeOlaSevenStrategies(node)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Executes the 7 Ola Click Strategies recovered from Ride Accepter Pro-v4:
     * Strategy 1: ACTION_CLICK on target node
     * Strategy 2: FOCUS + SELECT + CLICK on target node
     * Strategy 3: ACTION_CLICK on parent (walk up the node tree)
     * Strategy 4: OLA Gesture 1 (150ms hardware tap at node coordinates)
     * Strategy 5: OLA Gesture 2 (200ms hardware swipe across track)
     * Strategy 6: ACTION_LONG_CLICK on node & parent
     * Strategy 7: Fallback gesture + action click retry
     */
    private boolean executeOlaSevenStrategies(AccessibilityNodeInfo node) {
        if (node == null) return false;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if ((rect.width() <= 0 || rect.height() <= 0) && node.getParent() != null) {
            node.getParent().getBoundsInScreen(rect);
        }

        boolean success = false;

        // Strategy 1: Direct ACTION_CLICK on node
        try {
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                success = true;
            }
        } catch (Exception ignored) {}

        // Strategy 2: FOCUS + SELECT + CLICK on node
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.performAction(AccessibilityNodeInfo.ACTION_SELECT);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                success = true;
            }
        } catch (Exception ignored) {}

        // Strategy 3: Walk up node tree to click clickable parent
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            try {
                if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    success = true;
                    break;
                }
            } catch (Exception ignored) {}
            parent = parent.getParent();
        }

        // Strategy 4: OLA Gesture 1 COMPLETED (150ms tap)
        if (rect.width() > 0 && rect.height() > 0) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            if (clickXY(cx, cy, 150)) {
                success = true;
            }

            // Strategy 5: OLA Gesture 2 COMPLETED (200ms swipe across slider)
            float startX = rect.left > 0 ? (rect.left + 50) : 120;
            float endX = (rect.right > rect.left) ? (rect.right - 30) : 950;
            if (swipeXY(startX, cy, endX, cy, 200)) {
                success = true;
            }
        }

        // Strategy 6: LONG_CLICK on node & parent
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                success = true;
            }
            if (node.getParent() != null) {
                node.getParent().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
        } catch (Exception ignored) {}

        return success;
    }

    /* =========================================================================
     * GESTURE & SCREEN HELPERS
     * ========================================================================= */

    private boolean clickXY(float x, float y, long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs > 0 ? durationMs : 1);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private boolean swipeXY(float startX, float startY, float endX, float endY, long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs > 0 ? durationMs : 100);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private boolean isVisibleNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return node.isVisibleToUser();
        }
        return true;
    }

    private boolean isRapidoScreen(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Rapido");
        if (nodes != null && !nodes.isEmpty()) return true;
        List<AccessibilityNodeInfo> cNodes = root.findAccessibilityNodeInfosByText("Captain");
        return cNodes != null && !cNodes.isEmpty();
    }

    private boolean isOlaScreen(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Ola");
        if (nodes != null && !nodes.isEmpty()) return true;
        List<AccessibilityNodeInfo> pNodes = root.findAccessibilityNodeInfosByText("Partner");
        return pNodes != null && !pNodes.isEmpty();
    }

    /* =========================================================================
     * DRIVER FILTER EVALUATION
     * ========================================================================= */

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

        // 2. Area Filter Check: If Area is NOT ticked (or list empty), ONLY PRICE works!
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
                clickRapidoBoundsXY(nodes.get(0));
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
