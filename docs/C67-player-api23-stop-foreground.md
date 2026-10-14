# C67 Android 6 stopForeground compatibility

## Objective

Prevent Android 6 / API 23 from crashing when playback stops because `PlaybackService.removeForeground()` calls the API 24-only `Service.stopForeground(int)` overload.

## Root cause

Device logs on MuMu Android 6.0.1 showed:

```text
java.lang.NoSuchMethodError:
No virtual method stopForeground(I)V
in class Landroid/app/Service;
```

The call path was:

```text
VideoActivity.onBackInvoked()
→ PlaybackActivity.stopPlayback()
→ PlaybackService.suspend()
→ PlaybackService.removeForeground()
→ stopForeground(STOP_FOREGROUND_REMOVE)
```

`Service.stopForeground(int)` and `STOP_FOREGROUND_REMOVE` require API 24. API 23 provides `stopForeground(boolean)`.

## Change

`PlaybackService.removeForeground()` now uses the API 23-compatible overload before Android 7:

```java
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) stopForeground(true);
else stopForeground(STOP_FOREGROUND_REMOVE);
```

This follows the existing compatibility pattern in `RemoteAgentService`.

## Verification

- `:app:testMobileArmeabi_v7aDebugUnitTest --tests com.fongmi.android.tv.service.Android6StopForegroundCompatTest` passed.
- `:app:assembleMobileArmeabi_v7aRelease` passed.
- Release APK SHA-256: `123990D19FE338072BAC17070F54B271B156EC42935CCC0A9A1C5C09F2589CCF`.
- Installed over the existing MuMu Android 6 app with `adb install -r -d`; package data was preserved.

Device playback-exit verification was blocked by a separate Android 6 crash in `HistoryAdapter`: `ProgressBar.setProgress(int, boolean)` is API 24-only. That issue is outside this task and is recorded separately.

## Rollback

Revert this commit to restore the previous API 24-only `stopForeground(STOP_FOREGROUND_REMOVE)` behavior.
