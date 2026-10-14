# C67 Android 6 history progress compatibility

## Objective

Prevent Android 6 / API 23 from crashing while the mobile and leanback history lists bind an existing playback history item.

## Root cause

MuMu Android 6.0.1 logs showed:

```text
java.lang.NoSuchMethodError:
No virtual method setProgress(IZ)V
in class Landroid/widget/ProgressBar;
```

The stack ended at `HistoryAdapter.onBindViewHolder`. `ProgressBar.setProgress(int, boolean)` requires API 24; API 23 only provides `setProgress(int)`.

## Change

Both mobile and leanback `HistoryAdapter` implementations now branch before Android 7:

```java
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) setProgress(value);
else setProgress(value, animate);
```

Android 7+ keeps the existing animated overload.

## Verification

- `:app:testMobileArmeabi_v7aDebugUnitTest --tests com.fongmi.android.tv.ui.adapter.Android6HistoryProgressCompatTest` passed.
- `:app:assembleMobileArmeabi_v7aRelease` passed.
- Release APK SHA-256: `AA29E4503C721D327340732FCE02C37D2A01DF45B409145263BB4DF234C01CA9`.
- Installed over the existing MuMu Android 6 package with `adb install -r -d`; data was preserved.
- On Android 6, cold start and opening `HistoryActivity` no longer crash. The process remained alive and the history list rendered entries.
- A history item was opened into `VideoActivity`; pressing back no longer produced a `stopForeground` NoSuchMethodError, confirming the previous fix on top of this build.

A separate issue remains: after approximately 30 seconds of playback, the app hit the 256MB Java heap limit and threw an OutOfMemoryError. That playback memory issue is not part of this adapter compatibility change.

## Rollback

Revert this commit to restore the API 24-only animated progress behavior.
