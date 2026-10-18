package com.atri.photofinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 浅色主题：**白色 + 浅蓝**，低调清爽。
 *
 * ⚠️ 这里**关掉了"跟随壁纸取色"**（原来开着 `dynamicColor`）。
 *
 * 为什么：安卓 12 以上默认会用你的手机壁纸来配色，
 * 于是**颜色完全不可控** —— 壁纸是紫的，App 就变紫的，
 * 和"白色 + 浅蓝"的要求直接冲突。所以这里**写死自己的一套色**。
 */
private val LightColors = lightColorScheme(
    // 主色（蓝）
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,

    // 次要色（更淡的蓝灰）
    secondary = BlueSecondary,
    onSecondary = Color.White,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,

    // 第三色（用得很少，跟主色同调）
    tertiary = BlueSecondary,
    onTertiary = Color.White,

    // 背景 / 表面：大面积白，层次靠极浅的蓝灰拉
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AppSurface,
    surfaceContainer = AppSurfaceVariant,
    surfaceContainerHigh = AppSurfaceVariant,
    surfaceContainerHighest = OutlineVariantLight,

    // 线条
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,

    // 状态
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerRed,
    onErrorContainer = Color(0xFF410E0B),
)

/** 深色主题：夜里看图不刺眼 */
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF06202F),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFCFE4F5),

    secondary = DarkPrimary,
    onSecondary = Color(0xFF06202F),
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = Color(0xFFCFE4F5),

    tertiary = DarkPrimary,
    onTertiary = Color(0xFF06202F),

    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB4C4D2),
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF2C3945),

    outline = Color(0xFF4A5A68),
    outlineVariant = Color(0xFF334049),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * App 的整体外观（颜色、字体）。
 *
 * @param darkTheme 是否深色。默认跟随系统。
 */
@Composable
fun PhotoFinderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
