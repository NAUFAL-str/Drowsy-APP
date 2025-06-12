package com.example.myapplication.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.myapplication.R
import com.example.myapplication.drowsiness.DrowsinessViewModel

class BackgroundDetectionService : LifecycleService() {

    private val viewModel = DrowsinessViewModel()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        viewModel.analysisIntervalMs = 1000
        viewModel.initializeDetection(applicationContext)
        viewModel.setupBackgroundCamera(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onCleared()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drowsiness Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drowsiness Detection Running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "drowsiness_service"
    }
}
