# 系统生物识别应用锁与文件夹锁

应用锁入口：设置 → 隐私保护 → 应用锁。

文件夹锁入口：文稿库 → 选择文件夹 → 文件夹加锁。启用后立即收起内容，再次进入需要认证；已经打开的受保护文件夹提供“立即锁定”和“关闭文件夹锁”。移除文件夹前需先认证关闭其锁。

## 系统接口与可用条件

| 平台 | 认证接口 | 启用条件 |
| --- | --- | --- |
| iOS | `LAContext` + `LAPolicyDeviceOwnerAuthenticationWithBiometrics` | 系统已录入 Face ID 或 Touch ID，且授权可用 |
| Android | 系统 `android.hardware.biometrics.BiometricPrompt` | Android 9 / API 28 及以上，有生物识别硬件并可完成系统认证 |

Android API 29 及以上通过 `BiometricManager` 检查状态；API 28 检查指纹录入情况，面容／虹膜设备由实际系统认证结果确认。Android API 30 及以上允许系统支持的 Class 2 / Class 3 生物识别。

没有设备密码回退或自定义应用密码。不支持系统 BiometricPrompt 的安卓设备禁用应用锁、文件夹锁入口。未录入生物识别时需先在系统设置中完成录入。临时不可用、锁定或取消认证不会解除已保存的保护。

## 保护行为

- 应用锁在冷启动和进入后台时生效；文件夹授权在后台全部撤销。解锁应用后，受保护文件夹仍需单独认证。
- 启用、关闭锁及进入受保护文件夹均通过系统认证。每次请求使用新的 `CancellationSignal` / `LAContext`；过期、取消、替换和重复成功回调不能授予访问权限。
- 未解锁的文件夹文档不进入全部、收藏、搜索或回收站结果。原图、批注底图、原始 PDF、已缓存路径、导出及移动操作同样检查授权。
- 锁定时保留未保存的编辑状态，并隐藏编辑画面、弹窗和无障碍内容。Android 使用 `FLAG_SECURE`；iOS 使用系统窗口遮挡层保护后台快照。
- 同一受保护文件夹内生成的副本、合并及拼版文档保留文件夹关联。事务中断后的内部清理仅回滚该事务新增的文件和页面，后台撤销授权不会阻止清理。
- 分享、打印及相册发布前检查访问权限；分享原始文件时生成不可变副本。用户已交给其他应用、系统打印或相册的副本由对应系统／应用管理。

这是应用内访问保护；文件存储方式仍为应用沙盒文件。

## 自动验证

共享状态及仓库回归测试：

```bash
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test
```

安卓模拟器或测试设备的遮罩、弹窗、后台和返回键验证：

```bash
./gradlew :composeApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kingscan.app.BiometricLockUiIntegrationTest
```

`BiometricLockUiIntegrationTest` 使用测试授予的授权状态验证实际 UI 和生命周期，不作为生物识别硬件认证成功的证据。

2026-11-26 本次验证：Android 单元测试 65 项、iOS 模拟器测试 66 项全部通过；Android API 36 模拟器的上述 UI 测试通过 1 项，覆盖文件夹后台重新加锁、隐藏重命名弹窗、返回文稿库、应用后台重新加锁、拦截系统返回键以及应用与文件夹授权相互独立。

UI 测试也可在构建并安装应用和测试 APK 后直接执行：

```bash
adb shell am instrument -w -r \
  -e class com.kingscan.app.BiometricLockUiIntegrationTest \
  com.kingscan.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 真机验收

1. 在系统设置中录入 Face ID／Touch ID 或安卓指纹／面容。启用应用锁，取消和失败应保持关闭，成功后后台返回及重新启动应显示应用锁。
2. 给含文档的文件夹加锁；取消进入认证应保持不可见，成功后可读取、编辑和导出。关闭锁需重新认证。
3. 在重命名、批注、裁切或导出页面切到后台；返回后应遮住编辑器和弹窗。应用解锁后仍需解锁文件夹，再继续未保存的编辑。
4. Android API 28 以下或没有生物识别硬件的设备应禁用锁入口。取消认证后重试应产生新提示，后台中收到旧成功回调不能解锁。
