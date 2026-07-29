package net.bdfz.weibian.ui

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 视觉基调：宣纸与墨，朱砂点睛。
 *
 * 刻意避开「教材软件」的高饱和蓝与大色块：底色取宣纸的暖白，
 * 文字取松烟墨的偏冷黑，强调色用朱砂（印章、句读）与藤黄（成就）。
 * 深色模式不是把白翻黑，而是换成夜读的靛底暖字。
 */

// 宣纸 / 松烟
private val PaperLight = Color(0xFFF7F3EA)
private val PaperRaised = Color(0xFFFFFDF7)
private val InkDark = Color(0xFF1C1A17)
private val InkSoft = Color(0xFF4A453D)

// 靛夜
private val NightBase = Color(0xFF14161B)
private val NightRaised = Color(0xFF1D2028)
private val NightInk = Color(0xFFECE5D8)
private val NightInkSoft = Color(0xFFA9A294)

// 朱砂 / 青瓷 / 藤黄
private val Cinnabar = Color(0xFFA8322A)
private val CinnabarLight = Color(0xFFC8524A)
private val Celadon = Color(0xFF5C7F72)
private val CeladonLight = Color(0xFF86A99C)
private val Gamboge = Color(0xFFB8862B)

private val LightScheme = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFFFFF8F2),
    primaryContainer = Color(0xFFF3DED9),
    onPrimaryContainer = Color(0xFF3F0F0B),
    secondary = Celadon,
    onSecondary = Color(0xFFF3F8F5),
    secondaryContainer = Color(0xFFDCE7E1),
    onSecondaryContainer = Color(0xFF1B2C25),
    tertiary = Gamboge,
    onTertiary = Color(0xFFFFFBF0),
    background = PaperLight,
    onBackground = InkDark,
    surface = PaperLight,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFE8E1D4),
    onSurfaceVariant = InkSoft,
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFB9B0A0),
    outlineVariant = Color(0xFFD8D0C1),
    error = Color(0xFF9A3126),
)

private val DarkScheme = darkColorScheme(
    primary = CinnabarLight,
    onPrimary = Color(0xFF2A0906),
    primaryContainer = Color(0xFF52201B),
    onPrimaryContainer = Color(0xFFF7DDD9),
    secondary = CeladonLight,
    onSecondary = Color(0xFF13241E),
    secondaryContainer = Color(0xFF2C4239),
    onSecondaryContainer = Color(0xFFD8E8E0),
    tertiary = Color(0xFFD9AC57),
    onTertiary = Color(0xFF2A1D02),
    background = NightBase,
    onBackground = NightInk,
    surface = NightBase,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF2A2E37),
    onSurfaceVariant = NightInkSoft,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = Color(0xFF252932),
    outline = Color(0xFF565C68),
    outlineVariant = Color(0xFF393F49),
    error = Color(0xFFE0837A),
)

/** 古籍正文排版：字大、行疏、字距略松，长时间读不累。 */
val ClassicalTextStyle = TextStyle(
    fontSize = 21.sp,
    lineHeight = 38.sp,
    letterSpacing = 1.2.sp,
    fontWeight = FontWeight.Normal,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

val TranslationTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.3.sp,
)

val AnnotationTextStyle = TextStyle(
    fontSize = 14.5.sp,
    lineHeight = 25.sp,
    letterSpacing = 0.2.sp,
)

private val WeibianTypography = Typography()

/** 正文字号可调，读古文的人对字号很敏感。 */
val LocalReadingScale = staticCompositionLocalOf { 1.0f }

@Composable
fun WeibianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 动态取色跟随壁纸，会破坏宣纸墨色的整体感，默认关闭。 */
    dynamicColor: Boolean = false,
    readingScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalReadingScale provides readingScale) {
        MaterialTheme(
            colorScheme = scheme,
            typography = WeibianTypography,
            content = content,
        )
    }
}
