package cc.axymorrsen.amtoolnext.config

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ConfigKeys {
    const val GROUP = "amtool_next_configuration"
    const val LOCAL = "amtool_next_local"
    const val ENABLED = "enabled"
    const val CHINESE_LYRICS = "chinese_lyrics"
    const val AUTO_TRANSLATION = "auto_translation"
    const val PRONUNCIATION = "pronunciation"
    const val CHINESE_METADATA = "chinese_metadata"
    const val FLOATING_BOTTOM_BAR = "floating_bottom_bar"
    const val REVISION = "__revision"
}

data class HookConfig(
    val enabled: Boolean = true,
    val chineseLyrics: Boolean = true,
    val autoTranslation: Boolean = true,
    val pronunciation: Boolean = false,
    val chineseMetadata: Boolean = true,
    val floatingBottomBar: Boolean = true,
    val revision: Long = 0,
)

object ConfigCodec {
    fun read(prefs: SharedPreferences): HookConfig = HookConfig(
        enabled = prefs.getBoolean(ConfigKeys.ENABLED, true),
        chineseLyrics = prefs.getBoolean(ConfigKeys.CHINESE_LYRICS, true),
        autoTranslation = prefs.getBoolean(ConfigKeys.AUTO_TRANSLATION, true),
        pronunciation = prefs.getBoolean(ConfigKeys.PRONUNCIATION, false),
        chineseMetadata = prefs.getBoolean(ConfigKeys.CHINESE_METADATA, true),
        floatingBottomBar = prefs.getBoolean(ConfigKeys.FLOATING_BOTTOM_BAR, true),
        revision = prefs.getLong(ConfigKeys.REVISION, 0L),
    )

    fun write(editor: SharedPreferences.Editor, config: HookConfig): SharedPreferences.Editor = editor
        .putBoolean(ConfigKeys.ENABLED, config.enabled)
        .putBoolean(ConfigKeys.CHINESE_LYRICS, config.chineseLyrics)
        .putBoolean(ConfigKeys.AUTO_TRANSLATION, config.autoTranslation)
        .putBoolean(ConfigKeys.PRONUNCIATION, config.pronunciation)
        .putBoolean(ConfigKeys.CHINESE_METADATA, config.chineseMetadata)
        .putBoolean(ConfigKeys.FLOATING_BOTTOM_BAR, config.floatingBottomBar)
        .putLong(ConfigKeys.REVISION, config.revision)
}

object HookConfigRuntime {
    private val state = AtomicReference(HookConfig())
    private val appliedRevision = AtomicLong(-1L)

    fun current(): HookConfig = state.get()

    fun update(config: HookConfig): Boolean {
        val previous = state.getAndSet(config)
        appliedRevision.set(config.revision)
        return previous != config
    }

    fun revision(): Long = appliedRevision.get()
}
