package com.campdf.app

import android.os.Build
import androidx.biometric.BiometricManager

fun authenticators(): Int =
    if (Build.VERSION.SDK_INT >= 30)
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    else
        BiometricManager.Authenticators.BIOMETRIC_WEAK
