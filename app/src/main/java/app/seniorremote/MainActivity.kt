package app.seniorremote

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.seniorremote.diagnostics.Metric
import app.seniorremote.ui.AppRoot
import app.seniorremote.ui.theme.SeniorRemoteTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SeniorRemoteApp).container

    /** When this launch was requested; used for the "remote visible" timing. */
    private var launchStartedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a launch that opens straight onto the remote is a trial; first-time setup is not.
        launchStartedAt = when {
            container.repository.library.value.selected == null -> null
            firstActivityInProcess -> Process.getStartElapsedRealtime()
            else -> SystemClock.elapsedRealtime()
        }
        firstActivityInProcess = false
        enableEdgeToEdge()
        setContent {
            SeniorRemoteTheme {
                AppRoot(container, onRemoteFirstFrame = ::recordRemoteVisible)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.connection.onForeground()
    }

    override fun onStop() {
        // Leaving the foreground (including screen lock) stops any held-button repetition at once.
        container.dispatcher.cancelAll()
        container.connection.onBackground()
        container.persistDiagnostics()
        super.onStop()
    }

    private fun recordRemoteVisible() {
        val started = launchStartedAt ?: return
        launchStartedAt = null
        container.performance.record(Metric.LAUNCH_TO_REMOTE_VISIBLE, SystemClock.elapsedRealtime() - started)
    }

    private companion object {
        var firstActivityInProcess = true
    }
}
