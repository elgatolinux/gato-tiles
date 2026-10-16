package com.gatotiles.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatotiles.GatoApp
import com.gatotiles.data.ThemeMode
import com.gatotiles.util.ColorUtil
import kotlin.math.min

/** Builds a full Material 3 color scheme from one seed color (Namida-style dynamic theming). */
fun schemeFromSeed(seed: Int, dark: Boolean, amoled: Boolean): ColorScheme {
    val o = ColorUtil.toOklch(seed)
    val h = if (o.c < 0.02) 285.0 else o.h
    val c = if (o.c < 0.02) 0.06 else min(o.c, 0.16)
    fun t(l: Double, ch: Double, hue: Double = h) = Color(ColorUtil.fromOklch(l, ch, hue))
    val h3 = (h + 60) % 360
    return if (dark) {
        val surfaceL = if (amoled) 0.0 else 0.155
        val sc = if (amoled) 0.0 else 0.018
        darkColorScheme(
            primary = t(0.80, c),
            onPrimary = t(0.24, c * 0.6),
            primaryContainer = t(0.36, c * 0.75),
            onPrimaryContainer = t(0.93, c * 0.35),
            inversePrimary = t(0.45, c),
            secondary = t(0.80, c * 0.45),
            onSecondary = t(0.25, c * 0.3),
            secondaryContainer = t(0.33, c * 0.35),
            onSecondaryContainer = t(0.92, c * 0.2),
            tertiary = t(0.80, c * 0.65, h3),
            onTertiary = t(0.25, c * 0.4, h3),
            tertiaryContainer = t(0.35, c * 0.5, h3),
            onTertiaryContainer = t(0.93, c * 0.25, h3),
            background = t(surfaceL, sc),
            onBackground = t(0.93, 0.012),
            surface = t(surfaceL, sc),
            onSurface = t(0.93, 0.012),
            surfaceVariant = t(0.30, 0.025),
            onSurfaceVariant = t(0.80, 0.025),
            surfaceTint = t(0.80, c),
            inverseSurface = t(0.92, 0.012),
            inverseOnSurface = t(0.25, 0.015),
            outline = t(0.62, 0.025),
            outlineVariant = t(0.36, 0.025),
            scrim = Color.Black,
            surfaceBright = t(if (amoled) 0.20 else 0.27, 0.02),
            surfaceDim = t(surfaceL, sc),
            surfaceContainerLowest = t(if (amoled) 0.0 else 0.12, sc),
            surfaceContainerLow = t(if (amoled) 0.09 else 0.18, 0.02),
            surfaceContainer = t(if (amoled) 0.12 else 0.20, 0.022),
            surfaceContainerHigh = t(if (amoled) 0.16 else 0.235, 0.024),
            surfaceContainerHighest = t(if (amoled) 0.20 else 0.27, 0.026),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            primary = t(0.47, c),
            onPrimary = t(0.99, 0.01),
            primaryContainer = t(0.90, c * 0.4),
            onPrimaryContainer = t(0.26, c * 0.7),
            inversePrimary = t(0.80, c),
            secondary = t(0.50, c * 0.35),
            onSecondary = t(0.99, 0.01),
            secondaryContainer = t(0.90, c * 0.25),
            onSecondaryContainer = t(0.26, c * 0.3),
            tertiary = t(0.50, c * 0.6, h3),
            onTertiary = t(0.99, 0.01, h3),
            tertiaryContainer = t(0.90, c * 0.35, h3),
            onTertiaryContainer = t(0.26, c * 0.4, h3),
            background = t(0.975, 0.012),
            onBackground = t(0.20, 0.015),
            surface = t(0.975, 0.012),
            onSurface = t(0.20, 0.015),
            surfaceVariant = t(0.91, 0.022),
            onSurfaceVariant = t(0.42, 0.025),
            surfaceTint = t(0.47, c),
            inverseSurface = t(0.28, 0.015),
            inverseOnSurface = t(0.95, 0.012),
            outline = t(0.58, 0.025),
            outlineVariant = t(0.83, 0.022),
            scrim = Color.Black,
            surfaceBright = t(0.985, 0.01),
            surfaceDim = t(0.88, 0.015),
            surfaceContainerLowest = t(1.0, 0.0),
            surfaceContainerLow = t(0.96, 0.014),
            surfaceContainer = t(0.945, 0.018),
            surfaceContainerHigh = t(0.93, 0.02),
            surfaceContainerHighest = t(0.915, 0.022),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
        )
    }
}

@Composable
private fun animate(c: Color): Color = animateColorAsState(c, tween(600), label = "scheme").value

/** Smoothly animates every color of the scheme when the seed changes. */
@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animate(primary),
    onPrimary = animate(onPrimary),
    primaryContainer = animate(primaryContainer),
    onPrimaryContainer = animate(onPrimaryContainer),
    inversePrimary = animate(inversePrimary),
    secondary = animate(secondary),
    onSecondary = animate(onSecondary),
    secondaryContainer = animate(secondaryContainer),
    onSecondaryContainer = animate(onSecondaryContainer),
    tertiary = animate(tertiary),
    onTertiary = animate(onTertiary),
    tertiaryContainer = animate(tertiaryContainer),
    onTertiaryContainer = animate(onTertiaryContainer),
    background = animate(background),
    onBackground = animate(onBackground),
    surface = animate(surface),
    onSurface = animate(onSurface),
    surfaceVariant = animate(surfaceVariant),
    onSurfaceVariant = animate(onSurfaceVariant),
    surfaceTint = animate(surfaceTint),
    outline = animate(outline),
    outlineVariant = animate(outlineVariant),
    surfaceContainerLowest = animate(surfaceContainerLowest),
    surfaceContainerLow = animate(surfaceContainerLow),
    surfaceContainer = animate(surfaceContainer),
    surfaceContainerHigh = animate(surfaceContainerHigh),
    surfaceContainerHighest = animate(surfaceContainerHighest),
)

@Composable
fun lexendFamily(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        fun f(w: Int) = Font(
            path = "fonts/LexendDeca.ttf",
            assetManager = assets,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
        FontFamily(f(300), f(400), f(500), f(600), f(700), f(800), f(900))
    }
}

private fun typography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.l() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.l().copy(fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.l().copy(fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.l().copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.l().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.l().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.l().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.l().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.l().copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = base.titleSmall.l().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.l(),
        bodyMedium = base.bodyMedium.l(),
        bodySmall = base.bodySmall.l(),
        labelLarge = base.labelLarge.l().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.l().copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.l().copy(fontWeight = FontWeight.Medium),
    )
}

val GatoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
fun GatoTheme(seed: Int, mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val scheme = remember(seed, dark, mode) { schemeFromSeed(seed, dark, mode == ThemeMode.AMOLED) }
    val family = lexendFamily()
    val typo = remember(family) { typography(family) }
    MaterialTheme(colorScheme = scheme.animated(), typography = typo, shapes = GatoShapes, content = content)
}

/**
 * Material sheets and dialogs pick light or dark system bars from the phone setting; this
 * makes them follow the app theme instead (no white navigation bar under a dark sheet).
 */
@Composable
fun FollowAppTheme(content: @Composable () -> Unit) {
    val mode = GatoApp.app.settings.state.collectAsState().value.themeMode
    val dark = isDarkTheme(mode)
    val base = LocalConfiguration.current
    val cfg = remember(base, dark) {
        Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }
    CompositionLocalProvider(LocalConfiguration provides cfg, content = content)
}
