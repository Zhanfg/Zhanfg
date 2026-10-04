package cc.axymorrsen.amtoolnext.compat

import com.highcapable.yukihookapi.YukiHookAPI

/**
 * AMTool 1.x compatibility marker.
 * YukiHookAPI 1.3.2 is retained for migration of legacy finders, but is not registered
 * as another Xposed entry. libxposed API 102 exclusively owns the hook lifecycle.
 */
object YukiCompat {
    const val EXPECTED_VERSION = "1.3.2"
    val apiClassName: String get() = YukiHookAPI::class.java.name
}
