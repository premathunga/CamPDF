package com.campdf.app

import android.os.Bundle
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
import com.campdf.app.ui.WelcomeScreen

class MainActivity : AppCompatActivity() {

    private val vm: AppViewModel by viewModels()
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null && vm.appLock) {
            val can = BiometricManager.from(this).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
            locked = can
        }

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
            CamPDFTheme(darkTheme = dark) {
                if (locked) {
                    LaunchedEffect(Unit) { promptUnlock() }
                    LockScreen { promptUnlock() }
                } else {
                    Root(vm)
                }
            }
        }
    }

    private fun promptUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked = false
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

    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.Welcome -> WelcomeScreen { vm.completeWelcome() }
            Screen.Home -> HomeScreen(vm)
            Screen.Camera -> CameraScreen(vm)
            Screen.Crop -> CropScreen(vm)
            Screen.Review -> ReviewScreen(vm)
            Screen.SignPad -> SignPadScreen(vm)
            Screen.SignPlace -> SignPlaceScreen(vm)
            Screen.Export -> ExportScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
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
