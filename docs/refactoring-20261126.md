# 共享流程与目录重构记录

日期：2026-11-26。范围：现有 Kotlin Multiplatform 模块中的业务复用、职责拆分及包目录迁移。

保存、图像渲染与 OCR 已统一入口；文库状态、扫描会话和底层文件存储已从页面及全局对象中拆出。Android / iOS 保留各自的原生组件和系统能力实现，共享业务与页面布局。

## 已落实的结构

以下路径相对于 `composeApp/src/commonMain/kotlin/com/kingscan/app/`：

```text
App.kt                         应用入口与锁定状态下的页面呈现
AppGraph.kt                    创建依赖、连接系统事件
core/
  document/                    几何、页面编辑、文档、OCR、设置及导出数据模型
  imaging/                     图像、检测、几何、修复、滤镜、渲染与拼版能力
  storage/                     文档仓库、文档文件、文库元数据及导出快照
  security/                    应用锁、文件夹授权和前后台授权失效
  platform/                    相机、导入、分享、打印、生物认证等平台接口
  ui/                          公共组件接口、主题和图像展示组件
  ocr/                         OCR 引擎接口及文档识别流程
  pdf/                         PDF 布局、写入与读取接口
  codes/                       条码编解码接口
feature/
  scan/                        扫描会话、拍摄、书籍、导入、保存和后续路由
  editor/                      裁剪、滤镜、修复、标注、页面操作及拼版
  library/                     文库页面、状态、对话框和文档卡片
  ocr/                         识别页面
  export/                      导出页面
  settings/                    设置及导出选项界面
  security/                    应用锁界面
  codes/                       条码界面
navigation/
  Screen.kt                    页面模型
  Navigator.kt                 页面栈与导航操作
```

`androidMain`、`iosMain` 和共享测试采用对应包目录。平台入口 `MainActivity`、`MainViewController` 以及可选验收入口保留原有导出位置。原 `Models.kt` 拆成八个文件，其中导航模型进入 `navigation`，持久化模型进入 `core/document`。Android / iOS 原平台大文件分别拆为 Camera、Importers、Sharing、Navigation，iOS 导入代理的持有关系随实现一同保留。

## 复用入口与职责边界

| 工作 | 入口 | 行为 |
| --- | --- | --- |
| 图像处理 | `core/imaging/PageRenderer` | 用 `PageStage` 表达输入已完成的处理阶段，统一几何、修复、滤镜、调整、标记和 JPEG 编码 |
| 页面操作 | `feature/editor/PageOperations` | 注入仓库及编解码器，执行共享页面修改流程 |
| OCR | `core/ocr/DocumentOcr` | 支持单页、全部页面、仅缺失 OCR 的页面；逐页保存和回调，取消后不提交迟到结果 |
| 扫描状态 | `feature/scan/ScanSession` | 管理输入生命周期、任务取消、模式状态与已保存页面的发布 |
| 扫描保存 | `feature/scan/ScanWrite` + `finishScanSave` | 共用写入、取消回滚、状态接受、相册通知和下一步路由 |
| 文库状态 | `feature/library/LibraryState` | 管理查询、排序、选择、批量操作和授权失效后的状态清理 |
| 文库显示 | `LibraryScreen` / `LibraryDialogs` / `LibraryDocumentCard` | 分别负责页面编排、对话框和卡片呈现 |
| 文档存储 | `DocumentRepository` | 保留应用/文件夹权限及事务检查的公开门面 |
| 内部存储 | `DocumentFiles` / `LibraryStore` / `ExportStore` | 分别负责文档文件、文库元数据和不可变导出快照 |

扫描结果只发布本次实际新增的页面 ID。编辑已有页面不会增加拍摄计数或触发新增图片通知；书籍双页、连续拍摄、证件双面、队列导入分别沿用原有路由语义。取消回滚仍以本次写入拥有的页面和文件为范围，内部清理不依赖已失效的用户授权。

渲染阶段避免对已处理基底重复应用几何和滤镜；修改后仍保留原图及必要的可编辑基底，并清除过期 OCR。文库授权失效后的遮罩、保留编辑器状态和隐藏原生对话框的逻辑保持在相应入口。

后续新增功能应放入对应 `feature`；可复用能力放入 `core`；平台差异通过对应接口及两端实现处理。不要让低层文件存储承担用户授权，也不要从页面复制保存计数、OCR 循环或整条渲染流水线。当前生产源码的包声明与目录一致，`core` 没有直接导入 `feature` 或 `navigation`；部分平台适配器仍通过应用组装入口取得依赖，本次未增加 Gradle 模块。

## 同口径代码量

使用 cloc 2.10，统计 Kotlin / Swift 的非空、非注释代码行；包含包声明和导入。生产范围仅为 `commonMain`、`androidMain`、`iosMain` 和 `iosApp/iosApp`，不包含测试、验收工具、资源、配置、报告及生成文件。重构前以动手前保留的本地源码快照为准，包含当时尚未提交的修复。

| 范围 | 重构前 | 重构后 | 变化 |
| --- | ---: | ---: | ---: |
| commonMain | 6,836 | 7,075 | +239 |
| Android Kotlin | 1,155 | 1,170 | +15 |
| iOS Kotlin | 1,448 | 1,461 | +13 |
| Swift | 23 | 23 | 0 |
| 生产代码合计 | 9,462 | 9,729 | +267（+2.8%） |
| 含注释、空行的物理行数 | 10,153 | 10,500 | +347 |
| 生产源码文件数 | 77 | 100 | +23 |

**本次没有减少全项目的生产代码行数。** 导入行由 1,234 增至 1,400，净增 166 行，约占代码行增量的 62%；其余增量来自独立服务、状态及存储边界等实现。重复业务流程已集中，但职责拆分带来的声明和导入使总行数略增。测试代码另行新增，不计入上表。

主要文件的物理行数变化：

| 文件职责 | 重构前 | 重构后 |
| --- | ---: | ---: |
| AppGraph | 199 | 65 |
| DocumentRepository | 754 | 659 |
| LibraryScreen | 393 | 273 |
| FilterScreen | 204 | 187 |
| CaptureScreen | 288 | 282 |
| BookScreen | 153 | 149 |
| OcrScreen | 111 | 98 |
| ExportScreen | 73 | 75 |

Android 原 `AndroidPlatform.kt` 为 318 行，拆分后最大文件为 198 行；iOS 原 `IosPlatform.kt` 为 495 行，拆分后最大文件为 301 行。这些数字代表单文件职责缩小，不能相加作为删除代码量。`DocumentRepository` 仍保留权限、页面事务、文档操作及导出门面的职责。

## 验证

| 验证范围 | 结果 |
| --- | --- |
| Android 共享单元测试 | 75 项，0 失败、0 错误、0 跳过 |
| iOS 模拟器测试 | 76 项，0 失败、0 错误、0 跳过 |
| Android Debug APK / 测试 APK | 构建成功 |
| iOS 模拟器 Debug framework | 链接成功 |
| `kingscanVerification=true` 的附加验收源码 | Android APK / 测试 APK、iOS framework 均构建成功 |
| 独立检测器回放入口 | 重新编译成功；保留的相机截图裁片运行成功 |
| 包目录及引用检查 | 包路径一致；无旧包声明/导入；无 core 直接导入 feature/navigation |
| 补丁及脚本检查 | `git diff --check` 通过；回放脚本语法通过 |
| Android 模拟器四组集成测试 | 4 项，全部通过 |

此次新增 10 个共享测试用例，覆盖渲染阶段与编码、OCR 部分完成及取消、实际新增页发布、文库选择及授权失效状态。新增用例在 Android 与 iOS 两端均通过，原有权限、取消、文件所有权和输出回归测试继续执行。

完整构建命令：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/bytedance/Library/Android/sdk' \
DEVELOPER_DIR='/Applications/Xcode_27.0_beta6.app/Contents/Developer' \
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test \
  :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest \
  :composeApp:linkDebugFrameworkIosSimulatorArm64 \
  --offline --no-daemon -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8'
```

附加验收入口使用相同环境，运行 `assembleDebug`、`assembleDebugAndroidTest`、`linkDebugFrameworkIosSimulatorArm64` 并加 `-PkingscanVerification=true`。安卓原生集成测试通过设备上的 `AndroidJUnitRunner` 分组执行，逐组检查 `OK (1 test)`。

Android 原生测试在本次启动的 Pixel_9 模拟器上逐组运行，结果如下：

| 用例 | 覆盖范围 | 结果 |
| --- | --- | --- |
| `BiometricLockUiIntegrationTest` | 应用/文件夹锁、前后台切换、保留对话框隐藏、系统返回键 | `OK (1 test)` |
| `LocalOutputIntegrationTest` | 修复结果在旋转、滤镜及重开后保留；相册和打印输出 | `OK (1 test)` |
| `OfflineDocumentIntegrationTest` | 离线中文 OCR、可搜索加密 PDF、密码校验、PDF 后续图片导入、原 PDF 保存、涂黑后 OCR 清理 | `OK (1 test)` |
| `LocalGeometryIntegrationTest` | 书籍、拼接、证件物理尺寸 PDF | `OK (1 test)` |

验证证据保存在 [refactor-evidence-20261126](refactor-evidence-20261126/)：

- [完整构建日志](refactor-evidence-20261126/build.log)
- [附加验收入口构建日志](refactor-evidence-20261126/verification-build.log)
- [测试统计及用例名称](refactor-evidence-20261126/test-summary.json)
- [安卓四组集成测试汇总](refactor-evidence-20261126/native.log)，目录内同时保留逐组原始 runner 输出
- [代码量与逐文件对比](refactor-evidence-20261126/metrics.json)
- [检测器回放结果](refactor-evidence-20261126/detector-replay.log)

真实 Face ID / 指纹认证成功仍需在具备对应硬件的真机验收。模拟器应用锁 UI 用例使用测试授权状态，验证前后台遮罩、文件夹隔离、对话框隐藏及系统返回键行为，不能作为真实生物认证成功的证据。本次 iOS 验证覆盖 Kotlin 模拟器测试与框架构建，没有执行完整真机 UI 验收。

工作区变更保留，未提交或推送 Git。
