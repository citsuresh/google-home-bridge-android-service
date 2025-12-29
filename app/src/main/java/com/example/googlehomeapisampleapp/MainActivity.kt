/* Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.example.googlehomeapisampleapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.googlehomeapisampleapp.service.GhBridgeConstants
import com.example.googlehomeapisampleapp.service.GhBridgeService
import com.example.googlehomeapisampleapp.util.isIgnoringBatteryOptimizations
import com.example.googlehomeapisampleapp.view.HomeAppView
import com.example.googlehomeapisampleapp.viewmodel.HomeAppViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The main activity of the Google Home API Sample App.
 * This activity is responsible for initializing the [HomeApp] and displaying the [HomeAppView].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var homeClientProvider: HomeClientProvider
    lateinit var homeAppVM: HomeAppViewModel

    //<editor-fold desc="GH Bridge Service State">
    private var serviceState by mutableStateOf(GhBridgeConstants.STATE_STOPPED)
    private var serviceInfo by mutableStateOf<String?>(null)

    private var ghBridgeService: GhBridgeService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as GhBridgeService.LocalBinder
            ghBridgeService = binder.getService()
            isBound = true
            ghBridgeService?.homeApp = homeAppVM.homeApp
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }
    //</editor-fold>

    /**
     * Called when the activity is first created.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in [onSaveInstanceState]. Otherwise it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate of MainActivity instance of MainActivity ${this@MainActivity}")
        // Initialize logger for logging and displaying messages:
        logger = Logger(this)

        // Initialize the main app class to interact with the APIs:
        val homeApp = HomeApp(baseContext, lifecycleScope, this, homeClientProvider)
        Log.d(TAG, "homeApp created")
        homeAppVM = HomeAppViewModel(homeApp)
        Log.d(TAG, "homeAppVM created")

        // Get the service state and info
        serviceState = GhBridgeService.serviceState
        serviceInfo = GhBridgeService.lastServiceInfo

        // Call to make the app allocate the entire screen:
        enableEdgeToEdge()
        // Set the content of the screen to display the app:
        setContent {
            BatteryOptimizationDialog()
            HomeAppView(homeAppVM, serviceState, serviceInfo, ::toggleService)
        }

        // Receive the intent extra data to see if it is from AccountSwitchActivity.kt
        val isFromAccountSwitch = intent.getBooleanExtra(EXTRA_FROM_ACCOUNT_SWITCH, false)
        Log.i(TAG, "Launched from account switch: $isFromAccountSwitch")


        if (savedInstanceState != null)
            return
        // Activity is fresh and newly created
        if (isFromAccountSwitch) {
            // After new account signed-in, it still needs to request the permission.
            // When switching to an account gotten permissions before, it needs to wait
            // until the permissions are fully loaded.
            lifecycleScope.launch {
                // Block here until permissionManager is initialized
                homeApp.permissionsManager.isInitialized.first { it }
                if (!homeApp.permissionsManager.isSignedIn.value) {
                    // Try to request the permission
                    Log.d(TAG, "Permissions not granted, requesting permissions")
                    homeApp.permissionsManager.requestPermissions()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service if it is running
        if (serviceState == GhBridgeConstants.STATE_RUNNING) {
            Intent(this, GhBridgeService::class.java).also {
                bindService(it, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GhBridgeConstants.ACTION_SERVICE_STATUS)
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceStatusReceiver, filter)
        // Request initial status
        val requestStatusIntent = Intent(GhBridgeConstants.ACTION_REQUEST_SERVICE_STATUS)
        LocalBroadcastManager.getInstance(this).sendBroadcast(requestStatusIntent)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStatusReceiver)
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    //<editor-fold desc="GH Bridge Service Management">
    private fun toggleService() {
        Log.d(TAG, "Toggle service called. Current state: $serviceState")
        when (serviceState) {
            GhBridgeConstants.STATE_RUNNING -> stopGhBridgeService()
            GhBridgeConstants.STATE_STOPPED, GhBridgeConstants.STATE_FAILED -> startGhBridgeService()
            // Do nothing in intermediate states.
            GhBridgeConstants.STATE_STARTING, GhBridgeConstants.STATE_STOPPING -> { }
        }
    }

    private fun startGhBridgeService() {
        broadcastServiceStatus(GhBridgeConstants.STATE_STARTING)
        val serviceIntent = Intent(this, GhBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Intent(this, GhBridgeService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopGhBridgeService() {
        broadcastServiceStatus(GhBridgeConstants.STATE_STOPPING)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        val serviceIntent = Intent(this, GhBridgeService::class.java)
        stopService(serviceIntent)
    }

    // Setup receiver for service status updates
    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceState = intent.getStringExtra(GhBridgeConstants.EXTRA_SERVICE_STATE) ?: "UNKNOWN"
            serviceInfo = intent.getStringExtra(GhBridgeConstants.EXTRA_SERVICE_INFO)
            val message = serviceInfo ?: serviceState
            showInfo(this, "Service status: $message")
        }
    }

    private fun broadcastServiceStatus(state: String) {
        val intent = Intent(GhBridgeConstants.ACTION_SERVICE_STATUS)
        intent.putExtra(GhBridgeConstants.EXTRA_SERVICE_STATE, state)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    //</editor-fold>

    //<editor-fold desc="Battery Optimization Dialog">
    @Composable
    private fun BatteryOptimizationDialog() {
        var showBatteryOptimizationDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (!isIgnoringBatteryOptimizations(this@MainActivity)) {
                showBatteryOptimizationDialog = true
            }
        }

        if (showBatteryOptimizationDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryOptimizationDialog = false },
                title = { Text("Battery Optimization") },
                text = { Text("To ensure the bridge service runs reliably when the screen is off, please set battery usage to \"Unrestricted\" for this app in the settings.") },
                confirmButton = {
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                        showBatteryOptimizationDialog = false
                    }) { Text("Open Settings") }
                },
                dismissButton = {
                    TextButton(onClick = { showBatteryOptimizationDialog = false }) { Text("Dismiss") }
                }
            )
        }
    }
    //</editor-fold>

    companion object {
        const val TAG = "MainActivity"
        const val EXTRA_FROM_ACCOUNT_SWITCH = "fromAccountSwitch"
        private lateinit var logger: Logger
        /**
         * Shows an error message to the user and logs it.
         *
         * @param caller The object calling this function.
         * @param message The error message to display.
         */
        fun showError(caller: Any, message: String) { logger.log(caller, message, Logger.LogLevel.ERROR) }
        /**
         * Shows a warning message to the user and logs it.
         *
         * @param caller The object calling this afunction.
         * @param message The warning message to display.
         */
        fun showWarning(caller: Any, message: String) { logger.log(caller, message, Logger.LogLevel.WARNING) }
        /**
         * Shows an info message to the user and logs it.
         *
         * @param caller The object calling this function.
         * @param message The info message to display.
         */
        fun showInfo(caller: Any, message: String) { logger.log(caller, message, Logger.LogLevel.INFO) }
        /**
         * Logs a debug message.
         *
         * @param caller The object calling this function.
         * @param message The debug message to log.
         */
        fun showDebug(caller: Any, message: String) { logger.log(caller, message, Logger.LogLevel.DEBUG) }
    }
}

/*  Logger - Utility class for logging and displaying messages
*   This helps us to communicate unexpected states on screen, as well as to record them appropriately
*   so when it comes you to report an issue we can make sure the states are captured in adb logs.
*  */
class Logger (val activity: ComponentActivity) {

    enum class LogLevel {
        ERROR,
        WARNING,
        INFO,
        DEBUG
    }

    fun log (caller: Any, message: String, level: LogLevel) {
        // Log the message in accordance to its level:
        when (level) {
            LogLevel.ERROR -> Log.e(caller.javaClass.name, message)
            LogLevel.WARNING -> Log.w(caller.javaClass.name, message)
            LogLevel.INFO -> Log.i(caller.javaClass.name, message)
            LogLevel.DEBUG -> Log.d(caller.javaClass.name, message)
        }
        // For levels above debug, Also show the message on screen:
        if (level != LogLevel.DEBUG)
            Toast.makeText(activity.baseContext, message, Toast.LENGTH_LONG).show()
    }
}
