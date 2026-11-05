package app.seniorremote

import android.app.Application
import android.os.Process

class SeniorRemoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Only a configured app has a "launch to Ready" trial to measure.
        if (container.repository.library.value.selected != null) {
            container.performance.markColdLaunch(Process.getStartElapsedRealtime())
        }
        container.diagnostics.log("app", "Process started")
        container.start()
    }
}
