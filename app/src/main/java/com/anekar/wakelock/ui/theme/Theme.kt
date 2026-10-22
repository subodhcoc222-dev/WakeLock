package com.anekar.wakelock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PureBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF0D0D0E)
val ElevatedSurface = Color(0xFF141416)
val SubtleBorder = Color(0xFF242428)
val LaserGreen = Color(0xFF30D158)
val ThreatCrimson = Color(0xFFFF3B30)
val WarningAmber = Color(0xFFFFD60A)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF8E8E93)
val TextMuted = Color(0xFF48484A)

private val DarkColorScheme = darkColorScheme(
    primary = LaserGreen,
    onPrimary = PureBlack,
    secondary = TextSecondary,
    background = PureBlack,
    surface = DarkSurface,
    surfaceVariant = ElevatedSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = ThreatCrimson
)

val WakelockTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 1.5.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = LaserGreen
    )
)

@Composable
fun WakelockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = WakelockTypography,
        content = content
    )
}
