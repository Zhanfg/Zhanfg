package cc.axymorrsen.phigrosextractor

import com.chaquo.python.android.PyApplication

class ExtractorApplication : PyApplication() {
    override fun onCreate() {
        super.onCreate()

        // Recovery builds default to keeping a user-visible OGG copy after a
        // successful conversion. Heavy old-cache recovery is deliberately done
        // by RecoveryActivity on a worker thread, never in Application.onCreate.
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (!prefs.contains("keep_ogg")) {
            prefs.edit().putBoolean("keep_ogg", true).apply()
        }
    }
}
