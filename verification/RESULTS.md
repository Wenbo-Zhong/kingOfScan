# 本地功能对齐验收

日期：2026-11-23。范围为本仓库 Android/iOS 实现，不包含会员、账号或云能力。基础功能使用专门制作的测试页；新增 AI 模型使用注明来源的公开文档/拍屏/手指/票据参考图。没有拿 CamScanner 静态符号代替实际对比结果。

## 构建与共享测试

| 项目 | 结果 | 完整记录 |
|---|---|---|
| Android 正式 app、instrumentation APK、独立分享发送应用 | 构建成功；最终 app 已移除依赖带入的网络权限 | [初次构建](evidence/logs/kingscan-final-android-build.log)、[权限修正后构建](evidence/logs/kingscan-final-permissions-build.log) |
| Android 共享/JVM 测试 | 25 项通过，0 失败/跳过 | 同上；`composeApp/build/test-results/testDebugUnitTest` |
| iOS 共享/原生测试 | 26 项通过，0 失败/跳过 | [测试日志](evidence/logs/kingscan-final-ios-tests.log) |
| iOS 正式 app（普通 iosApp scheme，未开启验证属性） | 构建、安装、启动成功 | [完整构建日志](evidence/logs/kingscan-final-ios-app-build.log)、[实际首页](evidence/ios-app/library.png) |

环境：Android 16 ARM64、Pixel 9 模拟器 `emulator-5554`；iOS 27 ARM64 模拟器 `95759F50-ED05-4ED3-8670-8E45A6A24CBC`；JDK 使用 Android Studio JBR，Xcode `27.0 beta 6`。Android 验收期间关闭 Wi-Fi/移动数据，[connectivity 原始状态](evidence/logs/android-offline-connectivity.txt)显示无活动网络。检查 APK 发现依赖清单带入网络权限后，已用 manifest merger 显式移除；[最终 APK 权限](evidence/logs/final-apk-permissions.txt)没有 `INTERNET`/`ACCESS_NETWORK_STATE`，以下四组原生测试均在该最终包上通过。

## 同日 Xcode 启动复核

2026-11-23 上午用户打开 Xcode 后重新验证。当前系统报告 macOS `27.2.1 (26B131)`、Darwin `27.2.0`，Xcode 仍为 `27.0 / 27A5252f`。

直接点 Run 首次失败，Xcode 明确提示 `Android Gradle plugin requires Java 17 to run. You are currently using Java 11.`。已增加 `iosApp/scripts/build-framework.sh`，选择有效的 JDK 17 以上，并由工程构建阶段调用；之后在 Xcode 正常构建、启动并附加调试，状态为 `Running KingScan on KingOfScan Verification`。模拟器 booted，实际 app 进程及[首页画面](../iOS-模拟器运行复核.png)均确认。

可视窗口通过 `Xcode → Open Developer Tool → Device Hub` 打开，对应 app `com.apple.dt.Devices`，实际显示 iOS 27 模拟器和应用页面；通过其真实控件点击“更多 → 二维码与条码 → 返回”，导航正常。[可视窗口截图](../iOS-DeviceHub-运行复核.jpg)已保存。不能仅因传统 `Simulator.app` 不存在而认定这个版本没有可视模拟器。

同轮 XCUITest 再次 `build-for-testing` 成功，但执行仍提示 `DTServiceHubClient failed to bless service hub for simulator`、`localDeviceConnectionWithError:` failed。整轮限时 240 秒后停止，未计为通过；本轮日志位于 `/tmp/kingscan-ios-xcode-recheck/`，测试 runner 已单独终止。该连接失败与正常 Xcode 运行/Device Hub 可视操作是不同验证结果。收尾已恢复 Xcode 正式运行，应用和可视模拟器窗口均保持打开。

## 最终 Android 断网原生验收

[实际 instrumentation 结果](evidence/logs/kingscan-final-android-native-runtime.log)：**OK (4 tests)**，没有跳过。每组执行多项真实断言：

| 测试组 | 核对的实际行为 | 输入/输出证据 |
|---|---|---|
| `OfflineDocumentIntegrationTest` | 打包中英文 OCR、中文文字层、AES 密码/A4、加密 PDF 后图片导入、原始矢量 PDF 保留、坏尾图不丢已完成页、遮盖清除 OCR/保留底图 | [结果](evidence/android-native/offline-verification/result.txt)、[输入](evidence/android-native/offline-verification/input.jpg)、[文字](evidence/android-native/offline-verification/recognized.txt)、[PDF](evidence/android-native/offline-verification/encrypted-searchable.pdf)、[成图](evidence/android-native/offline-verification/pdf-page.jpg) |
| `LocalGeometryIntegrationTest` | 弯曲书页、投影重叠还原、A4 实际证件尺寸、组成来源独立保留 | [结果](evidence/android-native/geometry-verification/result.txt)、[原始书页](evidence/android-native/geometry-verification/book-original.jpg)、[展平](evidence/android-native/geometry-verification/book-left-flattened.jpg)、[重叠输出](evidence/android-native/geometry-verification/overlap-result.jpg)、[证件 PDF](evidence/android-native/geometry-verification/id-a4.pdf) |
| `LocalOutputIntegrationTest` | 修复与旋转/滤镜/重开，原照/参数保留，MediaStore 字节一致发布；打印三页文件仅输出选中的第二页并逐像素匹配 | [结果](evidence/android-native/output-verification/result.txt)、[原照](evidence/android-native/output-verification/retouch-original.jpg)、[结果](evidence/android-native/output-verification/retouch-result.jpg)、[旋转/滤镜](evidence/android-native/output-verification/retouch-rotated-refiltered.jpg)、[选页 PDF](evidence/android-native/output-verification/print-selected-page.pdf) |
| `OfflineToolsIntegrationTest` | Unicode QR JPEG 回读、旋转、多码、空白拒绝 | [结果](evidence/android-native/tools-verification/result.txt)、[QR](evidence/android-native/tools-verification/qr-generated.jpg)、[双码](evidence/android-native/tools-verification/qr-multiple.jpg) |

上表 PDF 打开密码为 `scan-2026`。直接运行，避免 Gradle 的设备测试卸载 app 导致证据丢失：

```bash
adb -s emulator-5554 install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 实际 Android UI

[六项结果](evidence/android-ui/result.txt)和[执行日志](evidence/logs/kingscan-final-android-ui-command.log)均来自安装后的正式 app：

1. 独立应用 UID 通过 `content://` 和真实 URI 读授权冷启动发送单图，进入裁边、滤镜并保存一页。
2. 热启动发送两图，逐页编辑，新增文档恰好两页，按输入顺序核对原照字节。
3. 在真实修复画布拖动笔刷，执行擦除和去彩笔。
4. 重置后撤销，恢复两项处理历史；[最终预览](evidence/android-ui/android-retouch-undo.png)已等待实际重绘。
5. 保存后元数据有两项修复，原照字节未改变；[实际输出](evidence/android-ui/ui-retouch-result.jpg)保留文字并移除选中的黑块，红蓝条仍有淡色边缘残余。
6. 从文档操作打开 Android 系统打印面板；[截图](evidence/android-ui/android-system-print.png)来自 `com.android.printspooler`。

复现：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest :shareSender:assembleDebug -PkingscanVerification=true
adb -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s emulator-5554 install -r verification/android/shareSender/build/outputs/apk/debug/shareSender-debug.apk
python3 verification/android/ui.py --serial emulator-5554
```

脚本只覆盖自建 `localuiacceptance` 测试文档，保留输入 `verification/fixtures/`，独立发送应用仅在验证属性开启时加入 Gradle 工程。运行会另外创建一页/两页验收文档。

最终移除网络权限后的 app 另通过 `verification/android/tools.py` **五项 UI 验收**：[结果](evidence/android-tools-ui/result.txt)、[执行日志](evidence/logs/kingscan-final-android-tools-ui-command.log)。

- 实际输入文字并[生成 QR 预览](evidence/android-tools-ui/qr-generated.png)。
- 点击相册保存，核对新发布的 [JPEG](evidence/android-tools-ui/gallery-qr.jpg)与 app 导出文件逐字节一致。
- 打开[系统图片分享面板](evidence/android-tools-ui/qr-share-sheet.png)，未选择接收者或发送消息。
- [大幅面模式](evidence/android-tools-ui/large-format-entry.png)显示连续拍摄、约一半重叠指引和完成拼接入口。
- [书籍](evidence/android-tools-ui/book-entry.png)与[证件](evidence/android-tools-ui/id-entry.png)模式有真实相机入口和对应指引；本项没有进行真实书页/证件拍摄。

## 实际 Android 设备认证

[六项结果](evidence/android-privacy/result.txt)、[执行日志](evidence/logs/kingscan-final-android-privacy-command.log)：

- 系统 PIN 认证成功后才能开启或关闭保护，状态立即持久化。
- [开启窗口记录](evidence/android-privacy/enabled-window.txt)包含 `SECURE`，[关闭窗口记录](evidence/android-privacy/disabled-window.txt)不包含；均检查正式 MainActivity 窗口。
- 先确认 Home 桌面真实可见再返回，应用锁定；[锁定无障碍树](evidence/android-privacy/locked-ui.xml)没有编辑器标题、保存按钮或修复预览，空白区域点击不会触达编辑器。
- 错误 PIN 和取消认证均保持锁定；[实际错误 PIN 提示](evidence/android-privacy/wrong-pin.png)。
- 正确 PIN 返回原编辑页，未保存的撤销/重做历史仍在；保存后的元数据由两项修复变成三项，证明未丢草稿。
- 认证关闭后后台返回不锁定，设置持久化为关闭；[最终设置](evidence/android-privacy/lock-disabled.png)。

脚本 `verification/android/privacy.py` 只在专门创建的模拟器上使用系统测试 PIN `2468`。快速连续发送 Home/启动命令曾使真实 `ON_STOP` 未发生，现等待桌面可见后验证，避免把测试时序当作产品失败。

收尾已关闭 app 保护、清除本次系统测试 PIN、恢复 Wi-Fi/移动数据、卸载独立发送应用并停止自己启动的 Android 模拟器。最终 `library.json` 为 `{}`（默认设置），没有保留测试密码或开启的设备锁。

## iOS 应用宿主原生验证

[七项结果](evidence/ios-native/result.txt)和[执行日志](evidence/logs/kingscan-ios-tools-revision1-command.log)：

- 真实中文、英文、数字 OCR；[输入](evidence/ios-native/input.jpg)、[识别文本](evidence/ios-native/recognized.txt)。
- 有密码 PDF 拒绝错误密码、接受正确密码；[PDF](evidence/ios-native/encrypted-searchable.pdf)的测试密码为 `scan-2026`。
- 中文可搜索文字层准确提取；[提取文本](evidence/ios-native/pdf-text.txt)、[实际渲染页](evidence/ios-native/pdf-page.jpg)。
- Unicode QR 生成后经过 JPEG 编解码及 90° 旋转，真实解码回原字符串；[生成图](evidence/ios-native/qr-generated.jpg)。
- 空白图不返回二维码。

使用真实 `UIApplication` 宿主调用生产 Kotlin/Native 代码。独立 K/N 测试进程的 Vision 曾返回空结果；真实宿主成功后保留正规 Vision 引擎。iOS 27 模拟器最新版 barcode request 实际出现 inference context 错误，现明确使用 Vision barcode revision 1，并通过真实解码验证。

运行脚本前先启动目标模拟器。

```bash
export DEVELOPER_DIR="/Applications/Xcode_27.0_beta6.app/Contents/Developer"
bash verification/ios/verify.sh 95759F50-ED05-4ED3-8670-8E45A6A24CBC
```

## 尚未完成的验收与能力边界

- iOS 完整 UI 测试目标能 `build-for-testing`，执行未启动。宿主内核[原始日志](evidence/logs/kingscan-host-servicehub.log)明确拒绝当前 Xcode 的 `DTServiceHub`，原因是 `com.apple.private.amfi.version-restriction` entitlement version 不匹配。没有关闭 AMFI/SIP 或重签系统服务；`verification/ios/ui.sh` 和 `iosApp/KingScanUITests/` 是待兼容环境运行的测试，不能计为通过。
- 真实相机、复杂书脊/翻页连续拍摄、真实大幅面实拍、iOS 相册/认证/打印交互及物理打印仍需真机验收。
- 书籍曲面为可调整的边界模型；合成纹理图验证投影拼接，不证明真实书页/复杂大幅面与原版质量等同。
- 已接入智能高清、黑手写/手指分割、水印/印章和摩尔纹模型；实图存在局部漏选、印刷笔画误伤、去手指纸面略灰，不能可靠重建被遮挡文字。去水印使用 DocDiff 印章权重，本轮实图为红色印章，未验证通用黑白或复杂水印效果。五步扩散和分块与原始百步/整图推理有差异；体积与耗时仍需优化，见下面 AI 专项。
- OCR 本次验证中英文，不覆盖原版资源列出的所有语言、公式/表格识别；PDF 有可搜索层，未提供 Word/Excel 转换或矢量内容编辑。
- 设备锁保护 app 访问，未另行加密每个存储文件；外部相册和分享副本独立存在。没有 CamScanner 同源输出或像素级 UI/性能对比，因此没有完整等同或速度提升结论。

## 五项开源模型专项

以下是压缩前版本的验证记录。当前五模型已采用 INT8 权重量化，ARM64 release 测试签名 APK 为 198.52 MB；两端原生实图及 Android 缓存恢复验证见[小包验证](evidence/model-size/RESULTS.md)。原始记录保留用于对照。

已将 DocRes、DIS-Handwriting-Remover、HandsWild EYTH、DocDiff 和 ESDNet 转换并随包部署，Android ONNX Runtime / iOS Core ML 真正运行权重。共同预处理、选区恢复、重叠融合、可复现噪声、旋转和编辑历史见 [模型说明](models/README.md)。[实际前后对比图](evidence/ai-comparison.png)由 iOS 原生验收的输入/输出生成；[输入](evidence/ai-inputs/)与 [iOS 输出](evidence/ai-ios-native/)保留，图片来源和权利边界另见模型说明。

| 项目 | Android ARM64 模拟器 | iOS 27 ARM64 UIApplication 宿主 |
|---|---|---|
| 五个原生 tensor 与独立 Torch 参考 | 全通过，MAE<0.003 | 全通过，MAE<0.003 |
| 五种整页流程 | 分辨率保留，实际像素改变 | 分辨率保留，实际像素改变 |
| 手写/手指选区外像素完全保留 | 手写 `294950/311910`，手指 `9388/22000` | 手写 `294847/311910`，手指 `9429/22000` |
| 原照字节、旋转重渲染、重开参数、清除过期 OCR | 通过 | 通过 |
| 干净纸无检出拒绝、独立噪声参考、缺模型版本/取消保留原照 | 通过 | 通过 |

各端均为 **14 项检查通过**；Android instrumentation 计为 `OK (1 test)`，测试内含上述多项断言，整套运行 `713.829 s`。原始 [Android 结果](evidence/ai-android-native/result.txt)、[runtime 日志](evidence/logs/kingscan-ai-android-provider-runtime.log)、[iOS 结果](evidence/ai-ios-native/result.txt)、[宿主日志](evidence/logs/runtime.log)分别保留。iOS 是实际 UIApplication 宿主的测量，未混用 macOS Core ML 转换误差。

| 模型 | Android MAE | iOS MAE | Android 首次加载及单次推理（ms） |
|---|---:|---:|---:|
| DocRes | 0.000000371 | 0.002126058 | 28079 |
| DIS 手写 | 0.000000032 | 0.000173053 | 6925 |
| HandsWild 手指 | 0.000000188 | 0.001538665 | 38365 |
| DocDiff 水印 | 0.000000185 | 0.001342285 | 19504 |
| ESDNet 摩尔纹 | 0.000000218 | 0.000908649 | 9426 |

时间来自该测试模拟器，包含首次加载/校验及一次固定输入预测，不是整页耗时、真机测量或与原版比较。Android 在模型切换时只保留一个 session，手指使用 CPU；其余模型使用可用 XNNPACK。[先前 XNNPACK 失败证据](evidence/logs/kingscan-ai-android-xnnpack-runtime.log)明确报手指图分区缺失 `/refinement/op0/Conv`，已改为加载前按模型选择 provider，随后整套通过。

Android 五模型实际界面整套通过：[运行日志](evidence/logs/kingscan-ai-final-android-ui-runtime.log)为 **OK (1 test)**，内部[七项结果](evidence/ai-android-ui/result.txt)包含在途取消后保存空修复、高清撤销/重做，以及五个按钮的实际预览、原图对比、保存、显式参数、原照字节和重新打开。整套耗时 `1759.963 s`，含多次重开后的重新推理，不是单次模型耗时。另导出五份原照并与安装前的 PNG 字节独立比较，[保存文件核对](evidence/ai-android-ui/saved-file-checks.json)全部通过；实际 [手指结果](evidence/ai-android-ui/fingers-result.png)、[水印结果](evidence/ai-android-ui/watermark-result.png)、[摩尔纹结果](evidence/ai-android-ui/esdnet-result.png)与[重开](evidence/ai-android-ui/esdnet-reopened.png)均保留。

本轮 Android UI 使用带临时跟踪的验收包，功能修复与最终包一致。运行环境为 12 GiB RAM / SwiftShader 模拟器，主机有较高内存压缩；前半段 DocRes 单次推理曾耗时数分钟。后半段临时关闭模拟器动画以减少渲染占用，结束后恢复未设置状态。[后续实际事件](evidence/logs/kingscan-ai-final-android-ui-events.log)完整记录结果发布和五组通过；[线程记录](evidence/logs/kingscan-ai-ui-postcancel-java-trace.txt)显示采样时测试在等待截图、模型任务已结束，未将其当作产品生成任务死锁。没有真机速度或性能提升结论。

iOS Device Hub 实际界面已检查高清、黑手写、手指入口、完成预览、原图对比及保存，保存文件的修复类型与原照一致；[高清](evidence/ai-ios-ui/docres-result.jpg)、[手写结果](evidence/ai-ios-ui/handwriting-result.jpg)/[原图](evidence/ai-ios-ui/handwriting-original.jpg)、[手指结果](evidence/ai-ios-ui/fingers-result.jpg)/[原图](evidence/ai-ios-ui/fingers-original.jpg)。[三份实际保存文件核对](evidence/ai-ios-ui/result.txt)再次验证修复类型、尺寸及原照 SHA-256；[最终正式包首页](evidence/ai-ios-ui/final-production-home.png)来自安装后的 simctl 截图。检查摩尔纹入口横向滚动时 Computer Use 返回 `MAC_LOCKED_USE_NOT_READY: cancelled`；摩尔纹/水印的本轮实际 UI 尚未完成，不计通过，两项已在原生宿主验收通过。之前点击取消时推理可能已完成，未据此计为 iOS 在途取消验证。

默认版本、seed、强度、方向在早期 UI 元数据中被序列化省略，现用 `@EncodeDefault` 显式保存，并补充实际存储契约回归，避免仅同版本 round-trip 掩盖缺失版本。旧无版本字段按固定 V1 解码；不将其默认指向未来新模型。

Android 原生 UI 跟踪发现预处理期间取消后，旧任务仍调用模型：13:18:46.832 已切换到空修复，13:18:47.404 才启动旧任务的 DocRes 加载。[修复前日志](evidence/logs/kingscan-ai-cancel-before-trace.log)保留原事件。共享预处理现逐行检查取消，并在调用原生推理之前再次检查；增加覆盖五种输入的取消回归，活动任务的数值输入不变。新增回归后 Android **27** 项、iOS **28** 项均通过，0 失败/跳过。

取消修复后的 [共享测试与 Android 构建](evidence/logs/kingscan-ai-cancel-fix-build-tests.log)及实际 [XML 汇总](evidence/ai-test-results.json)已保留。临时跟踪代码已清理，正式 [Android](evidence/logs/kingscan-ai-clean-production-android-build.log)/[iOS](evidence/logs/kingscan-ai-clean-production-ios-build.log)均重新构建成功；iOS 已安装、启动并实际复核首页。Android 在上述 UI 结束后安装正式包、移除测试 APK 和五份自建 UI 文档，再[冷启动成功](evidence/logs/kingscan-ai-final-android-cold-launch.log)，[实际首页](evidence/ai-android-ui/final-production-home.png)可见。[最终包检查](evidence/ai-final-bundles.json)核对五个 ONNX 哈希、不压缩存储、五个 `.mlmodelc`、两端清单一致，以及验证入口和临时日志不包含在生产包；[APK 权限](evidence/logs/kingscan-ai-final-apk-permissions.txt)没有网络权限。

模型资源约 Android 731 MiB、iOS 366 MiB；最终 debug APK `927009663` 字节（约 884 MiB）。没有更快或全场景效果等同结论。手指权重来源附带的基础 RefineNet 非商用限制及缺独立权重许可已保留，不能把整个模型包当作可自由商用 MIT。正式包不包含验证入口，Android 仍移除 `INTERNET`。

AI 专项收尾已恢复自有 Android 模拟器的动画默认值、Wi-Fi 和移动数据，卸载测试 APK、清理五份自建 UI 文档及验证输入目录，并停止自己启动的模拟器；[安装](evidence/logs/kingscan-ai-final-android-install.log)与[清理记录](evidence/logs/kingscan-ai-final-android-cleanup.log)保留。其他既有文档继续保留，iOS 正式应用仍在运行。
