package com.example.rapido

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RapidoAutoClickService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private var lastClickTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("rapido_auto_prefs", Context.MODE_PRIVATE)
        isServiceRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val isEnabled = prefs.getBoolean("is_auto_accept_on", true)
        if (!isEnabled) return

        val rootNode = rootInActiveWindow ?: return

        val now = System.currentTimeMillis()
        if (now - lastClickTime < 700) return

        val accepted = scanAndTriggerAccept(rootNode)
        if (accepted) {
            lastClickTime = now
            vibratePhone()
        }
    }

    private fun scanAndTriggerAccept(root: AccessibilityNodeInfo): Boolean {
        // Direct Accept Text Search
        val textCandidates = listOf(
            "Accept", "ACCEPT", "accept",
            "ஏற்றுக்கொள்", "स्वीकार करें",
            "Tap to accept", "TAP TO ACCEPT",
            "Accept Order", "ACCEPT ORDER"
        )

        for (candidate in textCandidates) {
            val nodes = root.findAccessibilityNodeInfosByText(candidate)
            if (nodes != null) {
                for (node in nodes) {
                    if (clickByBoundsXY(node)) {
                        return true
                    }
                }
            }
        }

        // Common Rapido Captain View IDs
        val idCandidates = listOf(
            "com.rapido.captain:id/btn_accept",
            "com.rapido.captain:id/accept_order_btn",
            "com.rapido.captain:id/btnAccept",
            "com.rapido.captain:id/accept_button",
            "com.rapido.captain:id/action_button",
            "com.rapido.captain:id/primary_action",
            "com.rapido.rider:id/btn_accept",
            "btn_accept", "accept", "accept_order"
        )

        for (id in idCandidates) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null) {
                for (node in nodes) {
                    if (clickByBoundsXY(node)) {
                        return true
                    }
                }
            }
        }

        // Rupee Symbol ₹ detection - find Accept in active offer card
        val rupeeNodes = root.findAccessibilityNodeInfosByText("₹")
        if (rupeeNodes != null && rupeeNodes.isNotEmpty()) {
            for (rNode in rupeeNodes) {
                var currentParent = rNode.parent
                var depth = 0
                while (currentParent != null && depth < 4) {
                    val rect = Rect()
                    currentParent.getBoundsInScreen(rect)
                    if (currentParent.isClickable && rect.width() > 100) {
                        return clickByBoundsXY(currentParent)
                    }
                    val nextParent = currentParent.parent
                    currentParent = nextParent
                    depth++
                }
            }
        }

        return false
    }

    /**
     * Exact (X, Y) Bounds Millisecond Click:
     * Reads node.getBoundsInScreen(rect) -> calculates exact center (x, y) pixels.
     * Dispatches hardware touch directly to screen coordinates in milliseconds.
     * Also triggers accessibility ACTION_CLICK simultaneously.
     */
    private fun clickByBoundsXY(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        var gestureDispatched = false

        // 1. Direct Hardware Screen (X, Y) Bounds Tap in 50 Milliseconds
        if (rect.width() > 0 && rect.height() > 0) {
            val clickX = rect.centerX().toFloat()
            val clickY = rect.centerY().toFloat()
            gestureDispatched = clickXY(clickX, clickY)

            // If wide button/slider (e.g. "Swipe to Accept" slider), also trigger horizontal swipe gesture!
            if (rect.width() > 180) {
                val startX = rect.left + 50f
                val endX = rect.right - 50f
                val midY = rect.centerY().toFloat()
                swipeXY(startX, midY, endX, midY)
            }
        }

        // 2. Parallel Node Accessibility Click
        var actionClicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val p1 = node.parent
        if (!actionClicked && p1 != null) {
            actionClicked = p1.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val p2 = p1.parent
            if (!actionClicked && p2 != null) {
                actionClicked = p2.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        return gestureDispatched || actionClicked
    }

    private fun clickXY(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun swipeXY(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(180)
            }
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {}

    companion object {
        var isServiceRunning = false
    }
}
