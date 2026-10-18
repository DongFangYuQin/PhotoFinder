// 整个项目的总配置。这里只声明"要用哪些插件"，具体配置在 app/build.gradle.kts 里。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
