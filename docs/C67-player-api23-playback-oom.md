# C67 Android 6 playback OOM recovery anchor

## Objective

Prevent repeated Android 6 playback failures from exhausting the 256 MB Java heap in the local MPV HLS proxy path while preserving normal HLS playlist handling.

## Evidence

- Android 6.0.1 device: `127.0.0.1:7555`.
- Latest crash: `.wh_diag/android6-latest-crash.log`, PID 9704, `java.lang.OutOfMemoryError`.
- ART repeatedly clamped the target GC heap to 256 MB and could not free objects.
- The pre-crash proxy thread reaches `ResponseBody.string()` from `MpvHlsProxy.serve(...)` under `NanoHTTPD`.
- Memory samples in `.wh_diag/android6-oom-meminfo-samples.txt` show Java heap rising from about 74 MB to 237 MB while Activity/View counts remain stable.
- The playback URL used the local MPV HLS proxy.

## Root cause hypothesis

HLS playlist responses are read entirely into a Java string and then copied/transformed by ad filtering, URL rewriting, and segment classification. On Android 6, repeated or large playlist responses can consume the 256 MB Java heap.

## Approved narrow design

- Add an 8 MiB hard byte limit for HLS playlist bodies in `MpvHlsProxy`.
- Reject a response before reading if `Content-Length` already exceeds the limit.
- Stream-read at most the limit in 8 KiB chunks; reject immediately if the body exceeds it.
- Decode with strict UTF-8.
- Apply the same limit to top-level and nested HLS playlists.
- Do not alter DASH, decoder selection, native libraries, heap configuration, or unrelated proxy code.
- The 8 MiB ceiling is intentionally generous for normal HLS playlists while capping a single response far below the Android 6 256 MB Java heap.

Known tradeoff: an unusually huge HLS playlist may become explicitly unplayable instead of crashing the process.

## Acceptance criteria

1. Focused unit tests cover exact-limit, over-limit, known oversized `Content-Length`, malformed UTF-8, and both HLS call sites.
2. Targeted unit test and APK build pass.
3. The APK is installed over the existing Android 6 installation (`adb install -r -d`), without uninstalling.
4. The previous playback scenario no longer reaches a fatal Java OOM; if the source is oversized, it fails explicitly.

## Rollback

Revert the implementation commit and redeploy the previously verified APK.

## Implementation record

- `MpvHlsProxy.MAX_PLAYLIST_BYTES` is package-private for focused same-package tests.
- `MpvHlsProxy.readPlaylistBody(ResponseBody)` checks `Content-Length`, performs bounded 8 KiB reads, and decodes strict UTF-8.
- Both HLS call sites now use `readPlaylistBody`; the DASH call site remains unchanged.
- Added `MpvHlsProxyPlaylistLimitTest` with exact-limit, one-byte-over-limit, oversized declared `Content-Length`, and malformed UTF-8 coverage.
- Verification: `:app:testMobileArmeabi_v7aDebugUnitTest --tests androidx.media3.mpvplayer.MpvHlsProxyPlaylistLimitTest` passed on 2026-10-14.
- Release build: `:app:assembleMobileArmeabi_v7aRelease` produced `app/build/outputs/apk/mobileArmeabi_v7a/release/mobile-armeabi_v7a.apk` (139,343,837 bytes, minSdk 23, versionCode 560, versionName 5.6.0); `apksigner verify` passed.
- Android 6.0.1 device deployment used `adb install -r -d` on `127.0.0.1:7555` without uninstalling; installation and app startup succeeded.
- Playback verification: the prior recently-watched HLS item entered `VideoActivity`, played through the local HLS proxy on IJK for over two minutes, produced a non-black video frame, and retained PID 10226.
- Memory and stability: during playback and after the additional observation, Java Heap was about 179 MB with no `FATAL EXCEPTION`, `OutOfMemoryError`, or ART 256 MB clamp; the process did not restart.
- Device evidence is archived outside the repository at `F:/Workspace/webtv2/webhtv-archives/C67-playback-oom/` (success log, meminfo, UI, and screenshots).

## Next action

Close this guard session, commit the bounded HLS playlist fix, and create its recovery tag.
