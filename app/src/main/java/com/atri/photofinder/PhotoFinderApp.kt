package com.atri.photofinder

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/**
 * App 级别的初始化。
 *
 * 目前只做一件事：告诉图片加载器（Coil）"怎么加载图片"。
 *
 * 为什么要显式写在这里，而不是靠自动发现：
 *   1. **GIF 动图**需要专门的解码器。虽然引入依赖后通常会自动注册，
 *      但显式注册最保险 —— 不注册的话 GIF 只会显示第一帧、不会动。
 *   2. 以后第 5 阶段要联网加载图片时，这里也已经准备好了网络模块。
 */
class PhotoFinderApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // GIF / 动图支持
                add(GifDecoder.Factory())
                // 网络加载（第 5 阶段接 AI 时可能用得上）
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() }))
            }
            .build()
}
