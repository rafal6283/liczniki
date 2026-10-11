package pl.kreska.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    const val SIZE = 6
    private val light = longArrayOf(0xFF0E7466, 0xFF3456B4, 0xFFB4600C, 0xFF8B3CA6, 0xFFB0334C, 0xFF5B771A)
    private val dark = longArrayOf(0xFF3CC3AC, 0xFF86A3F2, 0xFFEDA04A, 0xFFCD92E2, 0xFFF08499, 0xFFA9C75E)

    fun lightArgb(i: Int): Int = light[Math.floorMod(i, SIZE)].toInt()

    @Composable
    fun color(i: Int): Color =
        Color(if (isSystemInDarkTheme()) dark[Math.floorMod(i, SIZE)] else light[Math.floorMod(i, SIZE)])

    @Composable
    fun onColor(): Color = if (isSystemInDarkTheme()) Color(0xFF0E141A) else Color.White

    @Composable
    fun live(): Color = if (isSystemInDarkTheme()) Color(0xFFEDA04A) else Color(0xFFB4600C)

    @Composable
    fun liveSoft(): Color = if (isSystemInDarkTheme()) Color(0xFF3A2A17) else Color(0xFFF7E8D5)

    fun nextFree(used: List<Int>): Int {
        for (i in 0 until SIZE) if (i !in used) return i
        return used.size % SIZE
    }
}

@Composable
fun KreskaTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF3CC3AC),
            onPrimary = Color(0xFF05211C),
            primaryContainer = Color(0xFF1C3D38),
            onPrimaryContainer = Color(0xFFBFEDE4),
            secondaryContainer = Color(0xFF1F3A36),
            onSecondaryContainer = Color(0xFFBFEDE4),
            background = Color(0xFF10151B),
            onBackground = Color(0xFFE3E9EF),
            surface = Color(0xFF18202A),
            onSurface = Color(0xFFE3E9EF),
            surfaceVariant = Color(0xFF222C37),
            onSurfaceVariant = Color(0xFF93A1B1),
            surfaceContainer = Color(0xFF18202A),
            surfaceContainerHigh = Color(0xFF1E2731),
            surfaceContainerHighest = Color(0xFF222C37),
            outline = Color(0xFF465464),
            outlineVariant = Color(0xFF2B3642),
            error = Color(0xFFF2867D),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0E7466),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCDEDE7),
            onPrimaryContainer = Color(0xFF03302A),
            secondaryContainer = Color(0xFFD7EBE7),
            onSecondaryContainer = Color(0xFF0B3B34),
            background = Color(0xFFEEF1F4),
            onBackground = Color(0xFF17202B),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF17202B),
            surfaceVariant = Color(0xFFE4E9EE),
            onSurfaceVariant = Color(0xFF5A6777),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFF6F8FA),
            surfaceContainerHighest = Color(0xFFE4E9EE),
            outline = Color(0xFFA9B4BF),
            outlineVariant = Color(0xFFD3DAE1),
            error = Color(0xFFB3261E),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
