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

    /** v3.3: take the photo automatically when the page has been held steady in the live preview. */
    var autoCapture: Boolean
        get() = sp.getBoolean("auto_capture", true)
        set(v) { sp.edit().putBoolean("auto_capture", v).apply() }

    /** Whiten fingers that hold the page (on by default). */
    var removeFingers: Boolean
        get() = sp.getBoolean("remove_fingers", true)
        set(v) { sp.edit().putBoolean("remove_fingers", v).apply() }

    /** v3.4.3 document list order: 0 = newest, 1 = oldest, 2 = name A-Z, 3 = name Z-A, 4 = largest, 5 = smallest */
    var sortMode: Int
        get() = sp.getInt("sort_mode", 0)
        set(v) { sp.edit().putInt("sort_mode", v).apply() }

    /** v3.7 ID screen: last kind (IdKind ordinal), watermark on/off and the last purpose typed. */
    var idKind: Int
        get() = sp.getInt("id_kind", 0)
        set(v) { sp.edit().putInt("id_kind", v).apply() }
    var idWatermarkOn: Boolean
        get() = sp.getBoolean("id_wm_on", false)
        set(v) { sp.edit().putBoolean("id_wm_on", v).apply() }
    var idPurpose: String
        get() = sp.getString("id_purpose", "") ?: ""
        set(v) { sp.edit().putString("id_purpose", v).apply() }

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) { sp.edit().putBoolean("app_lock", v).apply() }
}
