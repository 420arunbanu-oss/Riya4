package com.example.rapido;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 50, 40, 50);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("⚡ Rapido Auto Clicker");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("வீடியோ பார்த்துக்கொண்டிருக்கும் போதும் ஆர்டர் வந்த உடனே நொடியில் கிளிக் செய்து ஏற்கும் (100% Free)");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setPadding(0, 10, 0, 35);
        root.addView(subtitle);

        // Master Switch
        boolean isAutoOn = prefs.getBoolean("is_auto_accept_on", true);
        Switch masterSwitch = new Switch(this);
        masterSwitch.setText("⚡ ஆட்டோ-அக்செப்ட் சுவிட்ச் (Master Auto-Accept)");
        masterSwitch.setTextSize(17);
        masterSwitch.setTextColor(Color.WHITE);
        masterSwitch.setChecked(isAutoOn);
        masterSwitch.setPadding(30, 35, 30, 35);
        masterSwitch.setBackgroundColor(Color.parseColor("#1E293B"));
        masterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("is_auto_accept_on", isChecked).apply();
            }
        });
        root.addView(masterSwitch);

        // Step 1: Accessibility Button
        Button accessibilityBtn = new Button(this);
        accessibilityBtn.setText("1. Accessibility அனுமதி ஆன் செய்க (Accessibility Service)");
        accessibilityBtn.setBackgroundColor(Color.parseColor("#10B981"));
        accessibilityBtn.setTextColor(Color.BLACK);
        accessibilityBtn.setTextSize(14);
        accessibilityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        LinearLayout.LayoutParams btnParams1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams1.setMargins(0, 30, 0, 15);
        accessibilityBtn.setLayoutParams(btnParams1);
        root.addView(accessibilityBtn);

        // Step 2: Floating Switch Button
        Button overlayBtn = new Button(this);
        overlayBtn.setText("2. மிதக்கும் சுவிட்ச் ஆன் செய்க (Floating Switch over Video)");
        overlayBtn.setBackgroundColor(Color.parseColor("#3B82F6"));
        overlayBtn.setTextColor(Color.WHITE);
        overlayBtn.setTextSize(14);
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    startService(new Intent(MainActivity.this, FloatingSwitchService.class));
                }
            }
        });
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams2.setMargins(0, 0, 0, 15);
        overlayBtn.setLayoutParams(btnParams2);
        root.addView(overlayBtn);

        // Step 3: Battery Optimization Button
        Button batteryBtn = new Button(this);
        batteryBtn.setText("3. பேட்டரி சேவர் விலக்கு (Battery Optimization Settings)");
        batteryBtn.setBackgroundColor(Color.parseColor("#F59E0B"));
        batteryBtn.setTextColor(Color.BLACK);
        batteryBtn.setTextSize(14);
        batteryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } else {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    }
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });
        LinearLayout.LayoutParams btnParams3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams3.setMargins(0, 0, 0, 30);
        batteryBtn.setLayoutParams(btnParams3);
        root.addView(batteryBtn);

        // Info TextView
        TextView infoBox = new TextView(this);
        infoBox.setText("💡 பயன்பாட்டு குறிப்புகள்:\n\n• நீங்கள் வீடியோ பார்த்துக் கொண்டிருக்கும் போதும், வாட்ஸ்அப்பில் இருக்கும் போதும் Rapido Captain ஆப்பில் ஆர்டர் வந்த உடனே 0.1 நொடியில் தானாக கிளிக் செய்து ஏற்கும்.\n\n• குறைந்தபட்ச விலை போன்ற எந்த குழப்பமும் இல்லை; சுவிட்ச் ஆன் செய்திருந்தால் அனைத்து ஆர்டர்களும் உடனே ஏற்கப்படும்.\n\n• திரையின் மேல் உள்ள மிதக்கும் சுவிட்சை (Floating Switch) தொட்டு எப்போது வேண்டுமானாலும் ON/OFF செய்து கொள்ளலாம்.");
        infoBox.setTextColor(Color.parseColor("#94A3B8"));
        infoBox.setTextSize(13);
        infoBox.setPadding(25, 25, 25, 25);
        infoBox.setBackgroundColor(Color.parseColor("#1E293B"));
        root.addView(infoBox);

        setContentView(scroll);
    }
}
