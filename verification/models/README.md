# 随包离线文档模型

2026-11-23 接入 `kingscan-document-ai-20261123-v1`。五项均实际执行训练权重，在设备上处理图片；没有联网下载模型或服务端处理。

| 入口 | 采用的开源方案与权重 | 处理范围及许可 |
|---|---|---|
| 智能高清 | [DocRes](https://github.com/ZZZHANG-jx/DocRes)，作者链接的 [HF 权重](https://huggingface.co/spaces/qubvel-hf/documents-restoration) | 文档清晰化，BGR 原图及 Sobel 提示，256 重叠分块；保留原尺寸，非超分辨率。MIT |
| 去黑色手写 | [DIS-Handwriting-Remover](https://huggingface.co/Inoob/DIS-Handwriting-Remover)，[作者代码](https://github.com/ivanhe123/Handwriting-Removal-DIS)、[IS-Net](https://github.com/xuebinqin/DIS) | 专用手写分割，512 预处理，恢复选区到原图并补纸面。模型卡/IS-Net Apache-2.0；作者适配代码 MIT |
| 去手指 | [HandsWild](https://github.com/aurooj/Hand-Segmentation-in-the-Wild) 的 EYTH ResNet101/RefineNet [公开权重](https://drive.google.com/file/d/12HRYXdHWOGkl71QqUdlijCq2w2ARa6-M/view) | 保持比例、512、四个 90° 方向置信度最大融合；仅选区补纸面。HandsWild 代码 MIT；基础 RefineNet 非商用，权重无独立声明 |
| 去水印 | [DocDiff](https://github.com/Royalvice/DocDiff)，`seal_init.pth` / `seal_denoiser.pth` | 原训练 100 步 alpha 表，固定 99/74/50/25/0 五步 DDIM；初始增强与残差扩散均运行，RGB/256 重叠分块。本轮实图验证为红色印章，通用黑白或复杂水印效果未验证。MIT |
| 去摩尔纹 | [UHDM / ESDNet](https://github.com/CVMI-Lab/UHDM)，作者 [混合数据 demo 权重](https://huggingface.co/spaces/ECCV2022/Screen_Image_Demoireing) | RGB/512 重叠分块，适用于拍屏摩尔纹。Apache-2.0 |

各权重及转换模型的 SHA-256、来源版本、输入输出形状与**macOS 转换参考**误差在两端 `models.json`。该误差不是 iOS/Android 运行测量。原始许可证和 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)随包；GPL 只适用于本项目代码，不替代上游条款。手指权重用于本地研究/非商用，商业或再次发布前应明确权利方授权，不能把整个模型包宣称为 MIT。

Android 的 `.onnx` 不压缩并用只读 mmap 加载，首次核对 SHA-256；ONNX Runtime 1.23.2，关闭遥测。其 XNNPACK 对 EYTH DAG 的分区/优化实测报缺失节点，因此手指明确使用 CPU，其他模型使用可用的 XNNPACK。iOS `.mlpackage` 由 Xcode 编译为 `.mlmodelc`，Core ML 使用 CPU/GPU，按实际输出 strides 读 float32。两端串行推理，只缓存一个模型；编辑界面另外缓存两个前缀结果，减少撤销/追加重算。

模型文件约 Android 731 MiB、iOS 366 MiB，其中手指约 450/225 MiB。大图按输入尺寸输出，分块不等于一次整图推理；耗时随分块数增长。预处理逐行检查取消，调用原生模型之前再次检查，避免已取消任务继续加载或推理；正在进行的单次原生推理不能立即打断。原照、处理版本、强度、噪声 seed 和创建方向随编辑保存；旋转后的重渲染回到各编辑创建方向计算，再转回页面方向。失败或无检出不会自动伪造选区。

分割任务先用模型预测选区，扩展抗锯齿边缘，然后估计局部纸面并作边界传播/调和填充；选区外直接采用源像素。默认手指阈值 0.2、膨胀 18/512，手写阈值 0.35、膨胀 3/512，可用强度调整阈值。不是恢复被遮挡的印刷文字。实图手写有漏选/相邻笔画误伤，手指补全略灰；应通过对比、撤销或手动补选检查。

HandsWild `refinenet_files/gen_class_info_ego.m` 明确类别为 `background / hand / void`，标签为 `0 / 1 / 255`；本次取 softmax 的 hand 通道，未把 void 或背景通道当作手指，也未只区分左右手中的一类。

水印五步相对百步在一张真实票据参考上 MAE `0.00745`、最大误差 `0.4067`，并非数值等价。转换验证的分块/整图 DocRes MAE `0.00606`、ESDNet MAE `0.01727`，没有建立与 CamScanner 同源质量或性能等同结论。

未采用 irllabs/handtrack 的颜色森林：在只露手指的真实裁图上漏检，并误选红标签。MediaPipe 关键点生成遮罩无法保证局部手指识别。其他手写候选有缺公开权重或仅合成字符训练的问题。最终资源只包含表中五个模型，没有这些中间尝试。

## 转换与复验

本目录保留实际使用的推理适配/导出/验证脚本。脚本没有打包完整上游仓库、原始训练优化器或外部照片。执行时需要将来源目录、权重和验证输入按脚本引用放在同一研究目录；可将这些脚本复制到该目录。`stage_models.py --research-root` 可直接使用已有研究目录。

实际环境：Python 3.13.16、Torch 2.7.1、NumPy 2.2.6、OpenCV 4.12.0.88、coremltools 9、ONNX Runtime 1.32、h5py/scipy/einops/Pillow/onnx。上游代码按 manifest 的 `code_revision` checkout；权重严格载入，不使用 `strict=False`。

1. `read_hand_mat.py` 解码 EYTH MAT 的 MCOS payload，生成 `hand-net-*.json`。`hand_refinenet.py` 独立翻译序列化 DAG 和全部权重，BN moments 折成 affine，MATLAB bicubic 使用 a=-0.5 和对称边界。本机没有 MATLAB，**未与 MATLAB 原执行数值比较**。
2. `hand_refinenet.py`、`validate_hand_rotations.py` 生成手指输入/四向参考；`validate_seal.py` 生成真实票据及 100/20/10/5 步参考。
3. 在研究目录运行下列转换，再用 `validate_tiles.py` 验证重叠融合。所有数值验证需先通过，才能 stage。

```bash
python convert_mobile.py docres --edge 256
python convert_mobile.py handwriting --edge 512
python convert_mobile.py fingers --edge 512
python convert_mobile.py watermark --edge 256
python convert_mobile.py esdnet --edge 512
python stage_models.py --research-root RESEARCH_DIR --repo-root REPO_DIR
python make_native_fixtures.py
```

`make_native_fixtures.py` 输出实际 `/tmp/kingscan-ai-fixtures`；float32 采用 little-endian。测试调用生产 `PageRetouch`，包括五模型与 Torch 输出 MAE<0.003、预处理≤1/255、五种整页输出、选外像素、原照/版本/旋转/重开/OCR、空白拒绝、独立噪声、取消和缺版本。设备结果见 [RESULTS.md](../RESULTS.md)。

```bash
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -PkingscanVerification=true
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb push /tmp/kingscan-ai-fixtures /data/local/tmp/kingscan-ai-fixtures
adb shell run-as com.kingscan.app mkdir -p files/ai-verification
adb shell run-as com.kingscan.app cp -R /data/local/tmp/kingscan-ai-fixtures/. files/ai-verification/
adb shell am instrument -w -e class com.kingscan.app.DocumentAiIntegrationTest \
  com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner
bash verification/ios/verify-ai.sh SIMULATOR_UUID /tmp/kingscan-ai-fixtures
```

`install_ui_fixtures.py` 另外创建五个 `localaiverification*` 文档，只用于真实 UI，保留其他文档。正常构建不包括验证 Kotlin 入口。

在仓库根目录运行 Android 实际界面验收：

```bash
python verification/models/install_ui_fixtures.py /tmp/kingscan-ai-fixtures --android-serial emulator-5554
./gradlew :composeApp:assembleDebugAndroidTest
adb install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb shell am instrument -w -e class com.kingscan.app.DocumentAiUiIntegrationTest \
  com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner
```

原生界面驱动在读取时关闭“等待动画空闲”，以免无限进度动画拖延界面树读取；测试结束恢复原设置。截图、实际保存图片和元数据写入测试设备的 `files/ai-ui-verification/`。仅在测试 APK 中包含这个入口。另保留外部 ADB 脚本：

```bash
python verification/android/ai.py --serial emulator-5554
```

脚本检查实际取消、原图对比、撤销/重做、保存、显式参数、原照字节和重新进入预览；不把未出现的临时进度文案算成失败或成功。流程在已启动的正式应用中执行，冷启动结果单独记录，避免重复冷启动受测试模拟器的系统调度超时影响。

本轮原生界面驱动的五组流程及两项历史/取消检查[全部通过](../evidence/ai-android-ui/result.txt)，[运行日志](../evidence/logs/kingscan-ai-final-android-ui-runtime.log)为 `OK (1 test)`、`1759.963 s`。五份[保存文件与原照独立核对](../evidence/ai-android-ui/saved-file-checks.json)通过。该模拟器使用 SwiftShader、12 GiB RAM，前半段实际推理及截图曾很慢；后半段临时关闭系统动画，结束恢复默认。不能把本轮结果当成真机速度测量。界面跟踪已从最终生产包清理，两端正式包均重新构建并安装启动。

测试完成后导出设备上的证据，再清理这五份自建文档：

```bash
adb exec-out run-as com.kingscan.app tar -C files/ai-ui-verification -cf - . > ai-ui-results.tar
mkdir -p ai-ui-results
tar -xf ai-ui-results.tar -C ai-ui-results
```

## 验证图片的来源

图片仅作本地验证证据，外部照片不继承代码仓库许可，也不进入应用模型资源：

- 高清：DocRes `input/for_debluring.png`，作者演示输入；本次没有核定拍摄/合成退化来源，因此不称其为新的实拍标注集。
- 摩尔纹：上述 ESDNet HF demo 的 `001.jpg` 拍屏样例，原生中心裁图。
- 黑色手写：[HandWritingEraser-Pytorch](https://github.com/AndSonder/HandWritingEraser-Pytorch) `images/11.jpg` 的左侧原图；没有把右侧作者结果混作输入。
- 手指：[EgoHOS](https://github.com/owenzlz/EgoHOS) 公开 `testimages` 内 `thu_draw_paper_RGB_draw_paper_lzy_2_38.jpg` 的局部裁图（200×110），只保留贴纸面的手指，避免仅凭手掌验证。
- 水印：DocDiff [论文 HTML](https://arxiv.org/html/2305.03892v1) Figure 6 展示的真实盖章票据，本次截第一张票据的 256 patch。没有用作者合成水印页来冒充真实拍摄输入。
