package com.campdf.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.campdf.app.ui.CamPDFTheme
import com.campdf.app.ui.CameraScreen
import com.campdf.app.ui.CropScreen
import com.campdf.app.ui.ExportScreen
import com.campdf.app.ui.HomeScreen
import com.campdf.app.ui.LockScreen
import com.campdf.app.ui.ReviewScreen
import com.campdf.app.ui.SettingsScreen
import com.campdf.app.ui.SignPadScreen
import com.campdf.app.ui.SignPlaceScreen
import com.campdf.app.ui.ViewerScreen
import com.campdf.app.ui.WelcomeScreen

class MainActivity : AppCompatActivity() {

    private val vm: AppViewModel by viewModels()
    private var locked by mutableStateOf(false)

    // v3.9 re-lock: before, App lock only asked when the app was started; coming back from the recent apps
    // opened it without the fingerprint. Now it locks again after the screen went off, or after 60 s away.
    private var leftAt = 0L

    companion object {
        /** false until the user unlocked once in this app process (a restored process starts locked again). */
        private var unlockedInProcess = false
    }
    private var screenOff = false
    private var authenticating = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { screenOff = true }
    }

    private fun canLock(): Boolean =
        vm.appLock && BiometricManager.from(this).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    override fun onStop() {
        super.onStop()
        // the system PIN screen (device credential) also stops this activity: that is not "leaving the app"
        if (!isChangingConfigurations && !authenticating) leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (leftAt > 0L && !locked && canLock()) {
            val away = SystemClock.elapsedRealtime() - leftAt
            if (screenOff || away >= RELOCK_AFTER_MS) locked = true
        }
        leftAt = 0L
        screenOff = false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    /** v3.9: with App lock on, the app's screens are hidden in the recent apps and cannot be screenshotted. */
    private fun applySecureFlag(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // locked at every new start of the app process - also when Android restores the app after closing it in
        // the background (then savedInstanceState is not null, which the old check let through unlocked)
        if (!unlockedInProcess && canLock()) locked = true else unlockedInProcess = true
        applySecureFlag(vm.appLock)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            val dark = when (vm.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val view = LocalView.current
            SideEffect {
                val ic = WindowCompat.getInsetsController(window, view)
                ic.isAppearanceLightStatusBars = !dark
                ic.isAppearanceLightNavigationBars = !dark
            }
            LaunchedEffect(vm.appLock) { applySecureFlag(vm.appLock) }
            LaunchedEffect(locked) { vm.uiLocked = locked }
            CamPDFTheme(darkTheme = dark) {
                // v3.9: the app stays underneath the lock screen (an open scan, a picker result are not lost);
                // the lock screen covers it completely and takes every touch and the back button.
                Box(Modifier.fillMaxSize()) {
                    Root(vm)
                    if (locked) {
                        LaunchedEffect(Unit) { promptUnlock() }
                        BackHandler { moveTaskToBack(true) }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        ) {
                            LockScreen { promptUnlock() }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** v3.4.3: images sent with "Share -> CamPDF" open straight as a new scan. */
    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            else -> return
        }
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        // only content:// images from the sending app; never file paths from other apps
        val ok = uris.filter { it.scheme == "content" }.distinct()
        if (ok.isNotEmpty()) vm.importShared(ok)
        intent.action = null // handled: not again after rotation / recreation
    }

    private fun promptUnlock() {
        if (authenticating) return
        authenticating = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                unlockedInProcess = true
                locked = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
            }
        })
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setAllowedAuthenticators(authenticators())
        if (android.os.Build.VERSION.SDK_INT < 30) {
            builder.setNegativeButtonText(getString(R.string.cancel))
        }
        prompt.authenticate(builder.build())
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val ctx = LocalContext.current

    BackHandler(enabled = vm.screen != Screen.Home && vm.screen != Screen.Welcome) { vm.onBack() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    // v3.8.2: a Surface gives every screen the theme's text / icon colour (before, text without an explicit colour
    // - top bar titles, the back arrow - was black, invisible in the dark theme)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.Welcome -> WelcomeScreen { vm.completeWelcome() }
            Screen.Home -> HomeScreen(vm)
            Screen.Viewer -> ViewerScreen(vm)
            Screen.Camera -> CameraScreen(vm)
            Screen.Crop -> CropScreen(vm)
            Screen.Review -> ReviewScreen(vm)
            Screen.SignPad -> SignPadScreen(vm)
            Screen.SignPlace -> SignPlaceScreen(vm)
            Screen.Export -> ExportScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.PageTool -> com.campdf.app.ui.PageToolScreen(vm)
            Screen.IdIntro -> com.campdf.app.ui.IdIntroScreen(vm)
            Screen.Tools -> com.campdf.app.ui.ToolsScreen(vm)
        }
        if (vm.busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
    }
    }
}

/** App lock: locks again when the app was away this long (or the screen went off meanwhile). */
private const val RELOCK_AFTER_MS = 60_000L
