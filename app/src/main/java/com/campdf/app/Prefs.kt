package com.campdf.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("campdf_prefs", Context.MODE_PRIVATE)

    var welcomeSeen: Boolean
        get() = sp.getBoolean("welcome_seen", false)
        set(v) { sp.edit().putBoolean("welcome_seen", v).apply() }

    /** 0 = system, 1 = light, 2 = dark */
    var theme: Int
        get() = sp.getInt("theme", 2)
        set(v) { sp.edit().putInt("theme", v).apply() }

    /** 0 = high, 1 = balanced, 2 = compact */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) { sp.edit().putInt("quality", v).apply() }

    var autoDetect: Boolean
        get() = sp.getBoolean("auto_detect", true)
        set(v) { sp.edit().putBoolean("auto_detect", v).apply() }

    /** Snap pages that are close to A4 proportions to exact A4. */
    var snapA4: Boolean
        get() = sp.getBoolean("snap_a4", true)
        set(v) { sp.edit().putBoolean("snap_a4", v).apply() }

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) { sp.edit().putBoolean("app_lock", v).apply() }
}
