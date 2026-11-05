package app.seniorremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colors specific to the remote. Every pair here meets at least 7:1 contrast. */
@Immutable
data class RemoteColors(
    val button: Color,
    val onButton: Color,
    val okButton: Color,
    val onOkButton: Color,
    val pressed: Color,
    val onPressed: Color,
    val power: Color,
    val onPower: Color,
    val statusReady: Color,
    val statusBusy: Color,
    val statusProblem: Color,
    val statusBackground: Color,
    val onStatusBackground: Color,
)

private val LightRemote = RemoteColors(
    button = Color(0xFF1C2433),
    onButton = Color.White,
    okButton = Color(0xFF0B3D91),
    onOkButton = Color.White,
    pressed = Color(0xFFFFC233),
    onPressed = Color.Black,
    power = Color(0xFF8E1010),
    onPower = Color.White,
    statusReady = Color(0xFF0F5D22),
    statusBusy = Color(0xFF0B3D91),
    statusProblem = Color(0xFF8E1010),
    statusBackground = Color(0xFFF1F3F6),
    onStatusBackground = Color(0xFF000000),
)

private val DarkRemote = RemoteColors(
    button = Color(0xFF2A2F3A),
    onButton = Color.White,
    okButton = Color(0xFF4F8DFF),
    onOkButton = Color.Black,
    pressed = Color(0xFFFFD25C),
    onPressed = Color.Black,
    power = Color(0xFFFF6B6B),
    onPower = Color.Black,
    statusReady = Color(0xFF7BE495),
    statusBusy = Color(0xFF9CC2FF),
    statusProblem = Color(0xFFFF9C9C),
    statusBackground = Color(0xFF15181E),
    onStatusBackground = Color.White,
)

val LocalRemoteColors = staticCompositionLocalOf { LightRemote }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B3D91),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF1C2433),
    error = Color(0xFF8E1010),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF15181E),
    onSurfaceVariant = Color(0xFFE6E8EC),
    error = Color(0xFFFF9C9C),
    onError = Color.Black,
)

private val LargeTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SeniorRemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalRemoteColors provides if (dark) DarkRemote else LightRemote) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = LargeTypography,
            content = content,
        )
    }
}
