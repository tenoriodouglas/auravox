package com.auravox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auravox.ui.AuraVoxApp
import com.auravox.ui.AuraVoxTheme
import com.auravox.ui.KaraokeViewModel

class MainActivity : ComponentActivity() {

    private var afterPermission: (() -> Unit)? = null
    private var model: KaraokeViewModel? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Stops the governor from dropping clocks mid-performance, which is a
        // common source of dropouts on long sessions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(true)
        }

        setContent {
            AuraVoxTheme {
                Surface {
                    val vm: KaraokeViewModel = viewModel()
                    model = vm
                    AuraVoxApp(vm) { action -> withMicPermission(action) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model?.resumeIfNeeded()
    }

    /**
     * The mic is released whenever the app leaves the foreground. Holding an
     * exclusive low-latency input stream in the background would block every
     * other app that wants to record, and would be killed anyway.
     */
    override fun onStop() {
        super.onStop()
        model?.stopEngine()
    }

    private fun withMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            action()
        } else {
            afterPermission = action
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
