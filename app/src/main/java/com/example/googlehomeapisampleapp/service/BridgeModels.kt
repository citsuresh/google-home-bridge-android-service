package com.example.googlehomeapisampleapp.service

// Data classes for WebSocket communication
data class ClientResponse(
    val id: String,
    val ok: Boolean = true,
    val data: DeviceListData
)

data class DeviceListData(
    val devices: List<ClientDevice>
)

data class ClientDevice(
    val id: String,
    val name: String,
    val type: String,
    val state: DeviceState
)

data class DeviceState(
    val online: Boolean,
    val on: Boolean? = null
)

data class ErrorResponse(
    val id: String,
    val ok: Boolean = false,
    val error: BridgeError
)

data class BridgeError(
    val code: String,
    val message: String
)
