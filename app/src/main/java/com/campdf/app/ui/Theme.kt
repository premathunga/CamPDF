package com.campdf.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2563EB),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF60A5FA),
    onSecondary = Color.White,
    tertiary = Color(0xFF38BDF8),
    background = Color(0xFF0A0E17),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0A0E17),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E2738),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF070A10),
    surfaceContainerLow = Color(0xFF101622),
    surfaceContainer = Color(0xFF151B28),
    surfaceContainerHigh = Color(0xFF1A2234),
    surfaceContainerHighest = Color(0xFF242F45),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF242F45),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF3B82F6),
    onSecondary = Color.White,
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626)
)

@Composable
fun CamPDFTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
