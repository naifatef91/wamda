package app.barq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.barq.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens from the design canvas (Tokens artboard). Material roles map onto them:
 *   primary            = accent blue            tertiary  = ok green
 *   primaryContainer   = primary-soft           secondary = warn amber
 *   background / surface / onSurface / onSurfaceVariant / outline = bg / surface / ink / muted / line
 */
object BarqPalette {
    // Mint accent on deep navy (home mockup); light theme keeps the same hue, darker for contrast.
    val LightPrimary = Color(0xFF0E9C83)
    val LightPrimarySoft = Color(0xFFD6F4EC)
    val LightBg = Color(0xFFF2F6F6)
    val LightSurface = Color(0xFFFFFFFF)
    val LightInk = Color(0xFF0F1B20)
    val LightMuted = Color(0xFF5B6A6F)
    val LightLine = Color(0xFFD5DFDF)
    val LightOk = Color(0xFF1E7A4C)
    val LightOkSoft = Color(0xFFE1F2E8)
    val LightWarn = Color(0xFFA55A00)
    val LightWarnSoft = Color(0xFFFBEFDD)
    val LightError = Color(0xFFB3261E)

    val DarkPrimary = Color(0xFF5FE3C2)
    val DarkPrimarySoft = Color(0xFF163D36)
    val DarkBg = Color(0xFF0B1419)
    val DarkSurface = Color(0xFF131E25)
    val DarkInk = Color(0xFFE8F0F0)
    val DarkMuted = Color(0xFF93A4A8)
    val DarkLine = Color(0xFF223139)
    val DarkOk = Color(0xFF6FCF9B)
    val DarkOkSoft = Color(0xFF173326)
    val DarkWarn = Color(0xFFF0B260)
    val DarkWarnSoft = Color(0xFF3A2B16)
    val DarkError = Color(0xFFF2A9A3)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = BarqPalette.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = BarqPalette.LightPrimarySoft,
    onPrimaryContainer = Color(0xFF0A6B5A),
    secondary = BarqPalette.LightWarn,
    onSecondary = Color.White,
    secondaryContainer = BarqPalette.LightWarnSoft,
    onSecondaryContainer = BarqPalette.LightWarn,
    tertiary = BarqPalette.LightOk,
    onTertiary = Color.White,
    tertiaryContainer = BarqPalette.LightOkSoft,
    onTertiaryContainer = BarqPalette.LightOk,
    background = BarqPalette.LightBg,
    onBackground = BarqPalette.LightInk,
    surface = BarqPalette.LightSurface,
    onSurface = BarqPalette.LightInk,
    surfaceVariant = BarqPalette.LightBg,
    onSurfaceVariant = BarqPalette.LightMuted,
    outline = BarqPalette.LightLine,
    outlineVariant = BarqPalette.LightLine,
    error = BarqPalette.LightError,
    onError = Color.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = BarqPalette.DarkPrimary,
    onPrimary = Color(0xFF052A23),
    primaryContainer = BarqPalette.DarkPrimarySoft,
    onPrimaryContainer = Color(0xFF8FF0DA),
    secondary = BarqPalette.DarkWarn,
    onSecondary = BarqPalette.DarkBg,
    secondaryContainer = BarqPalette.DarkWarnSoft,
    onSecondaryContainer = BarqPalette.DarkWarn,
    tertiary = BarqPalette.DarkOk,
    onTertiary = BarqPalette.DarkBg,
    tertiaryContainer = BarqPalette.DarkOkSoft,
    onTertiaryContainer = BarqPalette.DarkOk,
    background = BarqPalette.DarkBg,
    onBackground = BarqPalette.DarkInk,
    surface = BarqPalette.DarkSurface,
    onSurface = BarqPalette.DarkInk,
    surfaceVariant = BarqPalette.DarkBg,
    onSurfaceVariant = BarqPalette.DarkMuted,
    outline = BarqPalette.DarkLine,
    outlineVariant = BarqPalette.DarkLine,
    error = BarqPalette.DarkError,
    onError = BarqPalette.DarkBg,
)

/**
 * Display face (Readex Pro, variable weight axis) for titles and numbers,
 * body face (IBM Plex Sans Arabic, static weights) for text. Files in res/font.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object BarqFonts {
    val Display: FontFamily = FontFamily(
        Font(R.font.readex_pro, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.readex_pro, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
    val Body: FontFamily = FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
    )
    val Mono: FontFamily = FontFamily.Monospace
}

val BarqTypography = Typography(
    displayLarge = TextStyle(fontFamily = BarqFonts.Display, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = BarqFonts.Display, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = BarqFonts.Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = BarqFonts.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = BarqFonts.Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = BarqFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

val BarqShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Semantic colors the Material scheme has no slot for. */
data class BarqExtras(
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
)

val LocalBarqExtras = staticCompositionLocalOf {
    BarqExtras(BarqPalette.LightOk, BarqPalette.LightOkSoft, BarqPalette.LightWarn, BarqPalette.LightWarnSoft)
}

val MaterialTheme.extras: BarqExtras
    @Composable get() = LocalBarqExtras.current

@Composable
fun BarqTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extras = if (darkTheme) {
        BarqExtras(BarqPalette.DarkOk, BarqPalette.DarkOkSoft, BarqPalette.DarkWarn, BarqPalette.DarkWarnSoft)
    } else {
        BarqExtras(BarqPalette.LightOk, BarqPalette.LightOkSoft, BarqPalette.LightWarn, BarqPalette.LightWarnSoft)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBarqExtras provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BarqTypography,
            shapes = BarqShapes,
            content = content,
        )
    }
}
