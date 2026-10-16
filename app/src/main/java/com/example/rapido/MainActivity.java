package com.example.rapido;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public static final String FIREBASE_URL = "https://nuraautoclicker-default-rtdb.firebaseio.com";
    public static final String WHATSAPP_NUMBER = "917708271310";
    public static final String ADMIN_EMAIL = "420arunbanu@gmail.com";
    public static final String UPI_VPA = "paytm.s1kt7kz@pty";

    private static MainActivity instance;
    private WebView webView;
    private SharedPreferences storage;
    private Handler mainHandler;
    private ExecutorService networkExecutor;
    private Thread killSwitchThread;

    public static MainActivity getInstance() {
        return instance;
    }

    public static void syncStaticUI() {
        if (instance != null) {
            instance.syncUIState();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        networkExecutor = Executors.newFixedThreadPool(4);
        storage = getSharedPreferences("PREMIUM_LOGIN_DATA", Context.MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0D121F"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);
        // Native touch handling enabled
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Chrome client handles javascript alerts and dialogs
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                showToast(message);
                result.confirm();
                return true;
            }
        });

        // WebViewClient handles external links (WhatsApp, Payments, Browser)
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null) {
                    if (url.startsWith("mailto:")) {
                        String to = url.substring(7);
                        int qIdx = to.indexOf("?");
                        if (qIdx > 0) to = to.substring(0, qIdx);
                        openEmailApp(to, "Nura Auto Clicker Driver Request");
                        return true;
                    }
                    if (url.startsWith("whatsapp://") || url.contains("api.whatsapp.com") || url.contains("wa.me")) {
                        openWhatsAppFromUrl(url);
                        return true;
                    }
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("intent://") || url.startsWith("upi://")) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            return true;
                        } catch (Exception ignored) {}
                    }
                }
                return false;
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(), "autoxBridge");
        webView.loadUrl("file:///android_asset/ui.html");

        // Sync initial state after page loads
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncUIState();
                String email = storage.getString("email", "");
                if (!email.isEmpty()) {
                    startRemoteKillSwitch(email);
                    fetchPermissionsFromFirebase(email);
                }
            }
        }, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUIState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (killSwitchThread != null && killSwitchThread.isAlive()) {
            killSwitchThread.interrupt();
        }
        if (instance == this) instance = null;
    }

    public String getSafeAndroidId() {
        try {
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 3) return id;
        } catch (Exception ignored) {}
        String alt = storage.getString("fallback_device_id", "");
        if (alt.isEmpty()) {
            alt = "DEV_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 899999 + 100000);
            storage.edit().putString("fallback_device_id", alt).apply();
        }
        return alt;
    }

    private String getFirebaseEmailKey(String email) {
        if (email == null) return "";
        return email.trim().toLowerCase().replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String httpGet(String path) {
        try {
            String fullUrl = FIREBASE_URL + (path.startsWith("/") ? path : "/" + path);
            if (!fullUrl.endsWith(".json")) fullUrl += ".json";
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean httpPut(String path, String jsonBody) {
        try {
            String fullUrl = FIREBASE_URL + (path.startsWith("/") ? path : "/" + path);
            if (!fullUrl.endsWith(".json")) fullUrl += ".json";
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
            os.close();
            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 300;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean httpPatch(String path, String jsonBody) {
        try {
            String fullUrl = FIREBASE_URL + (path.startsWith("/") ? path : "/" + path);
            if (!fullUrl.endsWith(".json")) fullUrl += ".json";
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            byte[] bytes = jsonBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 300;
        } catch (Exception ignored) {}
        return false;
    }

    private void startRemoteKillSwitch(final String email) {
        if (killSwitchThread != null && killSwitchThread.isAlive()) {
            killSwitchThread.interrupt();
        }
        killSwitchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(30000);
                        String emailKey = getFirebaseEmailKey(email);
                        String resStr = httpGet("/users/" + emailKey);
                        if (resStr == null || resStr.equals("null")) {
                            resStr = httpGet("/" + emailKey);
                        }
                        if (resStr == null || resStr.equals("null")) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    logoutAction();
                                    showToast("⚠️ Account deleted by Admin.");
                                }
                            });
                            break;
                        }
                        JSONObject data = new JSONObject(resStr);
                        long exp = data.optLong("expiryDate", 0);
                        if (exp > 0 && System.currentTimeMillis() >= exp) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    stopAutoClicker();
                                    syncUIState();
                                    showToast("🚫 Plan Expired!");
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {}
                }
            }
        });
        killSwitchThread.start();
    }

    private void fetchPermissionsFromFirebase(final String email) {
        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String emailKey = getFirebaseEmailKey(email);
                    String resStr = httpGet("/users/" + emailKey);
                    if (resStr == null || resStr.equals("null")) {
                        resStr = httpGet("/" + emailKey);
                    }
                    if (resStr != null && !resStr.equals("null")) {
                        JSONObject d = new JSONObject(resStr);
                        storage.edit()
                                .putBoolean("allow_area_perm", d.optBoolean("allowArea", true))
                                .putBoolean("allow_price_perm", d.optBoolean("allowPrice", true))
                                .putInt("driver_index", d.optInt("driverIndex", 1))
                                .apply();
                        syncUIState();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void toggleAutoClicker() {
        boolean running = storage.getBoolean("isRunning", false);
        if (!running) {
            boolean hasAcc = RapidoAutoClickService.isServiceRunning;
            if (!hasAcc) {
                showToast("🔑 Please enable Accessibility Service first!");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            storage.edit().putBoolean("isRunning", true).apply();
            RapidoAutoClickService.isAutoAcceptOn = true;

            // Start floating foreground service
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                Intent sIntent = new Intent(this, FloatingSwitchService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(sIntent);
                } else {
                    startService(sIntent);
                }
            }
            showToast("🟢 Engine is ONLINE");
        } else {
            stopAutoClicker();
            showToast("🛑 Auto Clicker Stopped");
        }
        syncUIState();
    }

    private void stopAutoClicker() {
        storage.edit().putBoolean("isRunning", false).apply();
        RapidoAutoClickService.isAutoAcceptOn = false;
        try {
            stopService(new Intent(this, FloatingSwitchService.class));
        } catch (Exception ignored) {}
    }

    private void saveFilters(JSONObject data) {
        SharedPreferences.Editor ed = storage.edit();
        ed.putBoolean("usePriceFilter", data.optBoolean("usePriceFilter"));
        ed.putBoolean("useSkipBelowMin", data.optBoolean("useSkipBelowMin"));
        ed.putBoolean("useAreaFilter", data.optBoolean("useAreaFilter"));
        ed.putString("customAreaSearchStr", data.optString("customAreaSearchStr"));
        ed.putInt("minPrice", data.optInt("minPrice", 40));
        ed.putInt("maxPrice", data.optInt("maxPrice", 10000));

        JSONArray sel = data.optJSONArray("selectedAreas");
        if (sel != null) ed.putString("selectedAreasJson", sel.toString());

        JSONArray custom = data.optJSONArray("customAreas");
        if (custom != null) ed.putString("customAreasJson", custom.toString());

        ed.apply();
        showToast("✅ Filters saved successfully!");
        syncUIState();
    }

    private void logoutAction() {
        stopAutoClicker();
        storage.edit().clear().apply();
        showToast("🚪 Logged out successfully");
        syncUIState();
    }

    public void syncUIState() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String email = storage.getString("email", "");
                    boolean approved = storage.getBoolean("is_approved", false);
                    long expMs = storage.getLong("firebase_expiry_ms", 0);
                    boolean isExpired = expMs > 0 && System.currentTimeMillis() >= expMs;

                    String viewStr = "LOGIN";
                    if (!email.isEmpty() && approved && !isExpired) {
                        viewStr = "DASHBOARD";
                    } else if (!email.isEmpty()) {
                        viewStr = "WAIT";
                    }

                    boolean hasAcc = RapidoAutoClickService.isServiceRunning;
                    boolean hasOvl = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this);
                    boolean hasBat = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null) hasBat = pm.isIgnoringBatteryOptimizations(getPackageName());
                    }

                    long diff = expMs - System.currentTimeMillis();
                    long daysLeft = expMs > 0 ? (long) Math.ceil((double) diff / (1000 * 60 * 60 * 24)) : 0;
                    if (daysLeft < 0) daysLeft = 0;

                    JSONObject p = new JSONObject();
                    p.put("view", viewStr);
                    p.put("email", email);
                    p.put("hasAccessibility", hasAcc);
                    p.put("hasOverlay", hasOvl);
                    p.put("hasBattery", hasBat);
                    p.put("androidId", getSafeAndroidId());
                    p.put("driverIndex", storage.getInt("driver_index", 1));
                    p.put("vehicleType", storage.getString("userVehicleType", "Auto"));
                    p.put("status", (approved && !isExpired) ? "APPROVED" : "PENDING");
                    p.put("daysLeft", daysLeft + " DAYS LEFT");
                    p.put("expiresOn", "--/--/----");
                    p.put("planName", storage.getString("active_plan_name", "PREMIUM"));
                    p.put("planAmount", 0);
                    p.put("isRunning", storage.getBoolean("isRunning", false));
                    p.put("lastPrice", storage.getString("last_order_price", "Rs. 0"));
                    p.put("lastArea", storage.getString("last_order_area", "None"));
                    p.put("lastTime", storage.getString("last_order_time", "--:--"));
                    p.put("todayEarnings", "Rs. " + storage.getInt("today_earnings", 0));
                    p.put("todayOrdersCount", storage.getInt("today_orders_count", 0) + " Orders Completed");
                    p.put("allowArea", storage.getBoolean("allow_area_perm", true));
                    p.put("allowPrice", storage.getBoolean("allow_price_perm", true));
                    p.put("usePriceFilter", storage.getBoolean("usePriceFilter", false));
                    p.put("useSkipBelowMin", storage.getBoolean("useSkipBelowMin", false));
                    p.put("useAreaFilter", storage.getBoolean("useAreaFilter", false));
                    p.put("minPrice", storage.getInt("minPrice", 40));
                    p.put("maxPrice", storage.getInt("maxPrice", 10000));

                    String selJson = storage.getString("selectedAreasJson", "[]");
                    p.put("selectedAreas", new JSONArray(selJson));

                    String customJson = storage.getString("customAreasJson", "[]");
                    p.put("customAreas", new JSONArray(customJson));

                    p.put("hasUpdate", false);
                    p.put("updateTxt", "V48.0 LATEST ✓");

                    String js = "updateDashboard(" + p.toString() + ");";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        webView.evaluateJavascript(js, null);
                    } else {
                        webView.loadUrl("javascript:" + js);
                    }
                } catch (Exception ignored) {}
            }
        });
    }
}
