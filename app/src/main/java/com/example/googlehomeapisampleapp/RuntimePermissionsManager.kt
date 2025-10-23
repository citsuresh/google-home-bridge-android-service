package com.example.googlehomeapisampleapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat


class RuntimePermissionsManager(
    private val activity: ComponentActivity,
    private val requestPermissionLauncher: ActivityResultLauncher<String>,
    private val onPermissionResult: (Boolean) -> Unit
) {

    fun checkMicrophonePermission() {
        val isGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        onPermissionResult(isGranted)
    }

    fun requestMicrophonePermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}