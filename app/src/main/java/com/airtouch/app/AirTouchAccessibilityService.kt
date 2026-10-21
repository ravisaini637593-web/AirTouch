package com.airtouch.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/** Performs only the user-requested scroll action in the currently active app. */
class AirTouchAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectScrollable(root, candidates)
        return candidates.any { node -> node.performAction(action) }
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable) result += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectScrollable(it, result) }
        }
    }

    companion object {
        @Volatile private var instance: AirTouchAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val expected = ComponentName(context, AirTouchAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun scroll(forward: Boolean): Boolean = instance?.scroll(forward) ?: false
    }
}
