# 文档外框检测修复

本目录保留首次外框定位修复的验证结果；后续跨帧稳定性修复与真机反馈见[预览选框稳定性修复](../preview-stability/RESULTS.md)。

2026-11-25。截图回放中已从桌面误选改为识别灰色印刷卡片的完整外框。[改前 / 改后对照](before-after.png)左侧红框为旧生产代码输出，右侧绿框为新生产代码输出。图片原有橙框属于截图中的 UI，仍保留在两侧输入中。

旧算法使用全局 Otsu 阈值，再从明、暗两个最大连通区域中选择文档。这个场景中卡片与桌面颜色接近，印刷图案又明显更暗，因此选中了桌面。新算法在 `commonMain` 中提取多档边缘，寻找闭合区域，合并被文字或横线分开的内部区域，以四条直边的支持度校验外框，最后拟合真实边线。圆角和侧边凹口采用直边延长线的交点。没有可靠外框时自动检测返回 `null`；手动裁边仍提供可拖动的默认框。

实现由 Android、iOS 共享，不新增模型。删除了旧最大明暗区域选框逻辑；滤镜仍使用的 `boxBlur` 和 `otsu` 保留。

## 截图回放

输入为用户提供的 1080×2400 截图，按当前 4:3 相机预览布局提取 `(0,480)–(1080,1920)`，得到 1080×1440 图像。原始上传文件未修改。[回放输入](camera-screenshot-crop.png)仍含界面覆盖，并非原始相机帧。

参考框由人工在四条可见直边上选取端点，再求延长线交点；具体端点、输入及源码 SHA-256 见[回放数值](replay-results.json)。这是近似人工参考，不是相机标定结果。没有获得扫描全能王对同一原始帧的输出，因此没有进行两款应用的定量质量对比。

| 回放 | 外框与近似人工参考的 IoU | 平均角点距离 |
|---|---:|---:|
| 修改前，原方向 | 32.31% | 364.66 px |
| 修改后，原方向 | 99.08% | 2.67 px |
| 修改后，顺时针 90° | 99.08% | 2.43 px |
| 修改后，顺时针 180° | 99.15% | 2.18 px |
| 修改后，顺时针 270° | 99.01% | 2.71 px |

回放脚本直接编译生产 Kotlin 检测代码，图像与几何类从生产源码提取；Python 只负责输入、绘图和测量。四个方向均断言参考 IoU >97%。桌面 JVM 预热后中位耗时约 30–34 ms，首次约 176 ms；这些数值不代表手机实时性能。

## 检查与安装包

[构建检查](build-validation.json)记录 33 项共享测试全部通过，以及 iOS ARM64 真机目标、ARM64 模拟器目标编译通过。新增 6 项回归覆盖相近颜色且带印刷内容、四向旋转、弱对比、圆角与凹口、贯穿纸面的横线、空白/渐变/圆形物体拒绝以及缺边拒绝。原有滤镜、透视裁切、拍摄稳定性等测试继续通过。

[修复版 ARM64 release APK](../../artifacts/KingScan-document-edges-arm64-20261125.apk)为 **198,528,871 bytes（198.53 MB）**，启用 R8 和资源裁剪，使用本机 Android debug keystore 作测试签名；v2/v3 签名与 ZIP 对齐检查通过。[包内检查](android-bundle.json)确认五个原有离线模型解压后的长度、SHA-256 正确，仅包含 ARM64 原生库。APK SHA-256：`c0cd97a5cfd0ecf41027d7a18cdc861df6adc474cc783ffd70dc26b7961e127a`。

本次没有连接 Android 设备，也没有完成新的真实相机预览验收。需要用修复包在原场景复验选框、稳定性和手机耗时；iOS 结果为编译检查。

## 复现

需 Python 3 + Pillow、JDK 17 以上，以及 Gradle 已缓存的 Kotlin 2.1.21 编译器。仓库根目录执行：

```sh
python3 verification/document-edges/replay.py \
  --screenshot /Users/bytedance/Desktop/Screenshot_20261125_171314.png \
  --java "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" \
  --before-ref ad04d2a22de71694bcde82e42dd8dd44b4720c6d

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :composeApp:testDebugUnitTest \
  :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64
```

脚本同时编译指定旧 Git 版本与当前检测代码；编译临时文件和完整日志写入系统临时目录。
