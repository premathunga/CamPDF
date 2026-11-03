package com.campdf.app.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.campdf.app.AppViewModel
import com.campdf.app.R
import com.campdf.app.authenticators
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val lang = if (tag.isNotEmpty()) tag.substringBefore('-') else Locale.getDefault().language
    val langIndex = when (lang) { "si" -> 1; "ta" -> 2; else -> 0 }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.settings), onBack = { vm.onBack() })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Shield, null, tint = c.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.offline_title), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.offline_desc), color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(stringResource(R.string.language))
                    Segmented(listOf("English", "සිංහල", "தமிழ்"), langIndex) { i ->
                        val t = when (i) { 1 -> "si"; 2 -> "ta"; else -> "en" }
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(t))
                    }
                    Spacer(Modifier.height(6.dp))
                    Label(stringResource(R.string.theme))
                    Segmented(
                        listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_dark), stringResource(R.string.theme_system)),
                        when (vm.themeMode) { 1 -> 0; 2 -> 1; else -> 2 }
                    ) { i -> vm.setTheme(when (i) { 0 -> 1; 1 -> 2; else -> 0 }) }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Label(stringResource(R.string.default_quality))
                    Spacer(Modifier.height(6.dp))
                    QualityRow(vm.defaultQuality == 0, stringResource(R.string.quality_high), stringResource(R.string.quality_high_d)) { vm.updateDefaultQuality(0) }
                    QualityRow(vm.defaultQuality == 1, stringResource(R.string.quality_bal), stringResource(R.string.quality_bal_d)) { vm.updateDefaultQuality(1) }
                    QualityRow(vm.defaultQuality == 2, stringResource(R.string.quality_compact), stringResource(R.string.quality_compact_d)) { vm.updateDefaultQuality(2) }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SwitchRow(stringResource(R.string.auto_detect), stringResource(R.string.auto_detect_d), vm.autoDetect) { vm.updateAutoDetect(it) }
                    SwitchRow(stringResource(R.string.snap_a4), stringResource(R.string.snap_a4_d), vm.snapA4) { vm.updateSnapA4(it) }
                    SwitchRow(stringResource(R.string.app_lock), stringResource(R.string.app_lock_d), vm.appLock) { want ->
                        if (!want) {
                            vm.updateAppLock(false)
                        } else {
                            val ok = BiometricManager.from(ctx).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
                            if (ok) vm.updateAppLock(true)
                            else Toast.makeText(ctx, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.about),
                color = c.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) androidx.compose.ui.graphics.Color.White else c.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QualityRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Text(desc, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Text(desc, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
