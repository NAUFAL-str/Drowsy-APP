// drowsiness/DrowsinessDetectionScreen.kt
package com.example.myapplication.drowsiness

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DrowsinessDetectionScreen(
    modifier: Modifier = Modifier,
    viewModel: DrowsinessViewModel = viewModel()
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val drowsinessState by viewModel.drowsinessState.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    // Alarm state
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isAlarmPlaying by remember { mutableStateOf(false) }

    // Initialize detection when permission is granted
    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (cameraPermissionState.status.isGranted) {
            viewModel.analysisIntervalMs = 500
            viewModel.initializeDetection(context)
        }
    }

    // Handle alarm based on drowsiness state
    LaunchedEffect(drowsinessState.status) {
        when (drowsinessState.status) {
            "Drowsy" -> {
                if (!isAlarmPlaying) {
                    mediaPlayer = startAlarm(context)
                    isAlarmPlaying = true
                }
            }
            "Alert" -> {
                if (isAlarmPlaying) {
                    stopAlarm(mediaPlayer)
                    mediaPlayer = null
                    isAlarmPlaying = false
                }
            }
        }
    }

    // Clean up alarm when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            stopAlarm(mediaPlayer)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            cameraPermissionState.status.isGranted -> {
                // Camera Preview
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }.also { previewView ->
                            // Setup camera when preview is ready
                            viewModel.setupCamera(previewView)
                        }
                    }
                )

                // Status Overlay
                StatusOverlay(
                    drowsinessState = drowsinessState,
                    isProcessing = isProcessing,
                    isAlarmPlaying = isAlarmPlaying,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            cameraPermissionState.status.shouldShowRationale -> {
                // Show rationale
                PermissionRationale(
                    onRequestPermission = { cameraPermissionState.launchPermissionRequest() }
                )
            }

            else -> {
                // Request permission
                LaunchedEffect(Unit) {
                    cameraPermissionState.launchPermissionRequest()
                }

                PermissionRequest(
                    onRequestPermission = { cameraPermissionState.launchPermissionRequest() }
                )
            }
        }
    }
}

// Alarm functions
private fun startAlarm(context: Context): MediaPlayer? {
    return try {
        // 1) Force ALARM stream to maximum volume
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            maxAlarmVol,
            AudioManager.FLAG_PLAY_SOUND or AudioManager.FLAG_SHOW_UI
        )

        // 2) Load the loud alarm sound from res/raw/loud_alarm.mp3
        val mediaPlayer = MediaPlayer.create(context, R.raw.loud_alarm)

        // 3) Configure as ALARM usage
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mediaPlayer.isLooping = true

        // 4) Ensure playback volume is at max on the ALARM stream
        mediaPlayer.setVolume(1.0f, 1.0f)

        mediaPlayer.start()
        mediaPlayer
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun stopAlarm(mediaPlayer: MediaPlayer?) {
    try {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
private fun StatusOverlay(
    drowsinessState: DrowsinessState,
    isProcessing: Boolean,
    isAlarmPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (drowsinessState.status) {
                    "Drowsy" -> Color.Red.copy(alpha = 0.9f)
                    "Alert" -> Color.Green.copy(alpha = 0.9f)
                    else -> Color.Gray.copy(alpha = 0.9f)
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isAlarmPlaying) "⚠️ ${drowsinessState.status} ⚠️" else drowsinessState.status,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "Confidence: ${String.format("%.2f", drowsinessState.probability)}",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )

                if (isAlarmPlaying) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "🔊 ALARM ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Yellow
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera Permission Required",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This app needs camera access to detect drowsiness",
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun PermissionRequest(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()

        Spacer(modifier = Modifier.height(16.dp))

        Text("Requesting camera permission...")

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRequestPermission) {
            Text("Request Again")
        }
    }
}
