package com.waqtak.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.waqtak.app.R

object WaqtakColors {
    val Ivory = Color(0xFFFEFBF2)
    val IvoryDeep = Color(0xFFF6F0E0)
    val Sage = Color(0xFF5E7A55)
    val SageDark = Color(0xFF3F5A3A)
    val SageLight = Color(0xFFDCE7D3)
    val Sky = Color(0xFF86BCCB)
    val SkyLight = Color(0xFFDDEFF4)
    val SkyDark = Color(0xFF2F6F82)
    val Peach = Color(0xFFF6C8A8)
    val PeachLight = Color(0xFFFCE9DC)
    val PeachDark = Color(0xFF9A5A34)
    val Ink = Color(0xFF26302A)
    val InkSoft = Color(0xFF4E5A52)
    val Sun = Color(0xFFF2B84B)
    val Moon = Color(0xFFF1E3B0)
    val Night = Color(0xFF34495E)
    val Coffee = Color(0xFF7B5135)
    val Tea = Color(0xFFB9713A)
    val Juice = Color(0xFFF39C38)
    val Berry = Color(0xFFC0566B)
    val Plate = Color(0xFFFFFFFF)
    val Shadow = Color(0x1A26302A)
}

val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
)

private val scheme = lightColorScheme(
    primary = WaqtakColors.Sage,
    onPrimary = Color.White,
    primaryContainer = WaqtakColors.SageLight,
    onPrimaryContainer = WaqtakColors.SageDark,
    secondary = WaqtakColors.SkyDark,
    onSecondary = Color.White,
    secondaryContainer = WaqtakColors.SkyLight,
    onSecondaryContainer = Color(0xFF15414E),
    tertiary = WaqtakColors.PeachDark,
    onTertiary = Color.White,
    tertiaryContainer = WaqtakColors.PeachLight,
    onTertiaryContainer = Color(0xFF5A2E12),
    background = WaqtakColors.Ivory,
    onBackground = WaqtakColors.Ink,
    surface = WaqtakColors.Ivory,
    onSurface = WaqtakColors.Ink,
    surfaceVariant = WaqtakColors.IvoryDeep,
    onSurfaceVariant = WaqtakColors.InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF7EC),
    surfaceContainer = Color(0xFFF7F2E6),
    surfaceContainerHigh = Color(0xFFF2ECDD),
    surfaceContainerHighest = Color(0xFFECE5D4),
    outline = Color(0xFF8A9488),
    outlineVariant = Color(0xFFD5D9CF),
    error = Color(0xFFB3261E),
)

private fun base(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Cairo, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val typography = Typography(
    displayLarge = base(56, FontWeight.Bold, 64),
    displayMedium = base(44, FontWeight.Bold, 52),
    displaySmall = base(36, FontWeight.Bold, 44),
    headlineLarge = base(30, FontWeight.Bold, 40),
    headlineMedium = base(26, FontWeight.Bold, 36),
    headlineSmall = base(22, FontWeight.SemiBold, 32),
    titleLarge = base(20, FontWeight.SemiBold, 30),
    titleMedium = base(17, FontWeight.SemiBold, 26),
    titleSmall = base(15, FontWeight.SemiBold, 22),
    bodyLarge = base(16, FontWeight.Normal, 26),
    bodyMedium = base(15, FontWeight.Normal, 24),
    bodySmall = base(13, FontWeight.Normal, 20),
    labelLarge = base(15, FontWeight.SemiBold, 22),
    labelMedium = base(13, FontWeight.SemiBold, 18),
    labelSmall = base(12, FontWeight.SemiBold, 16),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun WaqtakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
