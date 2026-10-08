package cc.axymorrsen.amtoolnext.hook

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps only the active lyrics ViewModel weakly, so preference changes can reload the
 * visible lyrics without restarting Apple Music. Hook code itself remains installed once.
 */
internal object LyricsHotReload {
    private val target = AtomicReference<WeakReference<Any>?>(null)
    private val lastArgs = AtomicReference<Array<Any?>?>(null)
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var loadMethod: Method? = null

    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    fun bind(method: Method) {
        loadMethod = method
    }

    fun capture(instance: Any?, args: Array<Any?>) {
        if (instance != null) target.set(WeakReference(instance))
        lastArgs.set(args.copyOf())
    }

    fun requestRefresh(log: (String) -> Unit) {
        val method = loadMethod ?: return
        val instance = target.get()?.get() ?: return
        val args = lastArgs.get()?.copyOf() ?: return
        mainHandler.post {
            if (!refreshing.compareAndSet(false, true)) return@post
            try {
                method.invoke(instance, *args)
                log("lyrics hot reload applied")
            } catch (error: Throwable) {
                log("lyrics hot reload skipped: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                refreshing.set(false)
            }
        }
    }
}
