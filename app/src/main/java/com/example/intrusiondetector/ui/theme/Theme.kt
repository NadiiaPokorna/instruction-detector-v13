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
