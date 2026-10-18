package com.atri.photofinder.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色：**白色 + 浅蓝**，整体低调。
 *
 * ## 为什么不用默认的紫色
 * 默认模板是紫色，看久了累眼，而且和"相册工具"的气质不搭。
 * 这里用**低饱和度的蓝**做点缀，大面积留白，让照片本身成为主角。
 *
 * ## 设计原则
 *  - **背景和卡片都接近纯白**，只在需要区分层次的地方用极浅的蓝灰
 *  - **主色（primary）只用在中度饱和的蓝**，不刺眼
 *  - **容器色（container）是很浅的蓝**，用来铺"选中""提示"这类背景
 *  - 正文用**近黑**，次要文字用**蓝灰**，对比度都够
 */

// ---------- 主色（蓝） ----------
/** 主色：低饱和的沉稳蓝，用在按钮、强调文字 */
val BluePrimary = Color(0xFF3E6E96)
/** 主色的浅容器：很浅的蓝，铺在选中项/提示条背景上 */
val BluePrimaryContainer = Color(0xFFD8E8F5)
/** 浅容器上的文字色（要够深才看得清） */
val BlueOnPrimaryContainer = Color(0xFF10334D)

// ---------- 次要色（更淡的蓝灰） ----------
val BlueSecondary = Color(0xFF5F7F99)
val BlueSecondaryContainer = Color(0xFFE4EFF7)
val BlueOnSecondaryContainer = Color(0xFF1B3446)

// ---------- 背景 / 表面 ----------
/**
 * 页面背景：**很浅的蓝白**。
 *
 * ⚠️ 不能和卡片一样白，否则**白色的卡片会"消失"在背景里**，
 *    界面看起来就是一整片白、没有层次。所以背景比卡片略深一点点。
 */
val AppBackground = Color(0xFFF4F8FC)
/** 卡片表面：纯白，靠和背景的微弱差异拉出层次 */
val AppSurface = Color(0xFFFFFFFF)
/** 次级表面：浅蓝灰，用于输入框底色、缩略图占位 */
val AppSurfaceVariant = Color(0xFFEDF3F9)

// ---------- 文字 ----------
/** 主要文字：近黑，比纯黑柔和 */
val TextPrimary = Color(0xFF1B2430)
/** 次要文字：蓝灰 */
val TextSecondary = Color(0xFF55697A)

// ---------- 线条 ----------
/** 边框/分割线：浅蓝灰 */
val OutlineLight = Color(0xFFC9D9E6)
/** 更浅的分割线 */
val OutlineVariantLight = Color(0xFFE3EDF5)

// ---------- 状态色（保持克制） ----------
val ErrorRed = Color(0xFFB3261E)
val ErrorContainerRed = Color(0xFFF9DEDC)

// ---------- 深色模式（夜里看图用） ----------
val DarkPrimary = Color(0xFF9CC4E4)
val DarkPrimaryContainer = Color(0xFF1E3A52)
val DarkBackground = Color(0xFF11161B)
val DarkSurface = Color(0xFF171D23)
val DarkSurfaceVariant = Color(0xFF222C35)
val DarkTextPrimary = Color(0xFFE3EAF0)
