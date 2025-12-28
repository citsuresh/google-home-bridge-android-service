package com.example.googlehomeapisampleapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.googlehomeapisampleapp.HomeApp
import com.example.googlehomeapisampleapp.R
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.home.ConnectivityState
import com.google.home.DeviceType
import com.google.home.HomeDevice
import com.google.home.Structure
import com.google.home.Trait
import com.google.home.automation.UnknownDeviceType
import com.google.home.google.GoogleCameraDevice
import com.google.home.google.GoogleDoorbellDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class GhBridgeService : Service() {

    private val channelId = "GhBridgeServiceChannel"
    private val serviceType = "_ghbridge._tcp."
    private var serviceName = "GHBridge"
    private val servicePort = 8602

    private var server: ApplicationEngine? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private val binder = LocalBinder()
    var homeApp: HomeApp? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): GhBridgeService = this@GhBridgeService
    }

    companion object {
        var serviceState: String = GhBridgeConstants.STATE_STOPPED
            private set
        var lastServiceInfo: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("GhBridgeService created.")
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ghbridge-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
        startServer()
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                Timber.d("Attempting to start Ktor server on port $servicePort")

                val ktorServer = embeddedServer(CIO, port = servicePort, host = "0.0.0.0") {
                    install(WebSockets)
                    routing {
                        webSocket("/ws") {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    Timber.d("Received message: $text")
                                    val json: JsonObject = JsonParser.parseString(text).asJsonObject
                                    val cmd = json.get("cmd").asString
                                    val id = json.get("id").asString

                                    when (cmd) {
                                        "list" -> {
                                            GlobalScope.launch {
                                                val devices = mutableListOf<ClientDevice>()
                                                try {
                                                    val structures: Set<Structure> = homeApp!!.homeClient.structures().first()
                                                    for (structure in structures) {
                                                        val deviceSet: Set<HomeDevice> = structure.devices().first()
                                                        for (device in deviceSet) {
                                                            val deviceTypes = device.types().first()
                                                            val fallbackPriorityOrder = listOf(
                                                                GoogleDoorbellDevice::class,
                                                                GoogleCameraDevice::class,
                                                                OnOffLightDevice::class
                                                            )
                                                            val primaryType =
                                                                deviceTypes.find { it.metadata.isPrimaryType }
                                                                    ?: fallbackPriorityOrder
                                                                        .asSequence()
                                                                        .mapNotNull { priorityClass ->
                                                                            deviceTypes.find { priorityClass.isInstance(it) }
                                                                        }
                                                                        .firstOrNull()
                                                                    ?: deviceTypes.singleOrNull()
                                                                    ?: UnknownDeviceType()

                                                            val onOffTrait = primaryType.traits().firstOrNull { it is OnOff } as? OnOff

                                                            devices.add(
                                                                ClientDevice(
                                                                    id = device.id.id,
                                                                    name = device.name,
                                                                    type = deviceTypes.firstOrNull()?.toString() ?: "Unknown",
                                                                    state = DeviceState(
                                                                        online = device.sourceConnectivity.connectivityState == ConnectivityState.ONLINE,
                                                                        on = onOffTrait?.onOff ?: false
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    }
                                                    val response = ClientResponse(id, data = DeviceListData(devices))
                                                    val gson = Gson()
                                                    val responseJson = gson.toJson(response)
                                                    send(responseJson)

                                                } catch (e: Exception) {
                                                    val error = BridgeError(code = e.javaClass.simpleName, message = e.message ?: "Unknown error")
                                                    val response = ErrorResponse(id, error = error)
                                                    val gson = Gson()
                                                    val responseJson = gson.toJson(response)
                                                    send(responseJson)
                                                }
                                            }
                                        }
                                        "toggle" -> {
                                            GlobalScope.launch {
                                                try {
                                                    val deviceIdElement = json.get("deviceId")

                                                    if (deviceIdElement == null || deviceIdElement.isJsonNull) {
                                                        throw IllegalArgumentException("deviceId must be provided.")
                                                    }

                                                    val deviceId = deviceIdElement.asString

                                                    val structures: Set<Structure> = homeApp!!.homeClient.structures().first()
                                                    for (structure in structures) {
                                                        val deviceSet: Set<HomeDevice> = structure.devices().first()
                                                        val device = deviceSet.find { it.id.id == deviceId }
                                                        if (device != null) {
                                                            val typeSet = device.types().first()
                                                            val fallbackPriorityOrder = listOf(
                                                                GoogleDoorbellDevice::class,
                                                                GoogleCameraDevice::class,
                                                                OnOffLightDevice::class
                                                            )
                                                            val primaryType =
                                                                typeSet.find { it.metadata.isPrimaryType }
                                                                    ?: fallbackPriorityOrder
                                                                        .asSequence()
                                                                        .mapNotNull { priorityClass ->
                                                                            typeSet.find { priorityClass.isInstance(it) }
                                                                        }
                                                                        .firstOrNull()
                                                                    ?: typeSet.singleOrNull()
                                                                    ?: UnknownDeviceType()
                                                            val onOffTrait = primaryType.traits().first { it is OnOff } as OnOff

                                                            val currentState = onOffTrait.onOff ?: false
                                                            if (currentState) {
                                                                onOffTrait.off()
                                                            } else {
                                                                onOffTrait.on()
                                                            }

                                                            val response = ClientResponse(id, data = DeviceListData(listOf()))
                                                            val gson = Gson()
                                                            val responseJson = gson.toJson(response)
                                                            send(responseJson)
                                                            break
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    val error = BridgeError(code = e.javaClass.simpleName, message = e.message ?: "Unknown error")
                                                    val response = ErrorResponse(id, error = error)
                                                    val gson = Gson()
                                                    val responseJson = gson.toJson(response)
                                                    send(responseJson)
                                                }
                                            }
                                        }
                                        else -> {
                                            // send(text) // Just echo the message back for other commands
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Subscribe to the ApplicationStarted event to know when the server is ready
                    environment.monitor.subscribe(ApplicationStarted) {
                        Timber.d("Ktor server started successfully. Advertising service.")
                        serviceState = GhBridgeConstants.STATE_RUNNING
                        lastServiceInfo = "Service running on port $servicePort"
                        broadcastServiceStatus(serviceState, lastServiceInfo)
                        updateNotification(lastServiceInfo!!)
                        advertiseMdns()
                    }
                }
                server = ktorServer

                // Start the server and wait for it to stop. This will block the current coroutine.
                ktorServer.start(wait = true)

            } catch (e: Exception) {
                // This will catch exceptions from embeddedServer and start, like BindException
                Timber.e(e, "Failed to start Ktor server.")
                serviceState = GhBridgeConstants.STATE_FAILED
                lastServiceInfo = e.message ?: "Failed to start Ktor server."
                broadcastServiceStatus(serviceState, lastServiceInfo)
                updateNotification(lastServiceInfo!!)
                stopSelf() // Stop the service if the server fails to start
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GhBridge Service")
            .setContentText("Service is starting...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("GhBridgeService destroyed.")
        server?.stop(1000, 3000) // Trigger server shutdown
        stopAdvertiseMdns()
        multicastLock?.release()
        serviceJob.cancel() // Cancel all coroutines
        serviceState = GhBridgeConstants.STATE_STOPPED
        lastServiceInfo = "Service stopped."
        broadcastServiceStatus(serviceState, lastServiceInfo)
    }

    private fun broadcastServiceStatus(state: String, serviceInfo: String? = null) {
        Timber.d("Broadcasting service status: state=$state, info=$serviceInfo")
        val intent = Intent(GhBridgeConstants.ACTION_SERVICE_STATUS).apply {
            putExtra(GhBridgeConstants.EXTRA_SERVICE_STATE, state)
            if (serviceInfo != null) {
                putExtra(GhBridgeConstants.EXTRA_SERVICE_INFO, serviceInfo)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GhBridge Service")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId, "GhBridge Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun advertiseMdns() {
        Timber.d("Advertising mDNS service.")
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@GhBridgeService.serviceName
            serviceType = this@GhBridgeService.serviceType
            port = servicePort
        }

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    this@GhBridgeService.serviceName = NsdServiceInfo.serviceName
                    Timber.d("mDNS service registered.")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("mDNS registration failed. Error code: $errorCode")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Timber.d("mDNS service unregistered.")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("mDNS unregistration failed. Error code: $errorCode")
                }
            }
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    private fun stopAdvertiseMdns() {
        Timber.d("Stopping mDNS advertisement.")
        nsdManager?.let {
            it.unregisterService(registrationListener)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}