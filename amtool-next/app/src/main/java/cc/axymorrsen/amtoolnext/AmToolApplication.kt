package cc.axymorrsen.amtoolnext

import android.app.Application
import cc.axymorrsen.amtoolnext.config.ConfigPublisher

class AmToolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigPublisher.init(this)
    }
}
