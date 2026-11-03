package com.campdf.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.R

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(c.primary.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.surfaceContainerHigh)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.VerifiedUser, null, tint = c.primary, modifier = Modifier.size(46.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("CamPDF", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = c.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.welcome_sub),
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Rounded.WifiOff, R.string.w1_t, R.string.w1_d)
            Feature(Icons.Rounded.Lock, R.string.w2_t, R.string.w2_d)
            Feature(Icons.Rounded.AccountBalance, R.string.w3_t, R.string.w3_d)
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            stringResource(R.string.get_started),
            Modifier.fillMaxWidth(),
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            onClick = onStart
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.no_account),
            color = c.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, @StringRes title: Int, @StringRes desc: Int) {
    val c = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = c.secondary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(desc), color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Lock, null, tint = c.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.unlock_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.onBackground)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.unlock), Modifier.fillMaxWidth(), onClick = onUnlock)
    }
}
