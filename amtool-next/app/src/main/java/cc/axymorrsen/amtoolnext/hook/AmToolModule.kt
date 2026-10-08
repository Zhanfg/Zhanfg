package cc.axymorrsen.amtoolnext.hook

import android.content.SharedPreferences
import android.util.Log
import cc.axymorrsen.amtoolnext.config.ConfigCodec
import cc.axymorrsen.amtoolnext.config.ConfigKeys
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.runtime.AmToolRuntimeV3
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

class AmToolModule : XposedModule() {
    private val installed = AtomicBoolean(false)
    private var remotePreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            val prefs = getRemotePreferences(ConfigKeys.GROUP)
            remotePreferences = prefs
            HookConfigRuntime.update(ConfigCodec.read(prefs))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
                val next = ConfigCodec.read(changed)
                if (HookConfigRuntime.update(next)) {
                    log(Log.INFO, TAG, "hot reload revision=${next.revision}")
                }
            }
            preferenceListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
            log(Log.INFO, TAG, "configuration attached revision=${HookConfigRuntime.revision()}")
        }.onFailure {
            log(Log.ERROR, TAG, "RemotePreferences unavailable; defaults active", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != AppleMusic653.PACKAGE || !param.isFirstPackage) return
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            AmToolRuntimeV3(this, param.classLoader).install()
        }.onFailure {
            log(Log.ERROR, TAG, "runtime v3 installation failed", it)
        }
    }

    companion object {
        private const val TAG = "AMToolNextV3"
    }
}
