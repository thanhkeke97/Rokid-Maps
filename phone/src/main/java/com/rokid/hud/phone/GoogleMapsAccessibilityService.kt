package com.rokid.hud.phone

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GoogleMapsAccessibilityService : AccessibilityService() {

    companion object {
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val PREFS_HUD = "rokid_hud_prefs"
        private const val PREF_GOOGLE_MAPS_MODE = "google_maps_mode"
        private const val PREF_LAST_GMAPS_DEBUG = "last_gmaps_debug"
    }

    private var streamingService: HudStreamingService? = null
    private var bound = false
    private var lastSignature = ""
    private var lastUpdateMs = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            streamingService = (service as HudStreamingService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bindService(Intent(this, HudStreamingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isGoogleMapsModeEnabled()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != GOOGLE_MAPS_PACKAGE) return

        val root = rootInActiveWindow ?: event.source ?: return
        val candidates = linkedSetOf<String>()
        collectTexts(root, candidates)
        event.text?.forEach { text ->
            text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(candidates::add)

        val lines = candidates.toList()
        if (lines.isEmpty()) return

        val parsed = GoogleMapsNotificationParser.parseTexts(lines) ?: return
        val signature = "${parsed.instruction}|${parsed.maneuver}|${parsed.distanceMeters.toInt()}"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastUpdateMs < 1500L) return
        lastSignature = signature
        lastUpdateMs = now

        saveDebug(
            "source=accessibility | parsedInstruction=${parsed.instruction} | parsedManeuver=${parsed.maneuver} | parsedDistance=${parsed.distanceMeters.toInt()} | texts=${lines.joinToString(" || ")}"
        )
        streamingService?.updateExternalNavigationStep(parsed.instruction, parsed.maneuver, parsed.distanceMeters)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int = 0) {
        if (depth > 14) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTexts(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isGoogleMapsModeEnabled(): Boolean {
        return getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .getBoolean(PREF_GOOGLE_MAPS_MODE, false)
    }

    private fun saveDebug(value: String) {
        getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_GMAPS_DEBUG, value)
            .apply()
    }
}
