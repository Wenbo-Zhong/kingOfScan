# 五模型小包验证

2026-11-23。保留 DocRes、DIS 手写、HandsWild EYTH、DocDiff 和 ESDNet，采用 8 位权重量化。Android gzip 随包，首次使用流式解压、SHA-256 校验、原子写入后 mmap；iOS 使用官方 Core ML 权重量化，仍兼容 iOS 16。全部离线处理。

| 项目 | 原版 | 本次 |
|---|---:|---:|
| Android 模型文件 | 731.17 MiB | 185.34 MiB |
| Android 随包模型资源 | 731.17 MiB | 161.97 MiB |
| iOS 源模型包 | 366.37 MiB | 184.77 MiB |
| iOS 实际编译模型 | 未同配置测量 | 185.16 MiB |

Android 模型随包资源减少约 77.8%；iOS 源模型包减少约 49.6%。量化保留浮点计算，运行内存、速度不按相同比例下降。Android 完整模型缓存另占约 185 MiB，可由系统清理，清理后从随包资源重新生成。

用户提供的 `CamScanner.apk` 为 171,567,698 bytes（171.57 MB / 163.62 MiB），仅包含 ARM64 原生库；已有分析显示部分增强功能有云端接口。该 APK 体积不能证明其所有处理都是离线模型，也没有建立本应用与它的同源质量等效结论。

实际可安装的 ARM64 release APK 为 **198,520,679 bytes（198.52 MB / 189.32 MiB）**，使用本机 Android debug keystore 作测试签名。普通发布构建启用 R8/资源裁剪；五个 `.onnx.pack` 资源保持 gzip 内容，避免 AGP 对 `.gz` 源文件的自动解压。原生库以 ZIP 压缩，Android 安装时另行解压约 30.56 MB。此前同模型的 `.gz` 文件被资源合并工具展开，导致 242.14 MB 包及加载路径不匹配；该中间包没有作为最终结果。

[最终包检查](android-final-bundle.json)确认五模型解压后的长度与 SHA、仅 ARM64、验证入口未进入正式代码、无 INTERNET 权限。实际安装成功，[正式构建冷启动](android-release-launch.txt)通过。包内模型 169.84 MB、压缩原生库 11.43 MB、压缩 DEX 2.50 MB，其余资源及 ZIP/签名结构约 14.75 MB。[iOS 编译模型实测](ios-model-packages.json)仅统计模型，不含应用框架，不能视为整个 iOS 安装包大小。

实图量化差异：

- [手指四向融合与边缘扩展](fingers-selections.png)：局部手指 / 完整双手的选区 IoU 为 98.02% / 99.14%，无手文档两版均未检出。[原始测量](fingers-selections.json)
- [手写选区](handwriting-outputs.png)：默认阈值与原模型重合约 99.6%，量化没有消除原版已有的漏选或邻近笔画误选。
- [高清](docres-outputs.png)、[摩尔纹](esdnet-outputs.png)、[印章](watermark-outputs.png)：与原模型输出 PSNR 均超过 40 dB；仍保留原版实际效果及限制。

这里的 IoU/PSNR 是压缩前后差异，不是标注准确率。还原模型图从左到右为输入、原版模型、Android INT8、iOS INT8。手写图显示模型选区，非填充后的恢复图。[完整宿主数值](quantization-metrics.json)；权重来源、SHA 与许可见两端 `models.json` 和[模型说明](../../models/README.md)。

未采用的小模型：tiny U-Net 漏局部手指并误选文档文字；YOLO26 手分割会选中部分纸面；手指四/六位均匀量化明显改变选区。失败候选没有进入应用资源；收尾时已清理其临时权重及重复模型包，效果测量和参考输入另保留在仓库外的研究目录。

iOS 27 ARM64 模拟器的[原生验收](ios-native-result.txt)通过：五个实际 tensor 对压缩模型宿主参考 MAE<0.003，五种整页处理、选区外像素、原照/参数保存、旋转重渲染、OCR 失效、纯白页无检出、固定噪声、取消及版本缺失检查均通过。这里沿用之前的验证宿主并替换全部编译模型与对应参考；本次 iOS 推理代码未变更。

Android ARM64 模拟器的[两个原生集成测试](android-instrumentation.txt)通过（440.125 秒）：[五模型实际输出](android-native-result.txt)对压缩宿主参考的 MAE 为 2.72×10⁻⁸ 至 5.70×10⁻⁷，低于 0.003 阈值；完整处理、未选像素、原照/版本、旋转重编辑、OCR、空白拒绝、噪声及取消检查均通过。另通过同长度缓存损坏和缓存删除后的重建检查，重建前后推理输出逐值一致，未留下 `.tmp` 文件。[Android 数值](android-native-metrics.json)分别列出对压缩宿主及原始模型的差异；单次 tensor 阈值 IoU 与上面的四向融合后选区 IoU 统计范围不同。[27 项共享测试](android-shared-tests.json)全部通过。

Android 原生整页结果：[高清](android-docres-page-result.jpg)、[手写](android-handwriting-page-result.jpg)、[手指](android-fingers-page-result.jpg)、[印章](android-watermark-page-result.jpg)、[摩尔纹](android-esdnet-page-result.jpg)。iOS 对应图片以 `ios-` 前缀保存在本目录。Android 日志中的首次耗时包含解压、SHA、会话创建及一次推理，不能当作纯模型速度或真机耗时。
