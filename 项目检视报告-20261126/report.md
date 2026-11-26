# 代码评审报告

- 仓库：KingOfScan
- 检测模式：通用检测 · 整项目静态检视与跨组复核
- 检测范围：完整当前项目 · HEAD 9987fb5 · 141 文件 / 14,455 行
- 生成时间：2026-11-26 10:44
- 检查文件：141
- 审查源码行数：14455

## 检视结论

工程采用共享业务与图像算法、平台原生能力及平台控件的分层方式；保存原照和显式编辑参数，为重编辑、跨端复用和结果核验提供了基础。现有验证记录也区分了模型能力边界和未完成的真机验收。

主报告列出 2 项 P0 隐私保护缺陷和 3 项 P1 功能缺陷，应优先修复设备锁遗漏弹层、明文打印覆盖分享快照及取消任务的资源归属。具体 PDF 误删依赖报告所述时序，不是每次取消都会发生；两条 P0 均限定已核实的 Android 路径。

缺陷来自当前代码、直接调用链及框架/依赖源码复核；这些场景未在本次真实 UI 中复现。两端共享测试通过说明现有用例成功，不能替代独立弹层、旧 URI 授权和取消交错路径的验证。

## 缺陷统计

- P0：2
- P1：3
- P2：0
- 合计：5

## P0 告警

- [P0][安全漏洞] composeApp/src/commonMain/kotlin/com/kingscan/app/App.kt:97 | 设备锁仅隐藏主布局，独立 Dialog 仍可未认证读取和修改文档
- [P0][安全漏洞] composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt:468 | 明文打印覆盖已分享的加密 PDF，旧 URI 授权可读取无密码内容

## 缺陷详情

### 1. [P0][安全漏洞] 设备锁仅隐藏主布局，独立 Dialog 仍可未认证读取和修改文档

- 位置：[composeApp/src/commonMain/kotlin/com/kingscan/app/App.kt:97-98](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/App.kt#L97-L98)
- 置信度：10/10

**问题代码（当前源码）**

```text
97:                 Box(Modifier.fillMaxSize().then(if (AppGraph.locked)
98:                     Modifier.alpha(0f).semantics { hideFromAccessibility() } else Modifier)) {
```

**补充调用链代码**

[composeApp/src/androidMain/kotlin/com/kingscan/app/ui/AndroidComponents.kt:53-54](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/androidMain/kotlin/com/kingscan/app/ui/AndroidComponents.kt#L53-L54)

```text
53: @Composable actual fun ScanDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier, dismissButton: (@Composable () -> Unit)?, icon: (@Composable () -> Unit)?, title: (@Composable () -> Unit)?, text: (@Composable () -> Unit)?) =
54:     AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text)
```

[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/DetailScreen.kt:277-294](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/DetailScreen.kt#L277-L294)

```text
277:     if (organizing) {
278:         AlertDialog(
279:             onDismissRequest = { organizing = false },
280:             title = { Text("标签与备注") },
281:             text = {
282:                 Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
283:                     OutlinedTextField(tagsDraft, { tagsDraft = it }, label = { Text("标签（逗号或顿号分隔）") })
284:                     OutlinedTextField(notesDraft, { notesDraft = it }, label = { Text("备注") }, minLines = 3)
285:                 }
286:             },
287:             confirmButton = {
288:                 TextButton(onClick = {
289:                     refresh(repository.organize(current.id, tags = tagsDraft.split(Regex("[,，、]")), notes = notesDraft))
290:                     organizing = false
291:                 }) { Text("保存") }
292:             },
293:             dismissButton = { TextButton(onClick = { organizing = false }) { Text("取消") } },
294:         )
```

**问题描述**

Android 上启用设备锁，在文档详情打开含已有备注的“标签与备注”对话框，再按 Home 使应用进入后台，返回时不解锁。AndroidDeviceSecurity.kt:64-65 在 ON_STOP 设置 locked=true，但 App.kt:97-118 只把主页面 Box 设为透明并隐藏其无障碍节点，未移除屏幕或对话框。DetailScreen.kt:277-294 的 organizing 状态仍为 true，备注字段和保存回调也没有锁定检查。AndroidComponents.kt:53-54 使用 Material3 AlertDialog；本地 Compose UI 1.8.2 的 AndroidDialog.android.kt:174-214、416-457、497-502 证实其创建独立 ComponentDialog 窗口和 DialogLayout，不继承主 Box 的 alpha/semantics。主窗口内的 DeviceLockScreen 无法覆盖该窗口，因此未认证者仍能看见备注并执行保存。FLAG_SECURE 只限制屏幕捕获，不提供访问控制。本结论限定于已核实的 Android 独立 Dialog 路径，不推定 iOS 弹层行为。

**修复建议**

锁定时关闭或移除所有 Dialog/Popup 的可见窗口，并在业务回调入口检查锁定状态。把弹层可见性统一绑定到设备锁；将草稿保存在独立状态持有者中，解锁后再恢复，不能仅对主页面设置透明度和无障碍隐藏。

---

### 2. [P0][安全漏洞] 明文打印覆盖已分享的加密 PDF，旧 URI 授权可读取无密码内容

- 位置：[composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt:468-474](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt#L468-L474)
- 置信度：9/10

**问题代码（当前源码）**

```text
468:         val path = exportsPath / "$id.pdf"
469:         val temporary = exportsPath / "${id}_${newId()}.tmp"
470:         try {
471:             writeNativePdf(document.pages.map {
472:                 PdfExportPage(pagePath(id, it), it.width, it.height, it.ocr)
473:             }, layoutOptions, temporary.toString(), document.title)
474:             fileSystem.atomicMove(temporary, path)
```

**补充调用链代码**

[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/DetailScreen.kt:198-205](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/DetailScreen.kt#L198-L205)

```text
198:                 DetailAction("系统打印") {
199:                     busy = true
200:                     scope.launch {
201:                         val result = withContext(Dispatchers.Default) {
202:                             runCatching { repository.exportPdf(current.id, repository.settings().export.copy(password = "", searchable = false)) }
203:                         }
204:                         busy = false
205:                         result.fold({ print(it, current.title) }, { onMessage(it.message ?: "打印文件生成失败") })
```

**问题描述**

先在 ExportScreen.kt:48-67 设置非空密码并分享文档 PDF。AndroidPlatform.kt:300-311 将 exports/$id.pdf 映射为 FileProvider URI，并通过 Intent 授予读取权限。让接收 Activity 的任务栈保持活动，例如接收应用使用独立任务，用户通过最近任务返回扫描王，而不是结束接收任务。随后在同一文档点击“系统打印”：DetailScreen.kt:198-205 明确以 password=""、searchable=false 调用同一 exportPdf，AndroidPdfExport.kt:76-82 因空密码不执行 protect；这里仍原子替换同一个 $id.pdf。已复核本地 androidx.core:core:1.15.0 的 FileProvider.java:315-319、653-658、845-907：授权在接收 Activity 栈活动期间有效，每次 openFile 都解析并打开当前路径，URI 不绑定原文件内容。接收方在授权仍有效时重新打开旧 URI，即可读取无密码打印副本；如果分享后文档新增页面，旧授权还可能读到这些未分享的新内容。原子替换不撤销 URI 授权。该漏洞不要求持久授权或所有接收应用都保留任务，也不声称已打开的旧文件描述符会自动指向新文件。

**修复建议**

每次导出生成唯一、不可变的文件与 URI，分享快照和打印快照使用不同路径，禁止将明文输出覆盖到任何已分享路径。为快照设置适当的延迟清理和授权撤销策略，验证旧 URI 在打印、重新导出及文档修改后只能读取原先分享的加密内容。

---

### 3. [P1][并发问题] 取消任务仍读取新扫描会话目标，PDF 回滚还会误删已有文档

- 位置：[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/CaptureScreen.kt:206-209](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/CaptureScreen.kt#L206-L209)
- 置信度：10/10

**问题代码（当前源码）**

```text
206:                 val id = AppGraph.session.documentId
207:                 if (id == null) AppGraph.repository.create(
208:                     if (mode == ScanMode.IdCard) "证件扫描" else defaultScanTitle(), pages)
209:                 else AppGraph.repository.addPages(id, pages)
```

**补充调用链代码**

[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/ImportFlow.kt:79-95](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/ImportFlow.kt#L79-L95)

```text
79:                         for (index in 0 until source.pageCount) {
80:                             val image = source.renderPage(index, ScanLimits.MAX_EDGE)
81:                             val bytes = AppGraph.codec.encodeJpeg(image, ScanLimits.JPEG_QUALITY)
82:                             val page = EncodedPage(bytes, image.width, image.height, FilterType.Original,
83:                                 originalBytes = bytes, edit = PageEdit(filter = FilterType.Original))
84:                             val updated = if (session.documentId == null) repository.create(file.name.substringBeforeLast('.'), listOf(page))
85:                                 else repository.addPages(session.documentId!!, listOf(page))
86:                             if (previousId == null && createdId == null) createdId = updated.id
87:                             session.documentId = updated.id
88:                             addedIds += updated.pages.last().id
89:                         }
90:                         repository.attachOriginalPdf(session.documentId!!, file)
91:                     } catch (failure: Throwable) {
92:                         // Roll back only this file, leaving earlier successful imports intact.
93:                         if (createdId != null) { repository.delete(createdId); repository.purge(createdId) }
94:                         else if (previousId != null) addedIds.forEach { repository.deletePage(previousId, it) }
95:                         session.documentId = previousId
```

**问题描述**

合并连续拍摄与 PDF 导入的共享会话误归属根因，保留较高置信度的拍摄位置。连续拍摄或大幅面模式已进入 CaptureScreen.kt:188-210 的 Default 同步处理时，按 Android 系统返回。App.kt:79-88 不检查 busy，session.cancel 经 AppGraph.kt:73-79、119-125 清空目标并 cancel Job，但不等待退出；同步解码、编码和仓库写入没有取消检查。旧任务随后在这里重新读取全局 documentId：尚未开始新会话时会创建已取消照片的额外文档；已在已有文档 B 点击“加页”（DetailScreen.kt:173-175）时则可把旧照片追加到 B。withContext 返回时的取消不能撤回已完成的磁盘写入。

PDF 同样重新读取共享目标，并额外存在具体的回滚所有权错误（ImportFlow.kt:74-95）。可达高后果路径是：从文稿库新会话导入一个加密 PDF，在 PdfUnlockScreen 输入正确密码，重试任务已记录 previousId=null 并进入首张同步 renderPage；此时按系统返回，App.kt:82-84 删除暂存 PDF 并取消会话；在旧渲染/编码完成前进入已有文档 B 并点“加页”。旧任务在 ImportFlow.kt:84-85 读取 B 并 addPages，第86行却仅因旧 previousId==null 将 B 记为 createdId，尽管本任务没有 create(B)。暂存路径已删除，随后 attachOriginalPdf 的 copy（DocumentRepository.kt:206-217）失败，ImportFlow.kt:93 执行 delete(B) 再 purge(B)，彻底删除已有文档和原图。AndroidPdfSource.kt:9-27 及本地 PDFBox 字节码确认已打开的 PDF 使用持有的 RandomAccessFile，同步渲染不会因 Job.cancel 或路径 unlink 自动停止。此完整误删后果独立置信度为9/10，需要 B 的赋值在旧任务读取目标前完成、渲染正常返回、暂存文件删除成功且回滚写删成功，不是每次取消必现。不能把它简化成仓库通用竞态；仅给仓库加锁也不会修复将 B 误记为本任务新建资源的错误。

**修复建议**

任务启动前捕获不可变的目标 ID、会话版本和资源所有权，后台任务不得重新读取可变全局目标。持久化提交与取消/开始新会话采用同一提交栅栏，在渲染后、每页提交前检查取消；等待旧任务退出后再清理其暂存文件。PDF 的 createdId 只能在实际 create 分支赋值，回滚只能删除本任务新建的文档或本任务追加到捕获目标的页面，绝不能依据 previousId==null 推断所有权。

---

### 4. [P1][业务语义问题] 逐页编辑返回相机后丢失去重状态，自动拍摄会重复同一页

- 位置：[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/CaptureScreen.kt:44-44](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/CaptureScreen.kt#L44-L44)
- 置信度：10/10

**问题代码（当前源码）**

```text
44:     val controller = remember { AutoCaptureController() }
```

**补充调用链代码**

[composeApp/src/commonMain/kotlin/com/kingscan/app/App.kt:99-104](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/App.kt#L99-L104)

```text
99:                 when (val screen = navigator.current) {
100:                     Screen.Library -> LibraryScreen(navigator, ::message)
101:                     Screen.Capture -> CaptureScreen(navigator, ::message)
102:                     Screen.Crop -> CropScreen(navigator)
103:                     Screen.Filter -> FilterScreen(navigator, ::message)
104:                     Screen.Book -> BookScreen(navigator, ::message)
```

**问题描述**

开启自动拍摄、关闭连续拍摄，保持纸张或证件正面不动；自动拍摄后在滤镜页点“再拍一页”，或证件模式保存正面并返回拍反面。shutter 在 CaptureScreen.kt:50-54 记录指纹，但 controller 仅由当前屏幕 remember 保存。App.kt:99-104 互斥展示 Capture、Crop、Filter、Book，进入编辑即移除 CaptureScreen；FilterScreen.kt:197-205 和 BookScreen.kt:90-93 经 Navigator.backToCapture 返回时会创建全新的控制器。CaptureAnalysis.kt:63-78 的 captured 因此为 null，update:108-129 在同页持续满足清晰、完整边缘和稳定条件约900ms后再次触发拍摄，无需翻页。普通文档产生重复页，证件流程可把同一正面当作反面继续处理。这是核心自动扫描去重契约被导航销毁破坏，不依赖旧照片回调或仓库并发。

**修复建议**

将成功拍摄的指纹与去重状态放到扫描会话或随会话存活的状态持有者，编辑后返回相机时恢复；仅在明确新建/取消扫描、确认新页面或需要重置的模式切换时清除。覆盖自动拍摄到编辑再返回、书籍继续拍及证件正反面的同页去重路径。

---

### 5. [P1][逻辑错误] 批量批注部分成功后仍使用旧 PageMeta，重试会读取已删除底图

- 位置：[composeApp/src/commonMain/kotlin/com/kingscan/app/ui/AnnotationScreen.kt:90-100](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/ui/AnnotationScreen.kt#L90-L100)
- 置信度：10/10

**问题代码（当前源码）**

```text
90:                                 targets.forEach { target ->
91:                                     val bytes = repository.readBytes(repository.annotationBasePath(documentId, target))
92:                                     val original = AppGraph.codec.decode(bytes, ScanLimits.MAX_EDGE)
93:                                     val applied = if (target.id == page.id) snapshot else target.edit?.marks.orEmpty() + addedWatermarks
94:                                     val rendered = PageMarkup.render(original, applied)
95:                                     repository.replacePage(documentId, target.id, EncodedPage(
96:                                         AppGraph.codec.encodeJpeg(rendered, ScanLimits.JPEG_QUALITY), rendered.width, rendered.height, target.filter,
97:                                         edit = (target.edit ?: PageEdit(filter = target.filter)).copy(marks = applied),
98:                                         // OCR must be recognized again after a redaction or other content change.
99:                                         annotationBaseBytes = bytes.takeIf { target.annotationBaseFileName == null },
100:                                     ), keepAnnotationBase = true) ?: error("页面不存在")
```

**补充调用链代码**

[composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt:176-185](file:///Users/bytedance/workspace/kingOfScan/composeApp/src/commonMain/kotlin/com/kingscan/app/data/DocumentRepository.kt#L176-L185)

```text
176:             val replacement = storePage(id, page, previous.id, createdFiles).let {
177:                 it.copy(originalFileName = it.originalFileName ?: previous.originalFileName,
178:                     annotationBaseFileName = it.annotationBaseFileName ?: previous.annotationBaseFileName.takeIf { keepAnnotationBase })
179:             }
180:             val pages = document.pages.toMutableList()
181:             pages[index] = replacement
182:             val updated = document.copy(pages = pages, updatedAt = clock())
183:             writeMeta(updated)
184:             // Commit metadata before removing the previous immutable image.
185:             removePageFiles(id, previous, keepOriginal = replacement.originalFileName, keepBase = replacement.annotationBaseFileName)
```

**问题描述**

对至少两张尚无 annotationBaseFileName 的页面添加文字水印并勾选“应用到文档全部页面”，第一张提交成功，第二张因磁盘空间不足等写入错误失败。DocumentRepository.replacePage:176-185 为第一张保存新的批注底图、提交新 PageMeta，并删除其旧 fileName。AnnotationScreen.kt:105-106 失败时只提示 completed，不更新第42-43行 remember 的 document/page。恢复空间后在同一屏幕再次保存，targets 仍来自旧 document.pages；第一张旧 PageMeta 的 annotationBaseFileName 为 null，annotationBasePath（DocumentRepository.kt:199-200）退回已被删除的旧 fileName，因此此处 readBytes 在第一张即失败，无法继续保存剩余页面。无需并发即可触发；并非未捕获异常，也不声称第一次已成功保存的页会丢失。

**修复建议**

部分成功后按页面 ID 重新加载已提交 PageMeta 和批注基线，保留未提交草稿并记录待重试目标；重试只处理未完成项，确保不读取已替换路径、不重复叠加已保存水印。

---

## 本次验证与边界

| 验证项 | 结果 | 说明 |
|---|---|---|
| Android 共享/JVM 测试 | 38 项通过；0 失败、0 错误、0 跳过 | 本次执行 :composeApp:testDebugUnitTest --rerun-tasks；实际 XML 汇总，10 个测试套件。 |
| iOS Kotlin/Native 测试 | 39 项通过；0 失败、0 错误、0 跳过 | 本次执行 :composeApp:iosSimulatorArm64Test；实际 XML 汇总，11 个测试套件。使用 Android Studio JBR 与 Xcode 27 完整开发目录。 |
| Android Debug Lint | 0 Error；48 Warning | 本次执行 :composeApp:lintDebug 并读取 XML；主要为依赖更新、配置和控件建议。4 项静态 Context 告警的当前注入均为 applicationContext；3 项 TLS 告警位于未被应用网络链路调用的依赖类，不作为当前可利用漏洞。 |
| 五组静态审查与跨组校验 | 已完成；报告收敛为五项优先问题 | 覆盖共享数据/生命周期、相机/几何/拼接、编辑界面、原生 OCR/PDF/输出/设备保护，以及离线模型/转换验证。按实际触发条件、影响、置信度筛选；未配置仓库自定义工作流。 |
| 本次未执行的验收 | 不计为通过 | 未执行真实相机、完整 Android/iOS UI、跨应用旧 URI 读取、设备认证交互、低内存压力、Release 归档/签名或竞品质量对照；历史结果仅作为对应场景的背景证据。 |

## 后续改进建议（不计入缺陷）

统一异步任务所有权：用独立会话状态和明确目标快照管理导入、拍摄和编辑任务，规定取消、退出页面、切换文档与持久化提交的先后关系。系统返回和页面返回应遵循同一契约，临时文件只由持有任务清理。

强化本地存储恢复：为完整的读取、修改、文件写入、元数据提交和清理设置文档级事务边界；明确提交后清理失败的结果。用持久化标记和恢复机制处理未完成删除与孤立文件，继续保留原照及来源归属。

补齐异常与时序验证：优先覆盖锁定时独立弹层、分享快照不可变、取消后切换会话、批量操作第二项失败及重试，断言原照字节、文档 ID、页序、输出内容和草稿保持正确。现有算法测试通过不能替代这些路径。

建立真机性能预算：在低内存 Android 与实际 iPhone/iPad 上记录解码、EXIF 纠正、几何、模型加载/推理及多页 PDF 导出的峰值内存和阶段耗时，定义像素、页数、总字节与同时在途任务上限；据此设计缩略图、磁盘缓存与取消响应目标。

收敛发布与模型维护流程：为共享测试、Lint 和模型资源/验证记录的内容哈希建立自动检查；模型候选应全量预检后作为一组提交，并可在失败时回滚。当前仓库未见 CI 配置，Release Lint 也被关闭，建议将正式发布检查作为明确门禁，再逐项处理适用的告警。

继续验证原生体验边界：重点补齐 iOS 权限恢复、iPad 方向变化、原生步进控件、相册/认证/打印，以及真实书页和大幅面图像；平台差异继续集中在控件和系统能力层。缺少同源竞品实拍与 UI 对照时，维持现有文档中的效果边界说明。
