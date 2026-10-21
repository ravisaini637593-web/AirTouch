package com.airtouch.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.Executors

private val Ink = Color(0xFF10251D)
private val Green = Color(0xFF155F4A)
private val Lime = Color(0xFFC9F27A)
private val Canvas = Color(0xFFF5F5F0)
private val Card = Color(0xFFE9ECE5)
private val Muted = Color(0xFF68756D)
private val White = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        continueAfterCameraPermission(granted)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        finishSessionPermission(granted)
    }

    private fun requestSessionPermissions(callback: (Boolean) -> Unit) {
        permissionResult = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            continueAfterCameraPermission(true)
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun continueAfterCameraPermission(cameraGranted: Boolean) {
        if (!cameraGranted) { finishSessionPermission(false); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else finishSessionPermission(true)
    }

    private fun finishSessionPermission(granted: Boolean) {
        permissionResult?.invoke(granted)
        permissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245, 245, 240)
        window.navigationBarColor = android.graphics.Color.rgb(245, 245, 240)
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = White, background = Canvas, surface = Canvas)) {
                AirTouchApp(
                    requestCamera = { callback -> requestSessionPermissions(callback) },
                    openLockAdmin = { startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, LockAdminReceiver::class.java))
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "AirTouch can lock the screen after a deliberate 0.8-second open-palm hold during an active session.")
                    }) }
                )
            }
        }
}
}

private enum class Screen { HOME, PRACTICE, SETTINGS, SESSION }
private data class GestureInfo(val name: String, val description: String, val icon: String)
private val gestureList = listOf(
    GestureInfo("Thumbs up", "Play or resume media", "👍"),
    GestureInfo("Closed fist", "Pause media", "✊"),
    GestureInfo("Swipe left", "Previous item or slide", "←"),
    GestureInfo("Swipe right", "Next item or slide", "→"),
    GestureInfo("Two-finger swipe up / down", "Scroll the screen in that direction", "✌️"),
    GestureInfo("Open palm", "Hold 0.8 sec to lock screen", "✋")
)

@Composable
private fun AirTouchApp(requestCamera: ((Boolean) -> Unit) -> Unit, openLockAdmin: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("airtouch", Context.MODE_PRIVATE) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var session by rememberSaveable { mutableStateOf(false) }
    var practice by rememberSaveable { mutableStateOf(false) }
    var pendingPractice by rememberSaveable { mutableStateOf(false) }
    var showCameraRationale by rememberSaveable { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    var lockEnabled by remember { mutableStateOf(isLockAdminActive(context)) }
    var scrollingEnabled by remember { mutableStateOf(AirTouchAccessibilityService.isEnabled(context)) }
    var enabledGestures by remember { mutableStateOf(prefs.getBoolean("gestures", true)) }
    var sensitivity by remember { mutableFloatStateOf(normalizeSensitivity(prefs.getFloat("sensitivity", 0.60f))) }
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(sensitivity) {
        if (prefs.getFloat("sensitivity", sensitivity) != sensitivity) {
            prefs.edit().putFloat("sensitivity", sensitivity).apply()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lockEnabled = isLockAdminActive(context)
                scrollingEnabled = AirTouchAccessibilityService.isEnabled(context)
                if (screen == Screen.SESSION && !prefs.getBoolean("camera_session_active", false)) {
                    session = false
                    screen = Screen.HOME
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun beginSession(isPractice: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            practice = isPractice
            runCatching { GestureCameraService.start(context, sensitivity, lockEnabled, enabledGestures, isPractice) }
                .onFailure { toast = "Could not start camera session: ${it.localizedMessage ?: "service unavailable"}" }
            session = true
            screen = Screen.SESSION
        } else {
            pendingPractice = isPractice
            showCameraRationale = true
        }
    }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.SESSION) { session = false; GestureCameraService.stop(context) }
        screen = if (screen == Screen.SESSION || screen == Screen.PRACTICE || screen == Screen.SETTINGS) Screen.HOME else Screen.HOME
    }

    LaunchedEffect(toast) {
        if (toast.isNotBlank()) {
            snackbarState.showSnackbar(toast, withDismissAction = true)
            toast = ""
        }
    }

    Surface(color = Canvas) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onStart = { beginSession(false) },
                    onPractice = { screen = Screen.PRACTICE },
                    onSettings = { screen = Screen.SETTINGS },
                    lockEnabled = lockEnabled
                )
                Screen.PRACTICE -> PracticeScreen(onBack = { screen = Screen.HOME }, onStart = { beginSession(true) })
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.HOME },
                    lockEnabled = lockEnabled,
                    onEnableLock = openLockAdmin,
                    scrollingEnabled = scrollingEnabled,
                    onEnableScrolling = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    enabled = enabledGestures,
                    onEnabled = { enabledGestures = it; prefs.edit().putBoolean("gestures", it).apply() },
                    sensitivity = sensitivity,
                    onSensitivity = { sensitivity = it; prefs.edit().putFloat("sensitivity", it).apply() }
                )
                Screen.SESSION -> GestureSession(
                    practice = practice,
                    lockEnabled = lockEnabled,
                    enabledGestures = enabledGestures,
                    sensitivity = sensitivity,
                    onStop = { GestureCameraService.stop(context); session = false; screen = Screen.HOME },
                    onLockSettings = { GestureCameraService.stop(context); session = false; screen = Screen.SETTINGS; openLockAdmin() },
                    onMessage = { toast = it }
                )
            }
            SnackbarHost(hostState = snackbarState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp))
        }
    }
    if (showCameraRationale) {
        AlertDialog(
            onDismissRequest = { showCameraRationale = false },
            title = { Text("Camera access for gestures") },
            text = { Text("AirTouch uses the front camera for this session and can keep recognizing gestures while you use another app or turn off the screen. Recognition runs on this phone; frames are not saved or uploaded. On Android 13 and later, allow notifications so Android can show a persistent session notice with a Stop camera action. Force-stopping AirTouch ends the session.") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraRationale = false
                    requestCamera { granted ->
                        if (granted) {
                            runCatching { GestureCameraService.start(context, sensitivity, lockEnabled, enabledGestures, pendingPractice) }
                                .onFailure { toast = "Could not start camera session: ${it.localizedMessage ?: "service unavailable"}" }
                            practice = pendingPractice; session = true; screen = Screen.SESSION
                        }
                        else toast = "Allow camera access and, on Android 13+, notifications to start a visible background camera session."
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showCameraRationale = false }) { Text("Not now") } }
        )
    }
}

@Composable
private fun HomeScreen(onStart: () -> Unit, onPractice: () -> Unit, onSettings: () -> Unit, lockEnabled: Boolean) {
    Column(Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("AIRTOUCH", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.4.sp)
                Text("Hands-free, on your terms", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Ink) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column {
                    Text("A little more", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("hands-free.", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                    Text("Simple gestures for the moments you’d rather not reach.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 9.dp))
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF174D3E), Color(0xFF0C2D24)))).padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(CircleShape).background(Color(0x3327E7A1)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Lime)); Spacer(Modifier.width(7.dp))
                            Text("CAMERA OFF", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        }
                        Spacer(Modifier.weight(1f)); Text("01 / 04", color = Color(0xFFA9C2B7), fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Your hands.\nYour shortcuts.", color = White, fontSize = 31.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
                    Text("Start a session when you’re ready. Recognition keeps running when you leave the app.", color = Color(0xFFCADAD2), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp, bottom = 21.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MiniGesture("👍", "PLAY")
                        MiniGesture("✊", "PAUSE")
                        MiniGesture("↔", "SWIPE")
                    }
                    Spacer(Modifier.height(21.dp))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink), shape = RoundedCornerShape(17.dp)) {
                        Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Text("Start gesture session", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoTile(Modifier.weight(1f), "LOCAL BY DESIGN", "Frames stay on this phone", "◉")
                    InfoTile(Modifier.weight(1f), "LOCK SHORTCUT", if (lockEnabled) "Ready on this device" else "Optional device access", "⌑")
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(White).clickable(onClick = onPractice).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE6F2E8)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.WavingHand, null, tint = Green) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("Take a quick practice", color = Ink, fontWeight = FontWeight.SemiBold); Text("Learn each gesture without triggering actions", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
                    Icon(Icons.Rounded.ArrowForward, null, tint = Green)
                }
            }
        }
    }
}

@Composable private fun BrandMark() {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Green), contentAlignment = Alignment.Center) {
        Text("a", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Lime)
    }
}
@Composable private fun MiniGesture(emoji: String, label: String) {
    Row(Modifier.clip(RoundedCornerShape(13.dp)).background(Color(0x20FFFFFF)).padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 17.sp); Spacer(Modifier.width(7.dp)); Text(label, color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
    }
}
@Composable private fun InfoTile(modifier: Modifier, title: String, body: String, icon: String) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(White).padding(15.dp)) {
        Text(icon, color = Green, fontSize = 17.sp); Spacer(Modifier.height(10.dp)); Text(title, color = Ink, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PracticeScreen(onBack: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        BackHeader("Practice", onBack)
        Text("Get to know the cues", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Text("Try them at your own pace. Practice never sends media commands or locks your screen.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 17.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
            items(gestureList) { gesture ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(White).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFEAF1E8)), contentAlignment = Alignment.Center) { Text(gesture.icon, fontSize = 24.sp) }
                    Column(Modifier.weight(1f).padding(start = 13.dp)) { Text(gesture.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(gesture.description, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp).padding(bottom = 2.dp), colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(17.dp)) {
            Icon(Icons.Rounded.WavingHand, null); Spacer(Modifier.width(8.dp)); Text("Practice with camera", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, lockEnabled: Boolean, onEnableLock: () -> Unit, scrollingEnabled: Boolean, onEnableScrolling: () -> Unit, enabled: Boolean, onEnabled: (Boolean) -> Unit, sensitivity: Float, onSensitivity: (Float) -> Unit) {
    Column(Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        BackHeader("Settings", onBack)
        Text("Make it yours", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Small controls, clear choices.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(White).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("↕", color = Green, fontSize = 22.sp); Spacer(Modifier.width(10.dp)); Text("Gesture scrolling", color = Ink, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f)); Text(if (scrollingEnabled) "READY" else "OFF", color = if (scrollingEnabled) Green else Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Show two fingers and swipe up or down to scroll the current screen in the same direction. Android requires you to enable AirTouch’s accessibility service.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                    OutlinedButton(onClick = onEnableScrolling, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text(if (scrollingEnabled) "Review scrolling access" else "Enable gesture scrolling")
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(White).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Gesture controls", color = Ink, fontWeight = FontWeight.SemiBold); Text("Enable or pause all gesture actions", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                        Switch(checked = enabled, onCheckedChange = onEnabled)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 13.dp), color = Card)
                    Text("RECOGNITION CONFIDENCE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text("Higher confidence means fewer, more deliberate triggers.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                    Slider(value = sensitivity.coerceIn(0.40f, 0.85f), onValueChange = onSensitivity, valueRange = 0.40f..0.85f, enabled = enabled)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("More responsive", color = Muted, fontSize = 10.sp); Text("More deliberate", color = Muted, fontSize = 10.sp) }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(White).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = Green); Spacer(Modifier.width(10.dp)); Text("Palm-to-lock", color = Ink, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f)); Text(if (lockEnabled) "READY" else "OFF", color = if (lockEnabled) Green else Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Uses Android’s screen-lock permission. AirTouch asks for it only here; the gesture stays off until Android confirms access.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                    OutlinedButton(onClick = onEnableLock, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text(if (lockEnabled) "Review device lock access" else "Enable device lock access")
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFE8F1E8)).padding(17.dp)) {
                    Icon(Icons.Rounded.PrivacyTip, null, tint = Green); Column(Modifier.padding(start = 12.dp)) {
                        Text("Private by default", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("Frames are processed on this phone and are not saved or uploaded. During a session, the camera continues in the background with a persistent notification until you stop it.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 23.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp).clip(CircleShape).background(White)) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Ink) }
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun GestureSession(practice: Boolean, lockEnabled: Boolean, enabledGestures: Boolean, sensitivity: Float, onStop: () -> Unit, onLockSettings: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var currentGesture by remember { mutableStateOf("Looking for one hand") }
    var status by remember { mutableStateOf(if (practice) "Practice mode · actions disabled" else "Camera active · on-device only") }
    var progress by remember { mutableFloatStateOf(0f) }
    var cameraRunning by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf("") }
    var lastFeedbackAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.action == GestureCameraService.ACTION_ERROR) {
                    status = intent.getStringExtra(GestureCameraService.EXTRA_STATUS) ?: "Camera session error"
                    onMessage(status)
                    return
                }
                if (intent.action == GestureCameraService.ACTION_STOPPED) {
                    cameraRunning = false
                    currentGesture = "Camera session stopped"
                    status = "Start a new session from Home"
                    progress = 0f
                    return
                }
                currentGesture = intent.getStringExtra(GestureCameraService.EXTRA_GESTURE) ?: currentGesture
                status = intent.getStringExtra(GestureCameraService.EXTRA_STATUS) ?: status
                progress = intent.getFloatExtra(GestureCameraService.EXTRA_PROGRESS, 0f)
                intent.getStringExtra(GestureCameraService.EXTRA_ACTION)?.let {
                    feedback = it
                    lastFeedbackAt = SystemClock.elapsedRealtime()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(GestureCameraService.ACTION_EVENT)
            addAction(GestureCameraService.ACTION_ERROR)
            addAction(GestureCameraService.ACTION_STOPPED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Column(Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onStop, modifier = Modifier.size(42.dp).clip(CircleShape).background(White)) { Icon(Icons.Rounded.ArrowBack, "End session", tint = Ink) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(if (practice) "Gesture practice" else "Gesture session", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("Session continues in the background", color = Muted, fontSize = 11.sp) }
            Box(Modifier.clip(CircleShape).background(if (cameraRunning) Color(0xFFE1F4D7) else Card).padding(horizontal = 11.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (cameraRunning) Green else Muted)); Spacer(Modifier.width(6.dp)); Text(if (cameraRunning) "CAMERA ON" else "CAMERA OFF", color = if (cameraRunning) Green else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(27.dp)).background(Ink)) {
            CameraSessionCard(practice, cameraRunning)
            Column(Modifier.align(Alignment.TopStart).padding(13.dp)) {
                Row(Modifier.clip(CircleShape).background(Color(0xCC10251D)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CameraAlt, null, tint = Lime, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("LIVE · PROCESSED ON DEVICE", color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDB10251D), Color(0xF210251D)))).padding(horizontal = 17.dp, vertical = 17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✋", fontSize = 28.sp); Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(currentGesture, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = Color(0xFFC8D8CF), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (feedback.isNotBlank() && SystemClock.elapsedRealtime() - lastFeedbackAt < 2200L) {
                        Box(Modifier.widthIn(max = 130.dp).clip(CircleShape).background(Lime).padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Text(feedback, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                if (progress > 0f && progress < 1f && currentGesture == "Open palm · hold to lock") {
                    Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Lime, trackColor = Color(0x55FFFFFF))
                    Text("Keep your palm steady · ${(PALM_LOCK_HOLD_MS / 1000f * (1f - progress)).coerceAtLeast(0f).format1()}s", color = White, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { GestureCameraService.pause(context, true) }, modifier = Modifier.weight(1f).height(51.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Pause, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Pause", fontSize = 13.sp) }
            Button(onClick = onStop, modifier = Modifier.weight(1f).height(51.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Stop camera", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        if (status.startsWith("Recognition paused")) {
            TextButton(onClick = { GestureCameraService.pause(context, false); status = if (practice) "Practice mode · actions disabled" else "Camera active · on-device only" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Rounded.PlayArrow, null); Text("Resume recognition") }
        }
    }
}

internal data class RecognitionEvent(val gesture: String, val status: String, val progress: Float = 0f, val action: String? = null)
internal const val PALM_LOCK_HOLD_MS = 800L

internal fun normalizeSensitivity(value: Float): Float =
    if (value.isFinite() && value in 0.40f..0.85f) value else 0.60f

internal class GestureEngine(
    threshold: Float,
    private val lockEnabled: Boolean,
    private val enabled: Boolean,
    private val practice: Boolean,
    private val emit: (RecognitionEvent) -> Unit
) {
    private val threshold = normalizeSensitivity(threshold)
    private var candidate = ""
    private var candidateSince = 0L
    private var lastAction = 0L
    private var swipeStartX: Float? = null
    private var swipeStartY: Float? = null
    private var swipeStartAt = 0L
    private var paused = false
    private var firedForPose = false

    fun pause() { paused = true; emit(RecognitionEvent("Recognition paused", "Recognition paused · tap Resume when ready")) }
    fun resume() { paused = false; reset() }

    fun onFrame(name: String?, score: Float, x: Float?, now: Long, y: Float? = null) {
        if (paused) return
        if (!enabled && !practice) { emit(RecognitionEvent("Gestures are off", "Turn gesture controls on in Settings")); return }
        if (x == null) {
            reset()
            emit(RecognitionEvent("Looking for one hand", "Move into the camera view"))
            return
        }
        if (name == null || name == "None" || name == "Unknown" || score < threshold) {
            reset()
            emit(RecognitionEvent("Hand detected · adjust pose", "Gesture confidence ${(score * 100).toInt()}% · keep your hand centered and well lit"))
            return
        }
        if (name != candidate) { candidate = name; candidateSince = now; firedForPose = false; swipeStartX = x; swipeStartY = y; swipeStartAt = now }
        val elapsed = now - candidateSince
        if (name == "Victory") {
            val dy = y?.minus(swipeStartY ?: y) ?: 0f
            if (now - swipeStartAt in 120..1000 && kotlin.math.abs(dy) >= 0.22f && now - lastAction >= 800L) {
                val action = if (dy < 0f) "Scroll up" else "Scroll down"
                trigger(if (practice) "Practice cue · $action" else action, if (practice) "Practice cue · two-finger swipe" else "Two-finger scroll recognized", now)
                reset()
                return
            }
            emit(RecognitionEvent("Two fingers · swipe to scroll", "Recognition confidence ${(score * 100).toInt()}%"))
            return
        }
        if (name == "Open_Palm") {
            val dx = x - (swipeStartX ?: x)
            val swipeElapsed = now - swipeStartAt
            if (swipeElapsed in 120..1000 && kotlin.math.abs(dx) >= 0.25f && now - lastAction >= 800L) {
                val action = if (dx < 0f) "Previous item" else "Next item"
                trigger(action, if (practice) "Practice cue · swipe" else "Swipe recognized", now)
                reset(); return
            }
            if (lockEnabled && !practice) {
                val progress = (elapsed / PALM_LOCK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
                if (progress >= 1f && !firedForPose && now - lastAction >= 800L) {
                    firedForPose = true
                    trigger("Locking screen", "Screen lock requested", now)
                } else emit(RecognitionEvent("Open palm · hold to lock", "Keep your hand steady", progress))
            } else if (!practice) emit(RecognitionEvent("Open palm", "Lock shortcut unavailable · enable in Settings"))
            else emit(RecognitionEvent("Open palm", "Practice cue · lock stays off"))
            return
        }
        val required = 600L
        val action = when (name) { "Thumb_Up" -> "Play / resume"; "Closed_Fist" -> "Pause"; else -> null }
        if (action != null && elapsed >= required && !firedForPose && now - lastAction >= 800L) {
            firedForPose = true
            trigger(if (practice) "Practice cue · ${if (action.startsWith("Play")) "thumbs up" else "fist"}" else action, "Gesture confirmed", now)
        } else {
            val label = when (name) { "Thumb_Up" -> "Thumbs up · hold briefly"; "Closed_Fist" -> "Closed fist · hold briefly"; else -> "Gesture not mapped" }
            emit(RecognitionEvent(label, "Recognition confidence ${(score * 100).toInt()}%"))
        }
    }
    private fun trigger(action: String, status: String, now: Long) {
        lastAction = now
        emit(RecognitionEvent(action, status, 0f, if (practice) "Practice" else action))
    }
    private fun reset() { candidate = ""; candidateSince = 0L; swipeStartX = null; swipeStartY = null; swipeStartAt = 0L; firedForPose = false }
}

@Composable
private fun CameraSessionCard(practice: Boolean, cameraRunning: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17352A), Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Text("✋", fontSize = 76.sp)
            Text(if (cameraRunning) "Camera session running" else "Camera session stopped", color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 18.dp))
            Text(if (!cameraRunning) "Start a new session from Home" else if (practice) "Practice mode · actions disabled" else "On-device recognition · background session active", color = Color(0xFFC8D8CF), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            if (cameraRunning) Text("Use the persistent notification to stop the camera from anywhere.", color = Color(0xFFC8D8CF), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
@androidx.camera.core.ExperimentalGetImage
private fun CameraFeed(onResult: (String?, Float, Float?, Long) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE; previewView = this } }, modifier = Modifier.fillMaxSize())
    DisposableEffect(owner, previewView) {
        val view = previewView
        if (view == null) return@DisposableEffect onDispose { }
        var recognizer: GestureRecognizer? = null
        var provider: ProcessCameraProvider? = null
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.50f)
                .setMinHandPresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
            val localRecognizer = recognizer
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!active.get()) return@addListener
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        try {
                            val mediaImage = image.image
                            if (mediaImage != null && active.get()) {
                                val mpImage = MediaImageBuilder(mediaImage).build()
                                val processing = ImageProcessingOptions.builder().setRotationDegrees(image.imageInfo.rotationDegrees).build()
                                val now = SystemClock.uptimeMillis()
                                val result = localRecognizer?.recognizeForVideo(mpImage, processing, now)
                                val categories = result?.gestures()?.firstOrNull()?.firstOrNull()
                                val landmarks = result?.landmarks()?.firstOrNull()
                                val x = landmarks?.map { it.x() }?.average()?.toFloat()
                                mainExecutor.execute { if (active.get()) onResult(categories?.categoryName(), categories?.score() ?: 0f, x, now) }
                            } else mainExecutor.execute { onResult(null, 0f, null, SystemClock.uptimeMillis()) }
                        } catch (_: Exception) {
                            mainExecutor.execute { onResult(null, 0f, null, SystemClock.uptimeMillis()) }
                        } finally { image.close() }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (error: Exception) {
                    mainExecutor.execute { onError("Camera unavailable · check front camera access") }
                }
            }, mainExecutor)
        } catch (error: Exception) {
            onError("Gesture model unavailable · sync the project in Android Studio")
        }
        onDispose {
            active.set(false)
            runCatching { provider?.unbindAll() }
            runCatching { recognizer?.close() }
            cameraExecutor.shutdown()
        }
    }
}

private fun isLockAdminActive(context: Context): Boolean {
    val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return manager.isAdminActive(ComponentName(context, LockAdminReceiver::class.java))
}
internal fun lockDevice(context: Context) {
    val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, LockAdminReceiver::class.java)
    check(manager.isAdminActive(admin)) { "Device admin not enabled" }
    manager.lockNow()
}
internal fun dispatchMediaKey(context: Context, key: Int) {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val now = SystemClock.uptimeMillis()
    manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
    manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
}
private fun Float.format1(): String = String.format(java.util.Locale.getDefault(), "%.1f", this)
