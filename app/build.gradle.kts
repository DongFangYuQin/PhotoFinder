import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ------------------------------------------------------------
//  正式签名配置
//
//  密码这些**绝不能写进代码**（这个文件会上传到 GitHub）。
//  所以从项目根目录的 keystore.properties 读 —— 那个文件在 .gitignore 里。
//
//  别人克隆这个仓库是没有这个文件的：那时 release 包就是**未签名**的，
//  debug 包照常能编译（debug 用系统自带的调试签名，不需要配置）。
// ------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // ⚠️ 必须显式用 UTF-8 读。
        //    Properties.load(InputStream) 按 ISO-8859-1 解码，
        //    keystore 路径里如果有中文（比如 D:\APP开发\...）就会变成乱码，
        //    然后报 "Keystore file ... not found"。
        keystorePropsFile.inputStream().use { stream ->
            load(stream.reader(Charsets.UTF_8))
        }
    }
}
val hasSigningConfig = keystorePropsFile.exists()

android {
    // App 的唯一标识（相当于身份证号，以后不能随便改）
    namespace = "com.atri.photofinder"

    // ⚠️ 注意：AGP 9 的写法跟网上老教程不一样，这里是"代码块"不是等号
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.atri.photofinder"

        // 安卓 10 及以上都能装。
        // 代码里已经按版本做了兼容（安卓 13+ 用 READ_MEDIA_IMAGES，更早用 READ_EXTERNAL_STORAGE）。
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 有 keystore.properties 才创建签名配置，否则跳过
    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
