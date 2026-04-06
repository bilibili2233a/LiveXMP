// 根项目构建文件，用于定义所有子模块共用的插件。
plugins {
    // 注册 Android 应用程序插件版本
    alias(libs.plugins.android.application) apply false
    // 注册 Kotlin Android 插件版本
    alias(libs.plugins.kotlin.android) apply false
    // 注册 Kotlin Compose 编译器插件版本 (Kotlin 2.0+ 环境必需)
    alias(libs.plugins.kotlin.compose) apply false
}