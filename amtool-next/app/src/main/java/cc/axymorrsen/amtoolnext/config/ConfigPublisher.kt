package cc.axymorrsen.amtoolnext.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicLong

object ConfigPublisher {
    private lateinit var local: SharedPreferences
    private var remote: SharedPreferences? = null
    private val nextRevision = AtomicLong(0L)
    private var initialized = false
    private var internalWrite = false

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (internalWrite) return@OnSharedPreferenceChangeListener
        if (key != ConfigKeys.REVISION) bumpAndPublish() else publish()
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        local = context.getSharedPreferences(ConfigKeys.LOCAL, Context.MODE_PRIVATE)
        if (!local.contains(ConfigKeys.REVISION)) {
            internalWrite = true
            ConfigCodec.write(local.edit(), HookConfig()).commit()
            internalWrite = false
        }
        nextRevision.set(local.getLong(ConfigKeys.REVISION, 0L))
        local.registerOnSharedPreferenceChangeListener(localListener)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                remote = service.getRemotePreferences(ConfigKeys.GROUP)
                publish()
            }

            override fun onServiceDied(service: XposedService) {
                remote = null
            }
        })
    }

    fun snapshot(): HookConfig = ConfigCodec.read(local)

    fun update(transform: (HookConfig) -> HookConfig) {
        val now = snapshot()
        val next = transform(now).copy(revision = Math.addExact(now.revision, 1L))
        nextRevision.set(next.revision)
        internalWrite = true
        ConfigCodec.write(local.edit(), next).commit()
        internalWrite = false
        publish()
    }

    private fun bumpAndPublish() {
        val rev = Math.addExact(nextRevision.get(), 1L)
        nextRevision.set(rev)
        internalWrite = true
        local.edit().putLong(ConfigKeys.REVISION, rev).commit()
        internalWrite = false
        publish()
    }

    private fun publish() {
        val target = remote ?: return
        ConfigCodec.write(target.edit().clear(), snapshot()).commit()
    }
}
