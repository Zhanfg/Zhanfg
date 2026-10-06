package cc.axymorrsen.amtoolnext.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary alpha7 device probe.
 *
 * We need one deterministic signal per stage because LSPosed hook installation can succeed while
 * an Apple Music 6.5.3 consumption seam never executes. This avoids asking the user for logcat.
 */
internal object RuntimeSignal {
    private val shown = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    fun once(key: String, message: String) {
        if (!shown.add(key)) return
        val app = currentApplication() ?: return
        main.post {
            runCatching {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun currentApplication(): Application? = runCatching {
        val type = Class.forName("android.app.ActivityThread")
        val method: Method = type.getDeclaredMethod("currentApplication").apply {
            isAccessible = true
        }
        method.invoke(null) as? Application
    }.getOrNull()
}
