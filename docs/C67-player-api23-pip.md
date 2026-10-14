# C67: Android 6 播放页 PiP API 崩溃修复

## Recovery anchor

### Objective

修复 Android 6 / API 23 播放页在点击画面唤起控制层时直接调用 API 24 `Activity.isInPictureInPictureMode()` 导致 `NoSuchMethodError` 的问题。Android 7+ PiP 行为保持不变。

### Acceptance

- 播放相关源码不再直接调用平台 `isInPictureInPictureMode()`。
- API 23 通过兼容入口固定返回 `false`；API 24+ 委托原始平台方法。
- focused JVM 测试通过。
- leanback 源集编译通过。
- MuMu Android 6 覆盖安装后，同一 4K 点播内容播放并点击画面，进程存活且 crash buffer 无相关异常。

## Root cause

设备日志确认：

```text
java.lang.NoSuchMethodError:
No virtual method isInPictureInPictureMode()Z
in class Lcom/fongmi/android/tv/ui/activity/VideoActivity;
```

调用链为 `VideoActivity.onSingleTap()` -> `VideoActivity.showControl()`。`Activity.isInPictureInPictureMode()` 是 API 24 引入的方法，API 23 上不存在。

## Change

新增 `PictureInPictureCompat`：

- API 23：直接返回 `false`，不调用平台方法。
- API 24+：委托 `activity.isInPictureInPictureMode()`。

替换以下源码中的直接调用：

- `app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java`
- `app/src/main/java/com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java`
- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java`
- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`
- `app/src/mobile/java/com/fongmi/android/tv/utils/PiP.java`
- `app/src/leanback/java/com/fongmi/android/tv/utils/PiP.java`

新增源码契约测试 `Android6PictureInPictureCompatTest`。

## Verification

- `:app:testMobileArmeabi_v7aDebugUnitTest --tests com.fongmi.android.tv.utils.Android6PictureInPictureCompatTest`：通过。
- `:app:compileLeanbackArmeabi_v7aDebugJavaWithJavac`：通过。
- `:app:assembleMobileArmeabi_v7aRelease`：通过。
- APK SHA-256：`002AC4D632923C341636C8B0C1D2254EA51855A7B65594212AD419B41219E2FE`。
- MuMu Android 6.0.1 / API 23 使用 `install -r -d` 覆盖安装成功，未卸载、未清数据。
- 同一 4K 内容播放约 38 秒后连续点击画面：进程仍存活，焦点仍在 `VideoActivity`，crash buffer 无 `NoSuchMethodError` / `FATAL EXCEPTION` / `ClassNotFoundException`。

## Follow-up

- 播放画面亮度较低，但进度有效推进；本修复只解决点击崩溃，不声明完整渲染链路已验收。
- crash handler 将 activity-alias `HomeActivityCurrent` 当 Java class 解析导致 `ClassNotFoundException`，这是崩溃后的次级问题，未包含在本任务内。
- Android 7+ PiP 实机行为未重新验证，源码保持 API 24+ 原始委托路径。
