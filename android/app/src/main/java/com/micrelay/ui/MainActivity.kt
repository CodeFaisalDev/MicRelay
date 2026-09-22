package com.micrelay.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.micrelay.core.video.CameraManager
import com.micrelay.service.MicRelayService

class MainActivity : ComponentActivity() {

    private val micRelayService = androidx.compose.runtime.mutableStateOf<MicRelayService?>(null)
    private var isBound = false
    private lateinit var cameraManager: CameraManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MicRelayService.LocalBinder
            val s = binder.getService()
            micRelayService.value = s
            isBound = true
            s.onError = { errorMsg ->
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "MicRelay: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            micRelayService.value = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = CameraManager(this)

        requestRequiredPermissions()

        // Bind to background MicRelayService
        val serviceIntent = Intent(this, MicRelayService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MicRelayTheme {
                val service = micRelayService.value
                HomeScreen(
                    service = service,
                    cameraManager = cameraManager
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        cameraManager.release()
        super.onDestroy()
    }
}
