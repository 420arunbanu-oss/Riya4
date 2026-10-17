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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (storage == null) {
            storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);
        }
        if (!isAutoAcceptOn) {
            isAutoAcceptOn = storage.getBoolean("isRunning", false);
        }
        if (!isAutoAcceptOn) return;
        if (event == null) return;

        // Anti-bounce debounce: minimum 500ms between accepts
        long now = System.currentTimeMillis();
        if (now - lastClickTimestamp < 500) return;

        // 1. Try primary root in active window
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean handled = handleRapidoOffer(root);
            if (handled) return;
        }

        // 2. Fallback: Event source node
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            boolean handled = handleRapidoOffer(source);
            if (handled) return;
        }

        // 3. Multi-window scan (covers floating popups, HUD, overlay windows)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo winRoot = window.getRoot();
                    if (winRoot != null) {
                        boolean handled = handleRapidoOffer(winRoot);
                        if (handled) break;
                    }
                }
            }
        }
    }

    private boolean handleRapidoOffer(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // 1. Collect full screen text for reliable inspection
        StringBuilder fullTextBuilder = new StringBuilder();
        collectAllText(root, fullTextBuilder);
        String fullScreenText = fullTextBuilder.toString();
        String fullScreenLower = fullScreenText.toLowerCase();

        // 2. Check if an offer/order screen is present
        boolean hasRupee = fullScreenText.contains("₹") || fullScreenLower.contains("rs.") || fullScreenLower.contains("rs ");
        AccessibilityNodeInfo acceptBtn = findAcceptButton(root);

        // If no Rupee and no Accept button is visible, this is not an active order screen
        if (!hasRupee && acceptBtn == null) {
            return false;
        }

        // 3. Extract Order Price (Base + Tip)
        int totalPrice = extractOrderPrice(root, fullScreenText);
        String priceDisplayStr = "Rs. " + (totalPrice > 0 ? totalPrice : "--");

        // 4. Admin Permission Flags
        boolean allowPrice = storage.getBoolean("allow_price_perm", true);
        boolean allowArea = storage.getBoolean("allow_area_perm", true);

        // 5. PRICE FILTERING LOGIC
        // If Price Filter is enabled by user, enforce minPrice and maxPrice strictly
        boolean usePrice = allowPrice && storage.getBoolean("usePriceFilter", false);
        int minPrice = storage.getInt("minPrice", 40);
        int maxPrice = storage.getInt("maxPrice", 10000);
        boolean useSkipBelowMin = storage.getBoolean("useSkipBelowMin", false);

        if (usePrice && totalPrice > 0) {
            if (totalPrice < minPrice) {
                // Price is below driver minimum setting: skip or ignore
                if (useSkipBelowMin) {
                    AccessibilityNodeInfo skipBtn = findSkipButton(root);
                    if (skipBtn != null) {
                        skipBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
                return false;
            }
            if (totalPrice > maxPrice) {
                // Price is above driver maximum setting: ignore
                return false;
            }
        }

        // 6. AREA FILTERING LOGIC
        // User rule: "சப்போஸ் நம்ம ஏரியா டிக் பண்ணல அப்படின்னா விலை மட்டும் வேலை செய்யணும்"
        // If Area Filter is NOT ticked (or no areas selected), ONLY PRICE filter is used!
        boolean useArea = allowArea && storage.getBoolean("useAreaFilter", false);
        List<String> selectedAreas = loadSelectedAreas();
        String matchedAreaName = "General Ride";
        String foundDropKm = "--";

        if (useArea && !selectedAreas.isEmpty()) {
            DropAreaResult areaRes = checkDropArea(fullScreenLower, selectedAreas);
            if (!areaRes.matched) {
                // Area does not match any driver selected areas: DO NOT CLICK ACCEPT
                return false;
            }
            matchedAreaName = areaRes.areaName;
            foundDropKm = areaRes.dropKm;
        } else {
            // Area filter is unticked: accept anywhere based only on price!
            DropAreaResult areaRes = checkDropArea(fullScreenLower, new ArrayList<String>());
            foundDropKm = areaRes.dropKm;
        }

        // 7. Find and CLICK the Accept Button / Slider!
        if (acceptBtn == null) {
            acceptBtn = findAcceptButton(root);
        }

        // If specific Accept button not found by keyword/ID, find any clickable button near Rupee
        if (acceptBtn == null && hasRupee) {
            acceptBtn = findClickableNearRupee(root);
        }

        if (acceptBtn != null) {
            boolean accepted = clickAndAccept(acceptBtn, root);
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
                        .putInt("today_earnings", oldEarn + (totalPrice > 0 ? totalPrice : 50))
                        .putInt("today_orders_count", oldCount + 1)
                        .putString("last_order_price", priceDisplayStr)
                        .putString("last_order_area", matchedAreaName + " (" + foundDropKm + ")")
                        .putString("last_order_time", timeStr)
                        .apply();

                MainActivity.syncStaticUI();
                return true;
            }
        }

        return false;
    }

    /**
     * Complete Multi-Layer Accept Trigger:
     * 1. Climbs node hierarchy to find full slider track
     * 2. Dispatches Center Hardware Tap (50ms duration)
     * 3. Dispatches Full Horizontal Swipe Gesture across slider width
     * 4. Dispatches Screen Center Tap
     * 5. Calls Accessibility ACTION_CLICK on all levels
     */
    private boolean clickAndAccept(AccessibilityNodeInfo btn, AccessibilityNodeInfo root) {
        if (btn == null) return false;

        AccessibilityNodeInfo targetNode = btn;
        AccessibilityNodeInfo current = btn;
        Rect bounds = new Rect();
        btn.getBoundsInScreen(bounds);

        // Climb up hierarchy (depth 0 to 5) to find wide button or slider container
        int depth = 0;
        while (current != null && depth < 5) {
            Rect r = new Rect();
            current.getBoundsInScreen(r);
            if (r.width() > bounds.width() || current.isClickable()) {
                bounds = r;
                targetNode = current;
            }
            current = current.getParent();
            depth++;
        }

        Rect screenBounds = new Rect();
        if (root != null) {
            root.getBoundsInScreen(screenBounds);
        }
        int screenWidth = screenBounds.width() > 0 ? screenBounds.width() : 1080;

        boolean gestureDispatched = false;

        if (bounds.width() > 0 && bounds.height() > 0) {
            float cx = bounds.centerX();
            float cy = bounds.centerY();

            // 1. Center hardware tap at exact coordinates (50ms solid touch)
            clickXY(cx, cy);

            // 2. Center of screen tap at button Y level (in case button is full width)
            clickXY(screenWidth / 2f, cy);

            // 3. Full-width horizontal swipe (for Slide to Accept / Swipe to Accept)
            float startX = bounds.left > 0 ? (bounds.left + 80) : 120;
            float endX = (bounds.right > bounds.left && bounds.right < screenWidth) ? (bounds.right - 50) : (screenWidth - 120);
            swipeXY(startX, cy, endX, cy);
            gestureDispatched = true;
        } else {
            // Fallback: swipe & tap bottom 20% of screen where Rapido Accept always sits
            if (screenBounds.height() > 0) {
                float bottomY = screenBounds.height() * 0.85f;
                clickXY(screenWidth / 2f, bottomY);
                swipeXY(120, bottomY, screenWidth - 120, bottomY);
                gestureDispatched = true;
            }
        }

        // 4. Parallel Accessibility Node ACTION_CLICK on all hierarchy levels
        try {
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {}
        if (targetNode != null && targetNode != btn) {
            try {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {}
        }
        AccessibilityNodeInfo p = btn.getParent();
        while (p != null) {
            try {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {}
            p = p.getParent();
        }

        return gestureDispatched;
    }

    private AccessibilityNodeInfo findAcceptButton(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // 1. View ID check for all Rapido Captain versions
        String[] ids = new String[] {
                "com.rapido.captain:id/btn_accept",
                "com.rapido.captain:id/accept_order_btn",
                "com.rapido.captain:id/btnAccept",
                "com.rapido.captain:id/accept_button",
                "com.rapido.captain:id/action_button",
                "com.rapido.captain:id/primary_action",
                "com.rapido.captain:id/slide_button",
                "com.rapido.captain:id/swipe_button",
                "com.rapido.captain:id/slider",
                "com.rapido.captain:id/thumb",
                "com.rapido.captain:id/cta_button",
                "com.rapido.captain:id/cta",
                "com.rapido.captain:id/btn_confirm",
                "com.rapido.captain:id/accept_btn",
                "com.rapido.captain:id/order_accept_btn",
                "com.rapido.rider:id/btn_accept",
                "btn_accept", "accept", "accept_order"
        };

        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n != null) return n;
                }
            }
        }

        // 2. Case-insensitive Text & Description check
        String[] keywords = new String[] {
                "Accept", "ACCEPT", "accept",
                "Tap to accept", "TAP TO ACCEPT",
                "Slide to accept", "SLIDE TO ACCEPT",
                "Swipe to accept", "SWIPE TO ACCEPT",
                "Hold to accept", "HOLD TO ACCEPT",
                "Accept Order", "ACCEPT ORDER",
                "Accept Ride", "ACCEPT RIDE",
                "Accept in", "ACCEPT IN",
                "ஏற்றுக்கொள்", "ஏற்கவும்",
                "स्वीकार करें", "स्वीकारें"
        };

        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n == null) continue;
                    if (n.isClickable()) return n;
                    if (n.getParent() != null && n.getParent().isClickable()) return n.getParent();
                    return n;
                }
            }
        }

        return null;
    }

    private AccessibilityNodeInfo findClickableNearRupee(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo rNode : rupeeNodes) {
                AccessibilityNodeInfo parent = rNode.getParent();
                int depth = 0;
                while (parent != null && depth < 5) {
                    Rect r = new Rect();
                    parent.getBoundsInScreen(r);
                    if (parent.isClickable() && r.width() > 150) {
                        return parent;
                    }
                    // Check children of this card for a button
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        AccessibilityNodeInfo ch = parent.getChild(i);
                        if (ch != null && ch.isClickable()) {
                            Rect cr = new Rect();
                            ch.getBoundsInScreen(cr);
                            if (cr.width() > 100) return ch;
                        }
                    }
                    parent = parent.getParent();
                    depth++;
                }
            }
        }
        return null;
    }

    private int extractOrderPrice(AccessibilityNodeInfo root, String fullText) {
        int bestPrice = 0;

        // Method 1: Check nodes with Rupee symbol
        List<AccessibilityNodeInfo> rupeeNodes = root.findAccessibilityNodeInfosByText("₹");
        if (rupeeNodes != null && !rupeeNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : rupeeNodes) {
                String txt = n.getText() != null ? n.getText().toString() : "";
                if (txt.isEmpty() && n.getContentDescription() != null) {
                    txt = n.getContentDescription().toString();
                }
                int p = parseDigits(txt);
                if (p > 0) {
                    bestPrice = p;
                    break;
                }
                // Check sibling nodes in parent if isolated "₹"
                AccessibilityNodeInfo pNode = n.getParent();
                if (pNode != null) {
                    for (int i = 0; i < pNode.getChildCount(); i++) {
                        AccessibilityNodeInfo sib = pNode.getChild(i);
                        if (sib != null && sib != n) {
                            String sTxt = sib.getText() != null ? sib.getText().toString() : "";
                            int sp = parseDigits(sTxt);
                            if (sp > 0) {
                                bestPrice = sp;
                                break;
                            }
                        }
                    }
                }
                if (bestPrice > 0) break;
            }
        }

        // Method 2: Regex on full text
        if (bestPrice == 0 && fullText != null) {
            Pattern p = Pattern.compile("(?:₹|rs\\.?|inr)\\s*([0-9]+)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(fullText);
            if (m.find()) {
                try {
                    bestPrice = Integer.parseInt(m.group(1));
                } catch (Exception ignored) {}
            }
        }

        return bestPrice;
    }

    private int parseDigits(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
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

    private DropAreaResult checkDropArea(String fullLower, List<String> areasToCheck) {
        String dropKm = "--";
        String matchedArea = "";
        boolean isMatched = false;

        int kmIdx = fullLower.indexOf("km");
        if (kmIdx != -1) {
            int start = Math.max(0, kmIdx - 5);
            dropKm = fullLower.substring(start, kmIdx + 2).trim();
        }

        for (String a : areasToCheck) {
            String clean = a.trim();
            if (!clean.isEmpty() && fullLower.contains(clean.toLowerCase())) {
                matchedArea = clean;
                isMatched = true;
                break;
            }
        }

        return new DropAreaResult(isMatched, matchedArea, dropKm);
    }

    private static class DropAreaResult {
        boolean matched;
        String areaName;
        String dropKm;
        DropAreaResult(boolean m, String a, String k) {
            matched = m; areaName = a; dropKm = k;
        }
    }

    private AccessibilityNodeInfo findSkipButton(AccessibilityNodeInfo root) {
        if (root == null) return null;
        String[] skipWords = new String[] { "-", "Reject", "Skip", "REJECT", "SKIP", "மறு" };
        for (String w : skipWords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(w);
            if (nodes != null && !nodes.isEmpty()) {
                return nodes.get(0);
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
            // 50ms solid touch duration for 100% Android recognition
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
            // 120ms duration smooth swipe across slider track
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private void playBeepOrVibrate() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
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
