# LiveXMP - 实况照片合成器

**LiveXMP** 是一款专为 Android 平台设计的高性能工具，旨在将 Apple 导出的散装资源（HEIC 图片 + MOV 视频）重新合成为符合 Google 规范的 **Motion Photo (动态照片)**。

利用 Android 原生硬件加速和 NIO 零拷贝技术，LiveXMP 能够以极快速度处理批量转换，生成的照片可在 Google 相册、小米相册等支持动态属性的终端上完美呈现。

## ✨ 核心特性

- 🚀 **性能卓越**：采用 NIO `transferTo` 零拷贝技术拼接数据，直接在内核空间传输，减少 CPU 开销。
- ⚡ **硬件加速**：使用 `ImageDecoder` 硬件分配器解码高分辨率 HEIC/JPG，有效防止大图处理时的内存溢出 (OOM)。
- 📁 **深度适配 SAF**：完整支持 Android 的存储访问框架 (Storage Access Framework)，无需申请高风险的文件操作权限即可处理任意目录。
- 🎨 **现代 UI**：基于 Jetpack Compose 和 Material Design 3 构建，界面简洁、交互流畅。

## 🛠️ 依赖与库

本项目基于以下现代化技术栈：

- **Jetpack Compose**: 用于构建响应式 UI。
- **Material3**: 遵循最新的 Google 设计语言。
- **AndroidX Core KTX**: Kotlin 扩展支持。
- **DocumentFile**: 用于递归处理录下的 SAF 目录。
- **ExifInterface**: 处理图片原始元数据。
- **Kotlin Coroutines**: 异步非阻塞的任务处理。

- **Android Studio**: Jellyfish (2023.3.1) 或更高版本。
- **JDK**: 25+ (推荐使用最新 LTS 版本)。
- **Kotlin**: 2.0.0+ (当前配置为 2.2.10)。
- **Android Gradle Plugin (AGP)**: 8.2+ (当前配置为 9.1.0)。

## 🏗️ 编译与环境准备

### 环境准备与安装

如果您还没有开发环境，可以按照以下步骤操作：

1. **安装 JDK 25** (推荐方式):
   - **Windows (winget)**: `winget install Oracle.JDK.25` 或 `winget install Microsoft.OpenJDK.25`
   - **Windows (Chocolatey)**: `choco install openjdk --version 25`
   - **macOS (Homebrew)**: `brew install openjdk` (此时通常已默认 25 或更高)
   - **直接下载**: [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) 或 [Adoptium](https://adoptium.net/)。

2. **环境变量配置**:
   确保 `JAVA_HOME` 指向 JDK 25 路径，并将 `bin` 目录添加到系统的 `PATH` 中。验证：`java -version`。

3. **Android SDK**:
   推荐安装 [Android Studio](https://developer.android.com/studio)，它会自动为您配置所需的 SDK 和 Build Tools。

### 编译与运行 (IDE 方式)

1. 使用 Git 克隆或手动将项目文件夹复制到您的开发目录。
2. 打开 Android Studio，选择 **File > Open**，指向项目根目录。
3. 等待 Gradle 同步完成。
4. 点击顶部的 **Build > Make Project**。
5. 连接 Android 10+ 设备，点击 **Run 'app'**。

### 编译与安装 (命令行方式)

如果您更喜欢使用终端，可以在项目根目录下执行以下命令：

```bash
# 赋予 Gradle Wrapper 执行权限 (如果是 Linux/macOS)
chmod +x gradlew

# 1. 清理项目
./gradlew clean

# 2. 同步并下载依赖
./gradlew help

# 3. 编译 Debug 版本 APK
./gradlew assembleDebug

# 4. 直接安装到已连接的设备并在设备上运行
./gradlew installDebug
```

编译产出的 APK 路径为：`app/build/outputs/apk/debug/app-debug.apk`。

## 📖 使用方法

1. **选择输入路径**：点击“选择输入文件夹”，在系统弹窗中选择存放 Apple 原始 HEIC/MOV 文件的目录。
2. **选择输出路径**：点击“选择输出文件夹”，选择合并后生成的 Motion Photo 存放位置。
3. **配置选项**：
   - **移动不匹配的文件**：若图片没有对应的视频，可以开启此项进行分类。
   - **合并成功后删除原始文件**：勾选后，合并成功的旧文件将被自动清理以节省空间。
4. **开始合并**：点击底部的“开始合并”按钮。
5. **产出物**：合并后的文件将命名为 `文件名_motion.jpg`，您可以直接将其分享到 Google 相册中查看动态效果。

## ⚠️ 注意事项

- 请确保输入目录下图片和视频的文件名（不含后缀）是一一对应的。
- 部分手机导出的视频格式可能为短视频，本工具针对实况照片优化，建议视频长度在 1-3 秒之间。

---
Produced with ❤️ by Antigravity AI.
LLM就是好用呀,虽然 看我 一股人机味,但总好过不写留空白