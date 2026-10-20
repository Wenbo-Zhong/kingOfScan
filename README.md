# 离线扫描王

一个用 Kotlin Multiplatform 和 Compose Multiplatform 写的文档扫描应用，界面和流程接近扫描全能王，但**没有账号、没有云同步、也没有网络权限**。同一套共享代码可以编译成 Android 和 iOS 应用。

## 能做什么

- 用相机拍摄，或从相册导入
- 拍摄后自动找纸张四角，可以拖动校正
- 透视拉正，并旋转页面
- 滤镜：原图、增强、灰度、黑白、柔和
- 一份文档多页，可继续拍摄、调序、删除
- 文档只存在本机，可重命名、删除
- 生成本地 PDF，再通过系统分享面板保存到文件或发给其他应用

边缘识别、透视校正、滤镜和 PDF 都在共享模块里，用 Kotlin 实现，不依赖联网模型。

## 工程结构

- `composeApp/src/commonMain`：界面、文档库、图像处理和 PDF
- `composeApp/src/androidMain`：CameraX、Bitmap、系统分享
- `composeApp/src/iosMain`：AVFoundation、Core Graphics、系统分享
- `iosApp`：Xcode 壳工程，嵌入 `ComposeApp` 框架

## 环境

- JDK 17 或 21。这台机器的系统 Java 是 11，请改用 Android Studio 自带的 JBR
- Android SDK，已安装 compileSdk 35
- Xcode，用来跑 iOS

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

## 运行

Android：用 Android Studio 打开仓库，运行 `composeApp`。或：

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest
```

调试包在 `composeApp/build/outputs/apk/debug/`。

iOS：用 Xcode 打开 `iosApp/iosApp.xcodeproj`，选择模拟器或真机后运行。模拟器只编 arm64，工程排除了 Intel 模拟器。真机需要在 `iosApp/Configuration/Config.xcconfig` 里填写 `TEAM_ID`。Xcode 的构建脚本会调用：

```bash
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

只检查 Kotlin/Native 能否编译。如果 `xcode-select -p` 指向 Command Line Tools，先把 `DEVELOPER_DIR` 指到完整的 Xcode：

```bash
export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

## 数据放在哪

Android 在应用私有目录 `files/kingscan`。iOS 在 Documents/kingscan。卸载应用会一起删掉。PDF 导出文件也在这个目录的 `exports` 里，分享时走系统面板，应用自己不上传。

## 许可证

本项目按 GNU 通用公共许可证第 3 版（GPL-3.0）授权，详情见 [LICENSE](LICENSE)。
