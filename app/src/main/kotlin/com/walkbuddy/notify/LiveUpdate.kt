package com.walkbuddy.notify

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * Android 16 "progress-centric" live-update styling for the ongoing walk notification.
 *
 * compileSdk is 35, so Notification.ProgressStyle is reached by reflection and only when SDK_INT >= 36. Any failure
 * (missing class, changed signature, OEM quirk) returns the original notification, so the plain ongoing notification
 * is always the fallback. Unverified on a real Android 16 device.
 */
object LiveUpdate {
    private const val EXTRA_PROMOTED = "android.requestPromotedOngoing"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 36

    /** [percent] is 0..100. Returns [base] unchanged when unsupported or on any error. */
    fun style(context: Context, base: Notification, percent: Int, shortText: String?): Notification {
        if (!supported()) return base
        return try {
            val cls = Class.forName("android.app.Notification\$ProgressStyle")
            val style = cls.getConstructor().newInstance()
            cls.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, percent.coerceIn(0, 100))
            cls.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType).invoke(style, true)
            val b = Notification.Builder.recoverBuilder(context, base)
            b.setStyle(style as Notification.Style)
            val extras = Bundle().apply {
                putBoolean(EXTRA_PROMOTED, true)
                if (shortText != null) putCharSequence("android.shortCriticalText", shortText)
            }
            b.addExtras(extras)
            b.build()
        } catch (t: Throwable) {
            base
        }
    }
}
