# 预览文档目标选择修正

2026-11-26。用户提供的截图中，KingScan 框住了笔记本触控板，扫描全能王框住了桌上的印刷卡片。本次调整共享检测器的候选排序和包含关系处理，保留此前的速度优化，并已将整合后的 ARM64 Release 包覆盖安装到 M2012K11AC，启动成功。

原评分主要由四边支持率和面积决定，容易让背景的强矩形边界压过低对比度、带圆角或缺口的卡片。完全包含关系还会让不准确的大框排除更合适的小框。新增的两项无 UI 覆盖层回归测试在改前分别选中了背景矩形和包围文档的大矩形，改后全部通过。截图回放进一步暴露了斜印刷线组成不规则四边形的竞争问题，现已加入温和的矩形形状评分。

`DocumentEdges` 继续要求四边支持率至少 0.55，使用原来的 480 像素检测尺度、四组 Canny 阈值、区域提取、外边拟合和圆角/缺口直边延伸。排序现在综合最弱边支持、平均边支持、取景中心距离、框内细节密度和角度形状，面积只保留较小的权重。框内细节通过行前缀计数覆盖内缩四边形内的全部像素，避免固定网格与印刷条纹发生采样混叠；它是评分加分项，空白页仍可检测。完全包含的外框需要评分不低于内框才能排除内框，沿共同外边划分的印刷半页继续优先保留整页。

这些权重是几何和内容启发式评分，并不是语义识别的置信概率。密集背景纹理、多个同时出现的文档及极端透视仍需更多真实相机输入验证。

[截图回放对比](../verification/evidence/document-selection/before-after.png)在同一裁剪输入上运行改前、改后实际 Kotlin 检测器。原截图的橙色界面框保留在图中；红色和绿色分别是两版重新计算的结果。20 项检查覆盖两张截图、原始裁剪和近似去描边裁剪、四个旋转方向，以及上一帧仍偏好触控板的情况，均通过完整卡片外框校验。角点参考来自人工选取的可见长边延伸，属于近似标注。

截图包含已绘制的框和 UI，去描边会影响附近真实边缘，无法等同于原始相机帧；因此同时保留原裁剪、修补裁剪和掩膜，并使用无覆盖层合成场景验证排序。两张截图的相机位置和拍摄时刻不同，不能据此计算与扫描全能王的同帧精度或速度差。

Android 82 项、iOS 83 项测试通过，iOS ARM64 目标编译成功，Android Debug 和 R8 Release 构建成功。此前保留的 48 帧均持续显示选框，超过 0.08 的相邻角点跳变和超过 35% 的面积跳变均为 0；最大相邻角点位移仍为 0.01152。两版逐帧坐标存在微小差异，最大同帧角点差为 0.00251，未声称坐标完全相同。原稳定性输入是此前处理过的录屏裁剪，原视频未修改。

M2012K11AC 真机运行了同一保留输入的改前、改后 Debug 测量，以下仅包含算法耗时。改前指的是已完成速度优化、尚未修正目标排序的版本。

| 真机 Debug 测量 | 选框修正前 | 选框修正后 |
|---|---:|---:|
| 检测中位数 | 121.55 ms | 124.39 ms |
| 检测 P95 | 127.38 ms | 130.45 ms |
| 整帧分析中位数 | 128.90 ms | 133.88 ms |
| 整帧分析 P95 | 133.78 ms | 137.54 ms |

目标排序增加约 2.84 ms 检测中位耗时，约 2.3%。之前的位打包形态学、精确可分离平滑、80 ms 安卓取样间隔，以及首个有效候选立即显示的逻辑继续保留；确认前的框不能推进自动拍摄。真机还对用户卡片截图、90° 旋转和去除扫描全能王描边的输入执行了角点断言，三项通过。上述数据来自 Debug 算法回放，不是 Release 相机启动、对焦、取帧或画面绘制的端到端速度。

[最终 APK](../build/device-build-20261126/KingScan-android-outline-target-arm64-release.apk)为 198,553,447 bytes，非 debuggable，仅含 ARM64 原生库，使用与原包相同的本地签名。v2/v3 签名、ZIP 对齐、五个模型压缩前后的长度与 SHA-256 均校验通过。2026-11-26 17:41:24 已通过 ADB 流式覆盖安装并启动成功，保留原数据，测试 APK 已移除。手机随后断开连接，安装后包文件的远端 SHA-256 未采集；本地交付包 SHA-256 为 `605abce2706376d1d2cc33e625b4ac9cafed243054e9434fb220772c68171c85`。

[完整验证记录](../verification/evidence/document-selection/verification-results.json)、[截图结果](../verification/evidence/document-selection/replay-results.json)、[改前源码快照](../verification/evidence/document-selection/before-sources.tar.gz)和[完整日志](../verification/evidence/document-selection/logs)已保存在工作区。修改未提交 Git。

复测截图可运行以下命令，编译中间文件存入临时目录：

```sh
python verification/document-selection/replay.py \
  --ours /path/Screenshot_20261126_170555.png \
  --comparison /path/Screenshot_20261126_170608.png \
  --before-root /path/unpacked/composeApp/src/commonMain/kotlin/com/kingscan/app \
  --output /tmp/selection-replay
```

需要 Pillow、opencv-python-headless、JDK 和缓存的 Kotlin 2.1.21 编译器；`--before-root` 可省略。真机测量使用 `PreviewLatencyIntegrationTest` 的 `input`、`label` 和可选 `expected_quad` instrumentation 参数。真实相机同场景的出框速度和选框体验仍需用户复测。
