# 修复与验证记录

2026-11-26。已完成本次检视提出的四项业务修复。设备锁按用户安排留待后续接入手机系统认证，本次未修改。改动尚未提交 Git。

## 修复结果

| 问题 | 最终行为 | 主要实现 |
| --- | --- | --- |
| PDF 重复导出覆盖共享文件 | 两个导出入口均生成独立、原子写入的快照；打印或重新导出不会改变旧文件的字节、密码和页数 | [DocumentRepository](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt#L459-L499) |
| 旧任务污染新会话、错误回滚已有文档 | 会话切换取消并等待其全部任务结束，随后清理导入文件；任务固定目标文档，回滚只处理本次新增的页面、文档和原始 PDF；覆盖结果返回界面之前的取消 | [ScanSession](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/AppGraph.kt#L68-L190)、[PDF 事务](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/PdfImport.kt)、[写入与结果发布](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/ScanWrite.kt) |
| 编辑后返回相机再次自动拍摄同一页 | 拍摄指纹保存在扫描会话中，跨裁切、滤镜和书页编辑保留；新页面或新会话可以继续拍摄 | [相机绑定](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/CaptureScreen.kt) |
| 批注部分保存后无法重试 | 每次按稳定页面 ID 读取最新元数据和批注底图；按水印 ID 去重，保留原图和已有笔迹，清除过期 OCR | [批注保存](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/AnnotationSave.kt) |

扫描任务管理同时覆盖导入、相机图片准备、连续拍摄保存、重新裁切准备、裁切计算、滤镜保存和书页保存。后台处理使用固定输入，界面状态在所属任务仍有效时发布。

PDF 快照在文档编辑时保留，在文档删除时清理。

## 修复前失败证据

测试首先在保持原业务行为的测试接口上运行，断言期望的正确行为，确认失败后才修复。没有删除失败断言或将期望改成原错误行为。

| 场景 | 实际失败 | 日志 |
| --- | --- | --- |
| 重复导出 | 两次返回同一路径，独立快照断言失败 | [baseline-export.log](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/logs/baseline-export.log) |
| PDF 渲染期间切换目标 | 回滚后原有文档被彻底删除 | [baseline-session.log](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/logs/baseline-session.log) |
| 取消期间输入资源归属 | 旧任务仍未结束，会话重置已完成 | 同上 |
| 从编辑返回相机 | 同一指纹再次触发拍摄 | 同上 |
| 第二页批注保存失败后重试 | 读取第一页已删除的旧图片，抛出 FileNotFoundException | [baseline-annotation-2.log](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/logs/baseline-annotation-2.log) |

批注首轮测试遇到 Android 字体绘制不支持普通 JVM 测试的环境限制。修正测试接口后，平台绘制作为依赖隔离，文件写入、元数据提交、底图保留和重试仍执行真实仓库逻辑。该环境失败不作为缺陷证据。

## 最终验证

| 验证 | 结果 |
| --- | --- |
| Android 全部单元测试 | 52 通过，0 失败，0 跳过 |
| iOS 全部共享及原生测试 | 53 通过，0 失败，0 跳过 |
| Android 应用及仪器测试包构建 | 成功 |
| 最终 Android 构建的模拟器原生集成测试 | 1 通过 |
| Git 差异格式检查 | 通过 |

新增 14 个共享回归用例，覆盖四项问题及其相关正常、边界、异常路径。三页批注测试特别检查第二页已保存、第三页失败时，重试保留第二页已有笔迹，并且水印只出现一次。

用例源码：[导出快照](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonTest/kotlin/com/kingscan/app/ExportSnapshotTest.kt) · [会话归属与自动拍摄](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonTest/kotlin/com/kingscan/app/ScanSessionOwnershipTest.kt) · [PDF 回滚](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonTest/kotlin/com/kingscan/app/PdfImportOwnershipTest.kt) · [写入完成后的取消](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonTest/kotlin/com/kingscan/app/ScanWriteCancellationTest.kt) · [批注重试](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonTest/kotlin/com/kingscan/app/AnnotationSaveTest.kt)。

iOS 原生 PDF 用例验证：加密导出后生成无密码打印版，再增加页面并换密码导出，原快照仍保持原字节、原密码和原页数。

Android 原生集成测试实际运行了内置中文 OCR、中文可搜索加密 PDF、密码解锁后接续图片导入、原始矢量 PDF 保留、损坏尾部图片的处理，以及涂黑后 OCR 清除和底图保留。

最终命令：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
DEVELOPER_DIR=/Applications/Xcode_27.0_beta6.app/Contents/Developer \
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test \
  :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest --console=plain

adb -s emulator-5554 shell am instrument -w -r \
  -e class com.kingscan.app.OfflineDocumentIntegrationTest \
  com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner
```

[最终构建与测试日志](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/logs/final-verification.log) · [Android 原生测试日志](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/logs/final-android-native-integration.log) · [原生测试输入与输出](file:///Users/bytedance/workspace/kingOfScan/修复验证记录-20261126/artifacts/android-native-evidence.tar)

测试在 JVM、iOS 测试运行环境和 Android 模拟器完成。本次没有进行真机相机的最终交互验收。
