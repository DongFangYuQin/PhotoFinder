package com.atri.photofinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.atri.photofinder.ui.MainScreen
import com.atri.photofinder.ui.PhotoViewModel
import com.atri.photofinder.ui.theme.PhotoFinderTheme

/**
 * App 的入口。
 * 只负责把界面搭起来，具体逻辑都在 ViewModel 和 ui 里。
 *
 * 说明：识别文字**只在前台进行**（不做什么后台服务）。
 * 原因是国产 ROM 会强行限制后台，连前台服务也被掐，代码层面绕不过去。
 * 用户想边识别边用手机，可以把 App 切成**小窗**，这样它仍然是"前台"。
 */
class MainActivity : ComponentActivity() {

    // 界面用的"大脑"。用 by viewModels() 让它在屏幕旋转时不被重建。
    private val viewModel: PhotoViewModel by viewModels { PhotoViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoFinderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
