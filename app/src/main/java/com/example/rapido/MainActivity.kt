package com.example.rapido

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 50)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "⚡ Rapido Auto Clicker"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "வீடியோ பார்த்துக்கொண்டிருக்கும் போதும் ஆர்டர் வந்த உடனே நொடியில் கிளிக் / ஸ்வைப் செய்து ஏற்கும் (100% Free)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 10, 0, 35)
        }
        root.addView(subtitle)

        // Master ON/OFF Switch
        var isAutoOn = prefs.getBoolean("is_auto_accept_on", true)
        val masterSwitch = Switch(this).apply {
            text = "⚡ ஆட்டோ-அக்செப்ட் சுவிட்ச் (Master Auto-Accept)"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = isAutoOn
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                isAutoOn = checked
                prefs.edit().putBoolean("is_auto_accept_on", checked).apply()
            }
            setPadding(30, 35, 30, 35)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        root.addView(masterSwitch)

        // Step 1: Accessibility
        val accessibilityBtn = Button(this).apply {
            text = "1. Accessibility அனுமதி ஆன் செய்க (Accessibility Service)"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.BLACK)
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 30, 0, 15) }
        }
        root.addView(accessibilityBtn)

        // Step 2: Floating Switch over Video
        val overlayBtn = Button(this).apply {
            text = "2. மிதக்கும் சுவிட்ச் ஆன் செய்க (Floating Switch over Video)"
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    startService(Intent(this@MainActivity, FloatingSwitchService::class.java))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 15) }
        }
        root.addView(overlayBtn)

        // Step 3: Battery Optimization Settings
        val batteryBtn = Button(this).apply {
            text = "3. பேட்டரி சேவர் விலக்கு (Battery Optimization Settings)"
            setBackgroundColor(Color.parseColor("#F59E0B"))
            setTextColor(Color.BLACK)
            textSize = 14f
            setOnClickListener {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } else {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    }
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 30) }
        }
        root.addView(batteryBtn)

        // Info box
        val infoBox = TextView(this).apply {
            text = "💡 பயன்பாட்டு குறிப்புகள்:

• நீங்கள் வீடியோ பார்த்துக் கொண்டிருக்கும் போதும், வாட்ஸ்அப்பில் இருக்கும் போதும் Rapido Captain ஆப்பில் ஆர்டர் வந்த உடனே 0.1 நொடியில் தானாக கிளிக் / ஸ்வைப் செய்து ஏற்கும்.

• குறைந்தபட்ச விலை போன்ற எந்த குழப்பமும் இல்லை; சுவிட்ச் ஆன் செய்திருந்தால் அனைத்து ஆர்டர்களும் உடனே ஏற்கப்படும்.

• திரையின் மேல் உள்ள மிதக்கும் சுவிட்சை (Floating Switch) தொட்டு எப்போது வேண்டுமானாலும் ON/OFF செய்து கொள்ளலாம்."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(25, 25, 25, 25)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        root.addView(infoBox)

        setContentView(scroll)
    }
}
