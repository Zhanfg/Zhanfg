package cc.axymorrsen.amtoolnext.runtime

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Single installation root for Apple Music runtime domains.
 *
 * Invariants:
 * 1. playback/account/entitlement traffic is never rewritten;
 * 2. localized catalog traffic is module-owned and token-routed at exact request executors;
 * 3. lyrics language and metadata localization are independent domains;
 * 4. display overlay never mutates canonical Apple model identity or playParams.
 */
internal class AmToolRuntimeV3(
    private val module: XposedModule,
    private val loader: ClassLoader,
) {
    fun install() {
        val logger: (Int, String, Throwable?) -> Unit = { priority, message, error ->
            if (error == null) {
                module.log(priority, TAG, message)
            } else {
                module.log(priority, TAG, message, error)
            }
        }

        RuntimeSignal.once("runtime-installed", "AMTool V3 已注入 Apple Music")

        val catalog = CatalogSideChannel(module, loader, logger)
        catalog.installRouter()

        MetadataOverlayRuntime(
            module = module,
            loader = loader,
            catalog = catalog,
            logger = logger,
        ).install()

        LyricsRuntime(
            module = module,
            loader = loader,
            logger = logger,
        ).install()

        logger(Log.INFO, "AMTool runtime v3 alpha5 installed", null)
    }

    companion object {
        private const val TAG = "AMToolNextV3"
    }
}
