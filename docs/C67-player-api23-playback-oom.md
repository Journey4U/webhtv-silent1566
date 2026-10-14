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

- Add a hard byte limit for HLS playlist bodies in `MpvHlsProxy`.
- Reject a response before reading if `Content-Length` already exceeds the limit.
- Stream-read at most the limit; reject immediately if the body exceeds it.
- Decode with strict UTF-8.
- Apply the same limit to top-level and nested HLS playlists.
- Do not alter DASH, decoder selection, native libraries, heap configuration, or unrelated proxy code.

Known tradeoff: an unusually huge HLS playlist may become explicitly unplayable instead of crashing the process.

## Acceptance criteria

1. Focused unit tests cover exact-limit, over-limit, known oversized `Content-Length`, malformed UTF-8, and both HLS call sites.
2. Targeted unit test and APK build pass.
3. The APK is installed over the existing Android 6 installation (`adb install -r -d`), without uninstalling.
4. The previous playback scenario no longer reaches a fatal Java OOM; if the source is oversized, it fails explicitly.

## Rollback

Revert the implementation commit and redeploy the previously verified APK.

## Next action

Restart this task guard with the implementation scope and patch `MpvHlsProxy`.
