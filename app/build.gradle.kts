plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    // App 的唯一标识（相当于身份证号，以后不能随便改）
    namespace = "com.atri.photofinder"

    // ⚠️ 注意：AGP 9 的写法跟网上老教程不一样，这里是"代码块"不是等号
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.atri.photofinder"

        // 只支持安卓 15 以上（你手机是安卓 16，没问题）
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 正式版会做代码压缩，让安装包更小
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

// Room 数据库需要这个设置，让它把"表结构"记录下来
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // ---------- 界面 ----------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ---------- 本地数据库 ----------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---------- 设置存储 ----------
    implementation(libs.androidx.datastore.preferences)

    // ---------- 显示图片 ----------
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ---------- 联网（阶段 5 用） ----------
    implementation(libs.okhttp)

    // ---------- 调试用（只在开发时打包，不影响正式版） ----------
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 让 GIF 动图能播放（否则只显示第一帧）
    implementation(libs.coil.gif)

    // 识别图里的文字（手机本地跑，离线、免费、不上传）
    implementation(libs.mlkit.text.recognition.chinese)
}
