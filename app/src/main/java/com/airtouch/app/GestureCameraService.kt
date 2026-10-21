package com.airtouch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GestureCameraService : LifecycleService() {
    private var cameraExecutor: ExecutorService? = null
    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val active = AtomicBoolean(false)
    private val fatalErrorReported = AtomicBoolean(false)
    private var engine: GestureEngine? = null

    @androidx.camera.core.ExperimentalGetImage
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) { engine?.pause(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RESUME) { engine?.resume(); return START_NOT_STICKY }
        if (active.get()) return START_NOT_STICKY
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val sensitivity = normalizeSensitivity(intent?.getFloatExtra(EXTRA_SENSITIVITY, 0.60f) ?: 0.60f)
        val lockEnabled = intent?.getBooleanExtra(EXTRA_LOCK, false) ?: false
        val enabled = intent?.getBooleanExtra(EXTRA_ENABLED, true) ?: true
        val practice = intent?.getBooleanExtra(EXTRA_PRACTICE, false) ?: false
        engine = GestureEngine(sensitivity, lockEnabled, enabled, practice) { event ->
            sendEvent(event)
            if (event.action != null && !practice) when (event.action) {
                "Play / resume" -> dispatchMediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                "Pause" -> dispatchMediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Previous item" -> dispatchMediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Next item" -> dispatchMediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                "Scroll down" -> if (!AirTouchAccessibilityService.scroll(true)) {
                    sendError(if (AirTouchAccessibilityService.isEnabled(this)) "This screen cannot scroll further down." else "Enable AirTouch gesture scrolling in Settings to scroll other apps.")
                }
                "Scroll up" -> if (!AirTouchAccessibilityService.scroll(false)) {
                    sendError(if (AirTouchAccessibilityService.isEnabled(this)) "This screen cannot scroll further up." else "Enable AirTouch gesture scrolling in Settings to scroll other apps.")
                }
                "Locking screen" -> if (lockEnabled) {
                    runCatching { lockDevice(this) }
                        .onSuccess { stopSession() }
                        .onFailure { sendError("Screen lock failed. Check Android device-admin access in Settings.") }
                }
            }
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        active.set(true)
        getSharedPreferences("airtouch", MODE_PRIVATE).edit().putBoolean("camera_session_active", true).apply()
        startCamera()
        return START_NOT_STICKY
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.50f)
                .setMinHandPresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, options)
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                if (!active.get()) return@addListener
                try {
                    cameraProvider = providerFuture.get()
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor!!) { image -> analyze(image) }
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                } catch (error: Exception) {
                    sendError("Front camera could not start: ${error.localizedMessage ?: "camera unavailable"}")
                    stopSession()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (error: Exception) {
            sendError("Gesture recognizer could not start: ${error.localizedMessage ?: "model unavailable"}")
            stopSession()
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(image: ImageProxy) {
        try {
            if (!active.get()) return
            val mediaImage = image.image
                ?: throw IllegalStateException("CameraX did not provide an RGBA media image.")
            if (mediaImage.format != android.graphics.PixelFormat.RGBA_8888) {
                throw IllegalStateException("CameraX returned image format ${mediaImage.format}; expected RGBA_8888.")
            }
            val mpImage = MediaImageBuilder(mediaImage).build()
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()
            val now = SystemClock.uptimeMillis()
            val result = recognizer?.recognizeForVideo(mpImage, processing, now)
            val category = result?.gestures()?.firstOrNull()?.firstOrNull()
            val landmarks = result?.landmarks()?.firstOrNull()
            val centerX = landmarks?.map { it.x() }?.average()?.toFloat()
            val centerY = landmarks?.map { it.y() }?.average()?.toFloat()
            engine?.onFrame(category?.categoryName(), category?.score() ?: 0f, centerX, now, centerY)
        } catch (error: Exception) {
            if (fatalErrorReported.compareAndSet(false, true)) {
                sendError("Gesture recognition failed: ${error.localizedMessage ?: error.javaClass.simpleName}")
                ContextCompat.getMainExecutor(this).execute { stopSession() }
            }
        } finally {
            image.close()
        }
    }

    private fun sendEvent(event: RecognitionEvent) {
        val intent = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_GESTURE, event.gesture)
            .putExtra(EXTRA_STATUS, event.status)
            .putExtra(EXTRA_PROGRESS, event.progress)
            .putExtra(EXTRA_ACTION, event.action)
        sendBroadcast(intent)
    }

    private fun sendError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AirTouch camera session is active")
            .setContentText("Hands are recognized on this device. Camera stays on until you stop the session.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop camera", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gesture camera session", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun stopSession() {
        if (!active.compareAndSet(true, false)) {
            stopSelf()
            return
        }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { recognizer?.close() }
        cameraProvider = null
        recognizer = null
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        engine = null
        getSharedPreferences("airtouch", MODE_PRIVATE).edit().putBoolean("camera_session_active", false).apply()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    companion object {
        const val ACTION_EVENT = "com.airtouch.app.GESTURE_EVENT"
        const val ACTION_ERROR = "com.airtouch.app.GESTURE_ERROR"
        const val ACTION_STOPPED = "com.airtouch.app.CAMERA_STOPPED"
        const val ACTION_STOP = "com.airtouch.app.STOP_CAMERA"
        const val ACTION_PAUSE = "com.airtouch.app.PAUSE_RECOGNITION"
        const val ACTION_RESUME = "com.airtouch.app.RESUME_RECOGNITION"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_LOCK = "lock"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PRACTICE = "practice"
        const val EXTRA_GESTURE = "gesture"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ACTION = "action"
        private const val CHANNEL_ID = "airtouch_camera"
        private const val NOTIFICATION_ID = 41

        fun start(context: android.content.Context, sensitivity: Float, lock: Boolean, enabled: Boolean, practice: Boolean) {
            val intent = Intent(context, GestureCameraService::class.java)
                .putExtra(EXTRA_SENSITIVITY, sensitivity)
                .putExtra(EXTRA_LOCK, lock)
                .putExtra(EXTRA_ENABLED, enabled)
                .putExtra(EXTRA_PRACTICE, practice)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, GestureCameraService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: android.content.Context, paused: Boolean) {
            val action = if (paused) ACTION_PAUSE else ACTION_RESUME
            context.startService(Intent(context, GestureCameraService::class.java).setAction(action))
        }
    }
}
