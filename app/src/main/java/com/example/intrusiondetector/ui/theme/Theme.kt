package com.example.intrusiondetector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val CalibratingColor = Color(0xFF78909C)
val QuietColor = Color(0xFF43A047)
val VibrationColor = Color(0xFFFFA000)
val IntrusionColor = Color(0xFFE53935)

val AxisXColor = Color(0xFFE53935)
val AxisYColor = Color(0xFF43A047)
val AxisZColor = Color(0xFF1E88E5)

// Стани BLE-з'єднання
val BleIdleColor = Color(0xFF78909C)
val BleBusyColor = Color(0xFF1E88E5)
val BleConnectedColor = Color(0xFF43A047)
val BleTransferColor = Color(0xFF00897B)
val BleReconnectingColor = Color(0xFFFFB300)
val BleErrorColor = Color(0xFFE53935)

// Термінал: темний фон незалежно від теми
val TerminalBackground = Color(0xFF12161B)
val TerminalText = Color(0xFFCFD8DC)
val TerminalTx = Color(0xFF64B5F6)
val TerminalRx = Color(0xFF81C784)
val TerminalSys = Color(0xFF90A4AE)
val TerminalWarn = Color(0xFFFFD54F)
val TerminalErr = Color(0xFFEF5350)

@Composable
fun IntrusionDetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // minSdk 31, тому динамічні кольори Material You доступні завжди.
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
