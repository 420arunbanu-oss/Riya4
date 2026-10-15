package com.example.rapido

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingSwitchService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null
    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingSwitch()
    }

    private fun createFloatingSwitch() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 250
        }

        var isEnabled = prefs.getBoolean("is_auto_accept_on", true)

        floatingView = TextView(this).apply {
            text = if (isEnabled) "⚡ RAPIDO: ON" else "⏸ RAPIDO: OFF"
            setBackgroundColor(if (isEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(36, 24, 36, 24)

            setOnClickListener {
                isEnabled = !isEnabled
                prefs.edit().putBoolean("is_auto_accept_on", isEnabled).apply()
                text = if (isEnabled) "⚡ RAPIDO: ON" else "⏸ RAPIDO: OFF"
                setBackgroundColor(if (isEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            }

            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatingView, params)
                            return true
                        }
                    }
                    return false
                }
            })
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
