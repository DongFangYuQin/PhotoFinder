// 项目名字和包含哪些模块
// 说明：仓库地址没写在这里，而是通过全局的镜像配置统一加速（见 .gradle-cache\init.d\）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // 自动帮你准备编译用的 Java（如果本机没有的话）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhotoFinder"
include(":app")
