package com.atri.photofinder.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 相册权限的检查工具。
 *
 * 为什么需要这个文件：
 *   安卓 13 以后，"读相册"这个权限**光在清单文件里写是不够的**，
 *   必须在 App 运行时弹窗问用户要。用户点了允许，才看得到照片。
 *   之前漏了这一步，所以列出来是空的。
 */
object MediaPermissions {

    /** 权限有没有被允许（含"只允许部分照片"的情况） */
    fun hasAccess(context: Context): Boolean = when {
        // 安卓 14 以上：允许"部分照片"也算能用
        Build.VERSION.SDK_INT >= 34 ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

        // 安卓 13：只认这一个
        Build.VERSION.SDK_INT >= 33 ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES)

        // 安卓 12 及以下：用旧权限
        else ->
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 是不是"只允许了部分照片"。
     * 这种情况能用，但只能看到用户勾选的那几张，界面上应该提醒一下。
     */
    fun isPartialAccessOnly(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    /**
     * 向系统申请时要传的权限清单。
     *
     * ⚠️ 关键细节（踩过）：
     *   安卓 14 以上，如果有"部分照片"那条权限，申请列表里就**只能放**
     *   READ_MEDIA_IMAGES 这一条。
     *   如果两条都放进去，系统就只会弹"仅选择照片"的窗口，用户选完还是
     *   只能看到勾选的那几张 —— 而不是弹出"允许全部 / 仅选择照片"让你选。
     */
    fun permissionsToRequest(): Array<String> = when {
        // 安卓 13 及以上：只要这一条，系统会自己弹出"允许全部/仅选择照片"
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )

        // 安卓 12 及以下：用旧权限
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
