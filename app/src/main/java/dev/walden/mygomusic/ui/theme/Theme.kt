package dev.walden.mygomusic.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * MyGo's palette, carried over from the desktop app
 * (internal/app/ui_kit.go `paletteFor`): near-black neutral canvas, a single
 * warm-red accent and an almost invisible hairline grid. Light mode follows
 * the same ratios inverted, with Apple-ish greys.
 */
private val AccentDark = Color(0xFFF45058)
private val AccentLight = Color(0xFFE2333C)

private val MyGoDark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5C1B1E),
    onPrimaryContainer = Color(0xFFFFDAD8),
    inversePrimary = Color(0xFFB3262E),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF2E2321),
    onSecondaryContainer = Color(0xFFF7DCD6),
    tertiary = Color(0xFFD9C08A),
    onTertiary = Color(0xFF3B2F14),
    tertiaryContainer = Color(0xFF2B271D),
    onTertiaryContainer = Color(0xFFF4E7C6),
    background = Color(0xFF141416),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFF9C9CA4),
    surfaceTint = AccentDark,
    inverseSurface = Color(0xFFF5F5F7),
    inverseOnSurface = Color(0xFF1D1D1F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF26262B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A3F),
    surfaceDim = Color(0xFF141416),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF1A1A1D),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF242428),
    surfaceContainerHighest = Color(0xFF2C2C31),
)

private val MyGoLight = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF400005),
    inversePrimary = Color(0xFFFFB3B0),
    secondary = Color(0xFF775651),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E4E0),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF6E5C2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8E7C0),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF5E5E66),
    surfaceTint = AccentLight,
    inverseSurface = Color(0xFF1D1D1F),
    inverseOnSurface = Color(0xFFF5F5F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF8A8A92),
    outlineVariant = Color(0xFFD9D9DE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E3E7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFF4F4F6),
    surfaceContainerHigh = Color(0xFFEDEDF0),
    surfaceContainerHighest = Color(0xFFE6E6EA),
)

/**
 * Extras the Material roles can't express: MyGo's translucent hairline and
 * hover washes, and the cover-derived scrim used behind artwork.
 */
@Immutable
data class MyGoExtras(
    val isDark: Boolean,
    val hairline: Color,
    val hover: Color,
    val press: Color,
    val faint: Color,
    val miniPlayer: Color,
    val heroScrim: Color,
    val nowPlayingScrim: Color,
)

val LocalMyGoExtras = staticCompositionLocalOf {
    MyGoExtras(
        isDark = true,
        hairline = Color.White.copy(alpha = 0.07f),
        hover = Color.White.copy(alpha = 0.06f),
        press = Color.White.copy(alpha = 0.10f),
        faint = Color.White.copy(alpha = 0.30f),
        miniPlayer = Color(0xFF242428),
        heroScrim = Color.Black.copy(alpha = 0.35f),
        nowPlayingScrim = Color.Black.copy(alpha = 0.55f),
    )
}

private fun extrasFor(dark: Boolean): MyGoExtras = if (dark) {
    MyGoExtras(
        isDark = true,
        hairline = Color.White.copy(alpha = 0.07f),
        hover = Color.White.copy(alpha = 0.06f),
        press = Color.White.copy(alpha = 0.10f),
        faint = Color(0xFF7A7A82),
        miniPlayer = Color(0xFF242428),
        heroScrim = Color.Black.copy(alpha = 0.35f),
        nowPlayingScrim = Color.Black.copy(alpha = 0.55f),
    )
} else {
    MyGoExtras(
        isDark = false,
        hairline = Color.Black.copy(alpha = 0.07f),
        hover = Color.Black.copy(alpha = 0.04f),
        press = Color.Black.copy(alpha = 0.08f),
        faint = Color(0xFF9A9AA1),
        miniPlayer = Color(0xFFF1F1F4),
        heroScrim = Color.Black.copy(alpha = 0.22f),
        nowPlayingScrim = Color.Black.copy(alpha = 0.35f),
    )
}

private val base = Typography()

/**
 * MyGo's type scale is compact — 13–15sp body, 11–12.5sp captions, heavier
 * weights only on the few titles that lead a page. Sizes map onto the M3
 * roles so components (TopAppBar, ListItem, …) inherit the right voice.
 */
private val MyGoTypography = Typography(
    displaySmall = base.displaySmall.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = base.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = base.titleMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = base.titleSmall.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = base.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = base.bodySmall.copy(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = base.labelLarge.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = base.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = base.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp,
    ),
)

enum class ThemeMode { System, Light, Dark }

@Composable
fun MyGoTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> MyGoDark
        else -> MyGoLight
    }
    val extras = extrasFor(dark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = scheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = scheme.background.toArgb()
        }
    }
    CompositionLocalProvider(LocalMyGoExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MyGoTypography,
            shapes = MyGoShapes,
            content = content,
        )
    }
}

val MyGoShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

