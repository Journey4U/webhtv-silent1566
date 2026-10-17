package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Manifest contract tests driven by the real upstream {@code runtime.json} shape.
 */
public class RuntimeManifestTest {

    private static final Gson GSON = new Gson();

    private static final String LIBMPV_SHA =
            "ede6123bee76d965791548af01123316b29c1b18c6419ac392e2088df867987e";
    private static final String LIBPLAYER_SHA =
            "aaaecbe2a0a8a9f26758c7fe7fd469ac91ac44a66e4095ac599be90ff562ec35";
    private static final String LIBCXX_SHA =
            "b5d97c38a728afe28d341cda3369f512d35eaf9c680c1b0563cd6a63e395230f";

    /** Trimmed excerpt of the upstream runtime-mpv-1.0.2 manifest, field-for-field. */
    private static final String UPSTREAM_MANIFEST = "{"
            + "\"module\":\"mpv\","
            + "\"packageName\":\"com.fongmi.runtime.mpv\","
            + "\"versionCode\":3,"
            + "\"versionName\":\"1.0.2\","
            + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
            + "\"abi\":\"arm64-v8a\","
            + "\"sha256\":\"" + LIBPLAYER_SHA + "\","
            + "\"size\":56584,"
            + "\"library\":\"lib/arm64-v8a/libplayer.so\","
            + "\"hostLibraries\":{\"libc++_shared.so\":\"" + LIBCXX_SHA + "\"},"
            + "\"files\":["
            + "  {\"path\":\"lib/arm64-v8a/libmpv.so\",\"size\":17708336,\"sha256\":\"" + LIBMPV_SHA + "\"},"
            + "  {\"path\":\"lib/arm64-v8a/libplayer.so\",\"size\":56584,\"sha256\":\"" + LIBPLAYER_SHA + "\"}"
            + "]}";

    @Test
    public void parsesUpstreamManifestShape() {
        RuntimeManifest manifest = GSON.fromJson(UPSTREAM_MANIFEST, RuntimeManifest.class);

        assertNotNull(manifest);
        assertTrue(manifest.isValid());
        assertEquals("mpv", manifest.module);
        assertEquals("com.fongmi.runtime.mpv", manifest.packageName);
        assertEquals("mpv-jni-ffmpeg-shared-v1", manifest.compatibilityId);
        assertEquals("arm64-v8a", manifest.abi);
        assertEquals("lib/arm64-v8a/libplayer.so", manifest.library);
        assertEquals(2, manifest.files().size());
    }

    @Test
    public void declaresHostLibraryWithoutBundlingIt() {
        RuntimeManifest manifest = GSON.fromJson(UPSTREAM_MANIFEST, RuntimeManifest.class);

        assertEquals(1, manifest.hostLibraries().size());
        assertEquals(LIBCXX_SHA, manifest.hostLibraries().get("libc++_shared.so"));
        // The module must not ship its own copy of a host-provided library.
        assertNull(manifest.findFile("libc++_shared.so"));
        assertNull(manifest.findFile("lib/arm64-v8a/libc++_shared.so"));
    }

    @Test
    public void findsDeclaredFiles() {
        RuntimeManifest manifest = GSON.fromJson(UPSTREAM_MANIFEST, RuntimeManifest.class);

        RuntimeManifest.RuntimeFile mpv = manifest.findFile("lib/arm64-v8a/libmpv.so");
        assertNotNull(mpv);
        assertEquals(17708336L, mpv.size);
        assertEquals(LIBMPV_SHA, mpv.sha256);

        assertNull(manifest.findFile("lib/arm64-v8a/absent.so"));
        assertNull(manifest.findFile(""));
        assertNull(manifest.findFile(null));
    }

    @Test
    public void rejectsUnsupportedAbi() {
        RuntimeManifest manifest = GSON.fromJson(
                UPSTREAM_MANIFEST.replace("\"arm64-v8a\"", "\"x86_64\""), RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void rejectsAbsoluteLibraryPath() {
        RuntimeManifest manifest = GSON.fromJson(
                UPSTREAM_MANIFEST.replace("lib/arm64-v8a/libplayer.so\"", "/data/local/tmp/libplayer.so\""),
                RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void rejectsPathTraversal() {
        RuntimeManifest manifest = GSON.fromJson(
                UPSTREAM_MANIFEST.replace("lib/arm64-v8a/libmpv.so", "../../libmpv.so"), RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void rejectsHostLibraryWithMalformedDigest() {
        RuntimeManifest manifest = GSON.fromJson(
                UPSTREAM_MANIFEST.replace(LIBCXX_SHA, "not-a-digest"), RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void rejectsFileWithoutDigest() {
        RuntimeManifest manifest = GSON.fromJson(
                UPSTREAM_MANIFEST.replace("\"sha256\":\"" + LIBMPV_SHA + "\"", "\"sha256\":\"\""),
                RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void rejectsEmptyFileList() {
        RuntimeManifest manifest = GSON.fromJson("{"
                + "\"module\":\"mpv\",\"packageName\":\"p\",\"versionCode\":3,"
                + "\"compatibilityId\":\"c\",\"abi\":\"arm64-v8a\","
                + "\"library\":\"lib/arm64-v8a/libplayer.so\",\"files\":[]}", RuntimeManifest.class);

        assertFalse(manifest.isValid());
    }

    @Test
    public void missingHostLibrariesIsAllowed() {
        RuntimeManifest manifest = GSON.fromJson("{"
                + "\"module\":\"sherpa\",\"packageName\":\"p\",\"versionCode\":1,"
                + "\"compatibilityId\":\"c\",\"abi\":\"arm64-v8a\","
                + "\"library\":\"lib/arm64-v8a/libsherpa-onnx-jni.so\","
                + "\"files\":[{\"path\":\"lib/arm64-v8a/libsherpa-onnx-jni.so\",\"size\":10,"
                + "\"sha256\":\"" + LIBMPV_SHA + "\"}]}", RuntimeManifest.class);

        assertTrue(manifest.isValid());
        assertTrue(manifest.hostLibraries().isEmpty());
    }
}
