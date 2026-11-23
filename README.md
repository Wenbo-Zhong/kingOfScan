# 离线扫描王

使用 Kotlin Multiplatform 和 Compose Multiplatform 构建的本地文档扫描应用。扫描、编辑、文字识别及导出均在设备上完成，不含会员、账号和云服务；Android 不声明 `INTERNET` 权限。业务、几何和编辑参数共享，相机、文字识别、PDF、认证及系统输出使用各平台原生能力。

## 能做什么

- 拍摄和批量图片/PDF 导入，支持有密码的 PDF；Android 支持其他应用通过系统分享导入图片及 PDF。
- 两个平台实时检测纸边、拖动裁边、透视校正、旋转，按清晰度和稳定性自动拍摄，支持连续扫描及同页防重复。
- 书籍双页拆分、可调整书脊和上下边界曲线、阅读方向；证件正反面按实际尺寸排在 A4；大幅面连续拍摄后按重叠区域配准拼接。
- 原图、增强、灰度、黑白、柔和、提亮、锐化、去阴影、省墨、反色；亮度、对比度、饱和度及原图对比。
- 保存原照和编辑参数，保存后可重新裁边、调整滤镜；手动选区擦除、去红蓝彩笔、降噪及轻微失焦恢复，可重置、撤销、重做。
- 随包离线模型提供智能高清、自动去黑色手写、去手指、去水印/印章和去摩尔纹；支持进度、取消、原图对比及保存后重编辑。
- 多页增删、旋转、排序、批量滤镜、复制、提取、合并；A4 多图拼版、横向/纵向长图及重叠拼接，独立保留组成页的来源。
- 本地中英文 OCR、结果编辑、复制、搜索及 TXT 输出；可搜索 PDF 支持纸张、方向、边距、页码、JPEG 质量和打开密码。
- 笔迹批注、多笔段签名模板、文字水印和不透明遮盖；批注烧入输出图片，内容修改会清除过期 OCR。
- 文件夹、标签、备注、收藏、搜索和排序；整份文档移入回收站后可恢复，彻底删除前需确认。
- 图片保存到系统相册、扫描后自动存相册、PDF/JPEG 系统分享及系统打印；设备密码/生物识别保护，启动和后台返回重新锁定。
- 从相机或图片识别二维码/条码，生成文字/链接二维码并保存或分享。

自动处理使用 DocRes、DIS 手写分割、HandsWild EYTH、DocDiff 和 ESDNet。高清保留输入尺寸；手写/手指分割后补全纸面，可能漏选或误伤邻近印刷字；水印模型含五步扩散采样，与原始百步采样有差异。遮挡的文字无法可靠恢复。模型资源约 Android 731 MiB、iOS 366 MiB，首次加载和大图处理需等待；预处理逐行响应取消，已经开始的单次原生推理需等待结束。选型、许可与转换记录见 [模型说明](verification/models/README.md)。书页曲面需要调整边界，拼接需要足够清晰的重叠内容。没有进行与 CamScanner 同源实拍的质量、性能或像素级 UI 对比，不能据此声称全部效果等同。功能对照见 [本地功能分析第 13 章](KingOfScan-本地功能分析.md#13-对照-kingofscan本次实现与剩余差距)。

## 工程结构

- `composeApp/src/commonMain`：界面、文档库、编辑参数、拍摄状态、书页曲面、拼接、修复及 PDF 布局
- `composeApp/src/androidMain`：CameraX、Bitmap、打包的 ML Kit 中英文识别、ONNX Runtime、PdfBox、ZXing、系统认证/相册/打印/分享
- `composeApp/src/iosMain`：AVFoundation、Vision、Core ML、Core Graphics/Core Text、PDFKit、Core Image、LocalAuthentication、Photos 和系统输出
- `iosApp`：Xcode 壳工程，嵌入 `ComposeApp` 框架
- `verification`：独立分享发送应用、实际 Android UI 验收、iOS 应用宿主验证及测试输入/结果

界面保留一套共享页面、状态和导航。`ui/PlatformComponents.kt` 用 `expect/actual` 定义控件边界，`ScanTheme` 统一提供排版及颜色：Android 实现在 `androidMain/ui/AndroidComponents.kt`，使用 MD3 控件，Android 12 及以上跟随系统动态配色；iOS 实现在 `iosMain/ui/IosComponents.kt`，使用系统语义配色、SF Symbols、原生 `UISwitch`/`UISlider`，首页采用大标题和底部工具栏，设置采用分组布局。搜索、文稿筛选、编辑画布、保存和导出逻辑仍由 `commonMain` 复用，平台差异集中在控件层。

本轮深色模式截图、修复对比和验收范围见 [iOS 深色模式实测](ui-preview/ios-dark/RESULTS.md)。

## 环境

- JDK 17 或 21。这台机器的系统 Java 是 11，请改用 Android Studio 自带的 JBR
- Android SDK，已安装 compileSdk 35
- Xcode，用来跑 iOS
- Git LFS，用来获取随包的 ONNX 和 Core ML 模型权重

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

## 运行

首次克隆后，先获取模型文件再构建：

```bash
git lfs install
git lfs pull
```

模型通过 Git LFS 管理，普通 Git 存储权重指针和许可证。原始运行日志留在本地；验收文档引用的必要日志作为结果证据随版本保留。

Android：用 Android Studio 打开仓库并完成 Gradle Sync，然后在运行配置下拉框选择 **Build Android Debug APK**，点击 Run 即可编译 Debug APK。也可以从 Gradle 工具窗口运行 `composeApp > Tasks > build > assembleDebug`。

APK 输出在 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`。命令行方式：

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest
```

iOS：用 Xcode 打开 `iosApp/iosApp.xcodeproj`，选择模拟器或真机后运行。模拟器只编 arm64，工程排除了 Intel 模拟器。真机需要在 `iosApp/Configuration/Config.xcconfig` 里填写 `TEAM_ID`。Xcode 的构建脚本会调用：

```bash
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

`iosApp/scripts/build-framework.sh` 会优先使用有效的 `JAVA_HOME`（JDK 17 以上），否则选择已注册的可用 JDK、Android Studio JBR 或用户安装的 JetBrains Runtime。本机系统 Java 11 不满足要求，直接从 Xcode 点 Run 时现在也会自动选择可用 JDK。

当前 Xcode 27 环境从 `Xcode → Open Developer Tool → Device Hub` 打开可操作的模拟器窗口。

只检查 Kotlin/Native 能否编译。如果 `xcode-select -p` 指向 Command Line Tools，先把 `DEVELOPER_DIR` 指到完整的 Xcode：

```bash
export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

## 数据放在哪

Android 在应用私有目录 `files/kingscan`，且关闭应用备份；iOS 在 `Documents/kingscan`。`documents` 保存原照、处理图、编辑参数、识别文字及独立保留的原始 PDF，`library.json` 保存文件夹、签名和设置，`exports` 保存导出结果。卸载会删除应用内数据，相册和外部分享的副本独立存在。

设备锁保护应用访问，不是额外的逐文件加密。Android 开启时设置 `FLAG_SECURE`；iOS 在应用失活时遮住窗口，保护任务预览。带密码导出的 PDF 单独加密，默认设置不持久化导出密码。重新裁边会清除旧坐标的批注/修复及 OCR；老页面没有原照时不能凭空恢复原始拍摄内容。

## 验证

共享与原生测试：

```bash
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
```

Android 断网原生验收需先安装 app/test APK，再运行 `com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner`。实际跨应用分享/UI 验收：

```bash
./gradlew :shareSender:assembleDebug -PkingscanVerification=true
adb install -r verification/android/shareSender/build/outputs/apk/debug/shareSender-debug.apk
python3 verification/android/ui.py --serial emulator-5554
```

设备锁脚本只适用于自己创建的测试模拟器：临时设置 PIN `2468` 后运行 `verification/android/privacy.py`，完成后关闭应用设备锁并清除该 PIN。

`python3 verification/android/tools.py` 可额外验证二维码生成、相册发布、系统分享以及书籍/证件/大幅面拍摄入口。

iOS 的 Vision 验证在真实 `UIApplication` 宿主中执行，避免把独立测试进程缺少应用上下文时的空识别结果当作产品行为；运行前先启动目标模拟器：

```bash
bash verification/ios/verify.sh SIMULATOR_UUID
```

`-PkingscanVerification=true` 仅加入验证宿主代码/独立发送模块，正常构建不包含这些入口。完整证据、已通过项和 iOS UI 测试环境限制见 [verification/RESULTS.md](verification/RESULTS.md)。

## 许可证

本项目按 GNU 通用公共许可证第 3 版（GPL-3.0）授权，详情见 [LICENSE](LICENSE)。

Android PDF 文字层打包 `NotoSansSC-Regular.ttf`，随附字体许可位于 `composeApp/src/androidMain/assets/fonts/OFL.txt`。新增 PdfBox Android 和 ZXing 使用 Apache-2.0；Android OCR 使用随包提供的 ML Kit 库，iOS 使用系统框架。

模型各自保留上游许可及权重 SHA-256。**手指模型不能简单标为可自由商用的 MIT**：HandsWild 代码为 MIT，其基础 RefineNet 带非商用声明，公开 EYTH 权重没有独立许可声明。本次用于本地研究；商业使用或发布需向权利方明确权重授权。随包 [第三方说明](verification/models/THIRD_PARTY_NOTICES.txt)和原始许可证不由本项目 GPL 替代。
