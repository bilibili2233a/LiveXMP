plugins {
    // Android 应用程序插件
    alias(libs.plugins.android.application)
    // Kotlin Android 插件
    alias(libs.plugins.kotlin.android)
    // Kotlin Compose 编译器插件 (Kotlin 2.0+ 必需)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 应用程序的项目命名空间
    namespace = "com.LiveXMP.APP"
    // 编译时使用的 Android SDK 版本
    compileSdk = 34

    defaultConfig {
        // 应用程序的唯一标识符
        applicationId = "com.LiveXMP.APP"
        // 运行程序所需的最低 Android 版本 (Android 10+)
        minSdk = 29
        // 程序针对优化的 Android 版本
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 单元测试运行器
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            // 启用矢量图支持库
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // 是否启用代码混淆
            isMinifyEnabled = false
            // 默认的混淆规则文件
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // Java 源码兼容性版本
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        // Kotlin 编译目标的 JVM 版本
        jvmTarget = "11"
    }
    buildFeatures {
        // 开启 Jetpack Compose 支持
        compose = true
    }
    packaging {
        resources {
            // 排除重复的元数据文件以避免构建冲突
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX 核心库，用于 Kotlin 扩展
    implementation(libs.androidx.core-ktx)
    // 兼容层库，确保旧版本系统 UI 表现一致
    implementation(libs.androidx.appcompat)
    // Material Components 库
    implementation(libs.material)
    
    // [Jetpack Compose 依赖组件]
    // 使用 BOM (Bill of Materials) 管理 Compose 各组件版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3) // Material Design 3 库
    implementation(libs.androidx.activity.compose) // 让 Activity 支持 Compose 的核心库
    
    // [核心业务逻辑依赖]
    // 管理 SAF 文件的库
    implementation(libs.androidx.documentfile)
    // Kotlin 协程 Android 支持，用于后台处理
    implementation(libs.kotlinx.coroutines.android)
    // 用于读取和写入图片 Exif 信息的库
    implementation(libs.androidx.exifinterface)

    // [测试依赖]
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling) // 调试工具：IDE 预览支持
    debugImplementation(libs.androidx.ui.test.manifest) // 调试工具：UI 测试清单
}