package com.framecut.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.framecut.app.ui.FrameCutApp
import com.framecut.app.ui.theme.FrameCutTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Anything left in the cache by a previous run is dead weight on a
        // device that has just been asked to hold a 2 GB download.
        viewModel.pruneCache()
        handleRedirect(intent)
        ensureLegacyStoragePermission()

        setContent {
            FrameCutTheme {
                FrameCutApp(viewModel)
            }
        }
    }

    /** singleTask: the OAuth redirect arrives here rather than in a new Activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (viewModel.isRedirect(data)) viewModel.onRedirect(data)
    }

    /** "Save to device" writes the public Movies directory directly before API 29. */
    private fun ensureLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
