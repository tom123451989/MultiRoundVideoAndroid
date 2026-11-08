package com.example.multiroundvideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class VideoProcessingService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): VideoProcessingService = this@VideoProcessingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriString = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val uri = Uri.parse(uriString)
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "video.mp4"
                val speed = intent.getDoubleExtra(EXTRA_SPEED, 1000.0)
                val joins = intent.getIntExtra(EXTRA_JOINS, 2)
                val repeats = intent.getIntExtra(EXTRA_REPEATS, 2)
                val includeAudio = intent.getBooleanExtra(EXTRA_INCLUDE_AUDIO, true)
                val audioBoost = intent.getDoubleExtra(EXTRA_AUDIO_BOOST, 150.0)
                val intelliEnabled = intent.getBooleanExtra(EXTRA_INTELLI_ENABLED, false)
                val finalMatchEnabled = intent.getBooleanExtra(EXTRA_FINAL_MATCH_ENABLED, false)

                startProcessingInForeground(
                    uri = uri,
                    displayName = displayName,
                    speed = speed,
                    joins = joins,
                    repeats = repeats,
                    includeAudio = includeAudio,
                    audioBoost = audioBoost,
                    intelliEnabled = intelliEnabled,
                    finalMatchEnabled = finalMatchEnabled
                )
            }
            ACTION_STOP -> {
                stopProcessing()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProcessingInForeground(
        uri: Uri,
        displayName: String,
        speed: Double,
        joins: Int,
        repeats: Int,
        includeAudio: Boolean,
        audioBoost: Double,
        intelliEnabled: Boolean,
        finalMatchEnabled: Boolean
    ) {
        if (_isRunning.value) return

        stopRequested.set(false)
        _isRunning.value = true
        _progress.value = 0f
        _status.value = "Starting…"
        _details.value = "Initializing processing service…"
        _etaText.value = "Calculating ETA…"
        _activeEncoder.value = "Detecting…"

        val notification = buildNotification(0f, "Starting…", "Calculating ETA…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    )
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val startTime = System.currentTimeMillis()

        serviceScope.launch {
            try {
                val result = processVideo(
                    context = applicationContext,
                    uri = uri,
                    displayName = displayName,
                    speed = speed,
                    joins = joins,
                    includeAudio = includeAudio,
                    audioBoost = audioBoost,
                    repeats = repeats,
                    intelliEnabled = intelliEnabled,
                    finalMatchEnabled = finalMatchEnabled
                ) { value, label, message ->
                    val elapsed = System.currentTimeMillis() - startTime
                    val etaSec = calculateEtaSeconds(elapsed, value)
                    val etaStr = formatEta(etaSec)

                    _progress.value = value
                    _status.value = label
                    _details.value = message
                    _etaText.value = etaStr

                    when (label) {
                        "Encoder selected" -> _activeEncoder.value = message.substringAfter("Preferred encoder: ").substringBefore(".")
                        "Switching encoder" -> _activeEncoder.value = message.substringAfter("with ").substringBefore(".").trim()
                        "Complete" -> _activeEncoder.value = message.substringAfter("Encoder: ", _activeEncoder.value)
                    }

                    updateNotification(value, label, etaStr)
                }

                _generatedFile.value = result
                getSharedPreferences("multi_round_video", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pending_output", result.absolutePath)
                    .apply()

                _status.value = "Processing complete"
                _details.value = "${result.name} is ready. Save output to export it."
                _progress.value = 1f
                _etaText.value = "Complete"

                showCompletionNotification(result.name)

            } catch (cancelled: CancellationException) {
                _status.value = "Stopped"
                _details.value = "Processing was cancelled."
                _etaText.value = "Stopped"
            } catch (error: Throwable) {
                if (stopRequested.get()) {
                    _status.value = "Stopped"
                    _details.value = "Processing was cancelled. Temporary files were cleaned up."
                    _etaText.value = "Stopped"
                } else {
                    _status.value = "Processing failed"
                    _details.value = error.message ?: "An unknown processing error occurred."
                    _etaText.value = "Failed"
                }
            } finally {
                _isRunning.value = false
                stopRequested.set(false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun stopProcessing() {
        stopRequested.set(true)
        _status.value = "Stopping…"
        _details.value = "Cancelling processing…"
        _etaText.value = "Stopping…"
        FFmpegKit.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Processing Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and ETA for multi-round video processing."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progressValue: Float, statusLabel: String, etaString: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VideoProcessingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = (progressValue * 100).toInt().coerceIn(0, 100)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Processing Video ($percent%) — $statusLabel")
            .setContentText(etaString)
            .setSubText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(progressValue: Float, statusLabel: String, etaString: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(progressValue, statusLabel, etaString))
    }

    private fun showCompletionNotification(fileName: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Video Processing Complete")
            .setContentText("$fileName is ready! Tap to save your video.")
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "video_processing_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.multiroundvideo.START"
        const val ACTION_STOP = "com.example.multiroundvideo.STOP"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_JOINS = "extra_joins"
        const val EXTRA_REPEATS = "extra_repeats"
        const val EXTRA_INCLUDE_AUDIO = "extra_include_audio"
        const val EXTRA_AUDIO_BOOST = "extra_audio_boost"
        const val EXTRA_INTELLI_ENABLED = "extra_intelli_enabled"
        const val EXTRA_FINAL_MATCH_ENABLED = "extra_final_match_enabled"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _status = MutableStateFlow("Ready")
        val status: StateFlow<String> = _status.asStateFlow()

        private val _details = MutableStateFlow("Choose a video and configure processing.")
        val details: StateFlow<String> = _details.asStateFlow()

        private val _progress = MutableStateFlow(0f)
        val progress: StateFlow<Float> = _progress.asStateFlow()

        private val _etaText = MutableStateFlow("—")
        val etaText: StateFlow<String> = _etaText.asStateFlow()

        private val _generatedFile = MutableStateFlow<File?>(null)
        val generatedFile: StateFlow<File?> = _generatedFile.asStateFlow()

        private val _activeEncoder = MutableStateFlow("Not started")
        val activeEncoder: StateFlow<String> = _activeEncoder.asStateFlow()

        fun setGeneratedFile(file: File?) {
            _generatedFile.value = file
        }

        fun startService(
            context: Context,
            uri: Uri,
            displayName: String,
            speed: Double,
            joins: Int,
            repeats: Int,
            includeAudio: Boolean,
            audioBoost: Double,
            intelliEnabled: Boolean,
            finalMatchEnabled: Boolean
        ) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SPEED, speed)
                putExtra(EXTRA_JOINS, joins)
                putExtra(EXTRA_REPEATS, repeats)
                putExtra(EXTRA_INCLUDE_AUDIO, includeAudio)
                putExtra(EXTRA_AUDIO_BOOST, audioBoost)
                putExtra(EXTRA_INTELLI_ENABLED, intelliEnabled)
                putExtra(EXTRA_FINAL_MATCH_ENABLED, finalMatchEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
