# C67 Android 6 minimum SDK implementation

## Objective
- Determine whether WebHTV can set application `minSdk` to Android 6 / API 23 while preserving the current Gradle 9.5.1 / AGP 9.2.1 build chain and existing Python/Chaquopy capability.
- Produce and verify an installable Android 6 APK.

## Evidence and decision
- AndroidX WebKit `1.16.0` declares `minSdk 24`; current source only requires APIs present in `1.14.0`, so the catalog is downgraded to `1.14.0` (`minSdk 21`).
- Chaquopy 17 supports AGP 9.x but officially requires API 24; Chaquopy 15 supports API 21 but only AGP 7.0–8.5 and fails on Gradle 9.5.1 because `org.gradle.util.VersionNumber` is unavailable.
- To avoid a whole-project Gradle/AGP downgrade, keep the Chaquopy Gradle plugin at 17.0.0 and keep the `chaquo` Android library module at `minSdk 24`, while the application manifest uses `minSdk 23`.
- Android library `minSdk` does not force the application's final `minSdk`; the app manifest remains authoritative.
- Release signing explicitly enables both v1 and v2 signatures because Android 5/6 requires v1 JAR signing and Android 7+ uses v2.

## Implementation
- `gradle/libs.versions.toml`
  - `minSdk` 24 → 23.
  - `python` plugin 17.0.0.
  - `webkit` 1.16.0 → 1.14.0.
- `app/build.gradle`
  - Enable `enableV1Signing` and `enableV2Signing` in release signing.
- `chaquo/build.gradle`
  - Keep the library module's `minSdk` explicitly at 24 to satisfy Chaquopy 17's plugin contract.
- Preserve the current `ANDROID_PLATFORM=android-26` native configuration until runtime evidence proves it must be lowered.

## Verification plan
1. Build the smallest representative release variant with existing signing.
2. Inspect the produced APK to confirm:
   - final `minSdk` is 23;
   - v1 and v2 signatures are present;
   - the APK still contains the Chaquopy runtime and Python payload.
3. Install over an existing package on the API 23 emulator `emulator-5570` using `adb install -r -d`.
4. Launch the app and check logs for startup, manifest resolution, and Python runtime behavior.
5. Run one targeted lint or API compatibility check after the build.

## Status
- Completed: Option B is implemented and the final API-23 source build passed focused JVM tests, APK minSdk/signing checks, and MuMu Android 6 overwrite-install/start/config/search runtime verification.

## Runtime verification: Android 6.0.1
- MuMu Android 6.0.1 / API 23 (`127.0.0.1:7555`) installed the current ARM APK successfully and booted the main UI.
- Adding a VOD config reproducibly crashed through:
  `ConfigDialog.onPositive -> SettingFragment.load -> VodConfig.load -> VodConfig.startNewLoad -> VodConfig.clear -> BaseLoader.get()`.
- The main failure is `java.lang.UnsatisfiedLinkError: com.fongmi.android.tv.api.loader.BaseLoader$Loader`.
- The earlier native logs identify the first failing load:
  - `libcrypto_chaquopy.so`, `libssl_chaquopy.so`, and `libsqlite3_chaquopy.so` load through MuMu native bridge.
  - `libpython3.10.so` fails with `dlopen failed: ".../lib/arm/libpython3.10.so" has unexpected e_machine: 40`.
  - `BaseLoader.<init>` then fails at `BaseLoader.java:35`, making `BaseLoader$Loader` class initialization fail.
- Static ELF inspection of the APK confirms:
  - `lib/armeabi-v7a/libpython3.10.so` and `libchaquopy_java.so` are ELF32 ARM;
  - their Android ident note records NDK r27d and API 24 (`13750724`);
  - this matches Chaquopy 17's official API 24 runtime boundary, not a wrong ABI folder in packaging.
- The later `HomeActivityCurrent ClassNotFoundException` comes from the crash handler treating the launcher activity-alias as a Java class. It is a secondary restart/recovery issue, not the cause of the VOD-config crash.

## Current conclusion
- Build/install compatibility for API 23 is achieved: minSdk 23, v1+v2 signatures, successful MuMu Android 6 overwrite-install and startup.
- With Option B, Python/Chaquopy-dependent features are intentionally unavailable on API 23 because the bundled Chaquopy 17 native runtime targets API 24.
- The tested non-Python VOD configuration, home list, and search paths now run on Android 6. Full-feature compatibility is not claimed because lint still reports pre-existing API-level debt outside this task's scope.

## Build-local compatibility state
- The abandoned x86 flavor and empty-CMake skipExoNative experiment were removed from the build files.
- Build-local minSdk-23 copies for hook/thunder/tvbus AARs and the Chaquopy manifest override are part of the implementation, but their runtime behavior is not yet fully verified.

## User decision: disable Python on API 23
- The user selected Option B: keep the APK floor at API 23, disable Python functionality on Android 6, and preserve Chaquopy 17 behavior on Android 7+.
- `BaseLoader` now determines Python support from `Build.VERSION.SDK_INT >= Build.VERSION_CODES.N`.
- On API 23, `PyLoader` is not constructed. Loader cleanup, recent-plugin bookkeeping, proxy calls, and active-key collection all tolerate the absent Python loader.
- A Python site requested on API 23 returns `SpiderNull` and records a `Python unavailable on API 23` diagnostic instead of crashing.
- `SubtitleScriptRuntimeFactory` also defers Python subtitle runtime construction. On API 23, Python subtitle subscriptions report an unsupported runtime instead of loading the Chaquopy native library.
- The abandoned x86 flavor and `skipExoNative` empty-CMake experiment were removed from the build files.

## Android 6 degradation verification
- Focused JVM tests were added for loader, subtitle, HTML, and search-filter contracts:
  - `com.fongmi.android.tv.api.loader.Android6PythonDisableTest`
  - `com.fongmi.android.tv.subtitle.source.Android6PythonSubtitleDisableTest`
  - `com.fongmi.android.tv.utils.Android6HtmlCompatTest`
  - `SearchResultFilterTest` includes the API-23 Han-detection contract
- The loader test failed before implementation because `PyLoader` was still constructed eagerly. After implementation, the focused contracts passed.
- Additional runtime crashes found on API 23 were fixed in-task:
  - `Html.fromHtml(String, int)` is API 24+; `Vod`, `Util`, and karaoke HTML conversion now use `TextCompat.fromHtml`, which falls back to `Html.fromHtml(String)` on API 23.
  - `String.codePoints()` and `Character.UnicodeScript` are API 24+; search Han detection now uses `codePointAt`, `charCount`, and `Character.UnicodeBlock`.
- Final focused test command passed:
  - `:app:testMobileArmeabi_v7aDebugUnitTest`
  - selected: `SearchResultFilterTest`, `Android6HtmlCompatTest`
- Final release build passed:
  - `:app:assembleMobileArmeabi_v7aRelease -PfastRelease=true`
- Final APK checks:
  - SHA-256: `C2EAE5FD406C10A107E179605921B1914115DFF8A30F8393AA7BA22B992EAF75`
  - `minSdkVersion:'23'`, `targetSdkVersion:'28'`
  - v1 and v2 signature verification: `true`
- MuMu Android 6.0.1 overwrite-install passed with `adb install -r -d`; original first-install time was preserved and `lastUpdateTime` became `2026-10-14 01:00:50`.
- Runtime verification passed for the original failure lane and related UI path:
  - app starts to the home UI;
  - a configured VOD source survives restart;
  - home recommendations render;
  - searching `qingyunian` returns `庆余年` suggestions/results;
  - no `BaseLoader`, `libpython`, `Html.fromHtml`, `codePoints`, or `Character.UnicodeScript` crash appears.
- Full release lint ran once and failed on 295 pre-existing errors/1505 warnings. It reports 96 `NewApi` errors and no `ClassVerificationFailure` errors. These are broad existing API-level debts outside the Option B lane and are not silently expanded in this task.

## Remaining risks
- Python VOD sites, Python proxies, and Python subtitle sources remain unavailable on API 23 by design.
- The 295 lint errors and 1505 warnings include 96 `NewApi` findings in many untested feature lanes; full Android 6 feature compatibility is not established.
- The crash handler still treats launcher activity-alias `HomeActivityCurrent` as a Java class when resolving restart behavior. It only appears after a real crash and remains a separate issue.
