package com.example.googlehomeapisampleapp.view.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.googlehomeapisampleapp.R
import com.example.googlehomeapisampleapp.service.GhBridgeConstants
import java.io.File
import java.util.Locale

@Composable
fun ServiceStatusIndicator(serviceState: String, serviceInfo: String?, onToggleServiceClick: () -> Unit) {
    val serviceIconColor = when (serviceState) {
        GhBridgeConstants.STATE_RUNNING -> Color(0xFF008000)
        GhBridgeConstants.STATE_STOPPED -> Color.Red
        GhBridgeConstants.STATE_STARTING, GhBridgeConstants.STATE_STOPPING -> Color.Gray
        else -> Color.Yellow
    }
    val serviceContentDescription = when (serviceState) {
        GhBridgeConstants.STATE_RUNNING -> "Service is running. Click to stop."
        GhBridgeConstants.STATE_STOPPED -> "Service is stopped. Click to start."
        GhBridgeConstants.STATE_STARTING -> "Service is starting."
        GhBridgeConstants.STATE_STOPPING -> "Service is stopping."
        else -> "Service is in an unknown state."
    }
    val serviceText = when (serviceState) {
        GhBridgeConstants.STATE_FAILED -> serviceInfo ?: "Service failed."
        else -> serviceState.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onToggleServiceClick() }
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_service_status),
            contentDescription = serviceContentDescription,
            tint = serviceIconColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(serviceText, color = serviceIconColor, fontSize = 12.sp)
    }
}

@Composable
fun ServiceLogsMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Service Logs") },
        onClick = onClick
    )
}

@Composable
fun ServiceLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val logFile = File(context.filesDir, "service.log")
    var logText by remember(logFile) { mutableStateOf(logFile.readLines(Charsets.UTF_8).asReversed().joinToString("\n")) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Service Logs") },
        text = {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                Text(logText)
            }
        },
        confirmButton = {
            TextButton(onClick = { logText = logFile.readLines(Charsets.UTF_8).asReversed().joinToString("\n") }) { Text("Refresh") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { 
                    logFile.writeText("", Charsets.UTF_8)
                    logText = ""
                }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}