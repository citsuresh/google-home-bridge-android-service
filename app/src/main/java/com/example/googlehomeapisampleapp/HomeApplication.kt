package com.example.googlehomeapisampleapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class HomeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupFileLogging()
    }

    private fun setupFileLogging() {
        try {
            val logFile = File(filesDir, "service.log")
            if (logFile.exists() && logFile.length() > 1 * 1024 * 1024) { // 1 MB
                logFile.writeText("", Charsets.UTF_8)
            }
            Timber.plant(FileLoggingTree(logFile))
        } catch (e: IOException) {
            Timber.e(e, "Could not set up file logging.")
        }
    }

    class FileLoggingTree(private val logFile: File) : Timber.DebugTree() {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            try {
                val timestamp = dateFormat.format(Date())
                logFile.appendText("$timestamp $tag: $message\n")
                t?.let { logFile.appendText(it.stackTraceToString() + "\n") }
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
}