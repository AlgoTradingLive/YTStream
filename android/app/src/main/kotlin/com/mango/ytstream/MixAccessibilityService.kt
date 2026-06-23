package com.mango.ytstream

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MixAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MixAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
