package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Fail-closed verification tests for the module contract.
 *
 * <p>The central case is {@code hostLibraries}: a module must never bundle its own copy of a
 * host-provided library, and the host copy must match the declared digest. Two copies of
 * {@code libc++_shared.so} collide on the same soname and break dynamic linking for every native
 * consumer in the process.
 */
public class RuntimeVerifierTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String LIBMPV = "lib/arm64-v8a/libmpv.so";
    private static final String LIBPLAYER = "lib/arm64-v8a/libplayer.so";

    private static final byte[] LIBMPV_BYTES = "mpv-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIBPLAYER_BYTES = "player-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIBCXX_BYTES = "host-libcxx-payload".getBytes(StandardCharsets.UTF_8);

    private static String sha(byte[] data) {
        return RuntimeIntegrity.sha256(data);
    }

    private String catalogJson() {
        return "{"
                + "\"schema\":1,\"modules\":[{"
                + "\"id\":\"mpv\","
                + "\"packageName\":\"com.fongmi.runtime.mpv\","
                + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.0.2\","
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":100,"
                + "\"sha256\":\"" + sha(LIBPLAYER_BYTES) + "\","
                + "\"url\":\"https://example.invalid/runtime-mpv.apk\"}]"
                + "}]}";
    }

    private String manifestJson() {
        return "{"
                + "\"module\":\"mpv\","
                + "\"packageName\":\"com.fongmi.runtime.mpv\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.0.2\","
                + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + sha(LIBPLAYER_BYTES) + "\","
                + "\"size\":" + LIBPLAYER_BYTES.length + ","
                + "\"library\":\"" + LIBPLAYER + "\","
                + "\"hostLibraries\":{\"libc++_shared.so\":\"" + sha(LIBCXX_BYTES) + "\"},"
                + "\"files\":["
                + "  {\"path\":\"" + LIBMPV + "\",\"size\":" + LIBMPV_BYTES.length + ",\"sha256\":\"" + sha(LIBMPV_BYTES) + "\"},"
                + "  {\"path\":\"" + LIBPLAYER + "\",\"size\":" + LIBPLAYER_BYTES.length + ",\"sha256\":\"" + sha(LIBPLAYER_BYTES) + "\"}"
                + "]}";
    }

    private RuntimeCatalog.RuntimeModule module() {
        return GSON.fromJson(catalogJson(), RuntimeCatalog.class).find("mpv");
    }

    private RuntimeManifest manifest() {
        return GSON.fromJson(manifestJson(), RuntimeManifest.class);
    }

    /**
     * Materializes the module directory the way the platform actually lays it out.
     *
     * <p>{@code nativeLibraryDir} is a flat directory (verified on device as
     * {@code .../lib/arm64/}), while manifest paths are written relative to the APK root
     * ({@code lib/arm64-v8a/x.so}). The verifier strips that prefix, so the files live at the
     * directory root here.
     */
    private File moduleRoot() throws IOException {
        File root = folder.newFolder("module");
        write(new File(root, "libmpv.so"), LIBMPV_BYTES);
        write(new File(root, "libplayer.so"), LIBPLAYER_BYTES);
        return root;
    }

    private File hostLibcxx() throws IOException {
        File file = folder.newFile("libc++_shared.so");
        write(file, LIBCXX_BYTES);
        return file;
    }

    private static void write(File file, byte[] data) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), data);
    }

    private static RuntimeVerifier.HostLibraries host(File file) {
        return name -> "libc++_shared.so".equals(name) ? file : null;
    }

    @Test
    public void acceptsModuleThatMatchesCatalogAndHost() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), moduleRoot(), "arm64-v8a", host(hostLibcxx()));

        assertTrue(result.describe(), result.isOk());
        assertEquals(LIBPLAYER, result.getEntryLibrary());
        assertTrue(result.getProblems().isEmpty());
    }

    @Test
    public void rejectsTamperedModuleFile() throws IOException {
        File root = moduleRoot();
        // Flip one byte in a declared library.
        write(new File(root, "libmpv.so"), "mpv-payloaX".getBytes(StandardCharsets.UTF_8));

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), root, "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("declared file digest mismatch"));
    }

    @Test
    public void rejectsMissingModuleFile() throws IOException {
        File root = moduleRoot();
        assertTrue(new File(root, "libmpv.so").delete());

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), root, "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("declared file is missing"));
    }

    @Test
    public void rejectsSizeMismatch() throws IOException {
        File root = moduleRoot();
        write(new File(root, "libmpv.so"), "mpv-payload-plus".getBytes(StandardCharsets.UTF_8));

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), root, "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("declared file size mismatch"));
    }

    @Test
    public void rejectsIncompatibleHostLibraryDigest() throws IOException {
        File wrong = folder.newFile("libc++_shared.so");
        write(wrong, "a-different-libcxx".getBytes(StandardCharsets.UTF_8));

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), moduleRoot(), "arm64-v8a", host(wrong));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("host library digest mismatch"));
    }

    @Test
    public void rejectsMissingHostLibrary() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), moduleRoot(), "arm64-v8a", name -> null);

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("host library is missing"));
    }

    @Test
    public void rejectsUnavailableHostLibraryProvider() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), moduleRoot(), "arm64-v8a", null);

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("host library is required but unavailable"));
    }

    @Test
    public void rejectsModuleThatBundlesItsOwnHostLibrary() throws IOException {
        String bundled = manifestJson().replace(
                "{\"path\":\"" + LIBMPV + "\"",
                "{\"path\":\"lib/arm64-v8a/libc++_shared.so\"");
        RuntimeManifest tampered = GSON.fromJson(bundled, RuntimeManifest.class);

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), tampered, moduleRoot(), "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("must not bundle host library"));
    }

    @Test
    public void rejectsVersionMismatchBetweenCatalogAndManifest() throws IOException {
        RuntimeManifest older = GSON.fromJson(
                manifestJson().replace("\"versionCode\":3", "\"versionCode\":2"), RuntimeManifest.class);

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), older, moduleRoot(), "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("version mismatch"));
    }

    @Test
    public void rejectsCompatibilityContractMismatch() throws IOException {
        RuntimeManifest other = GSON.fromJson(
                manifestJson().replace("mpv-jni-ffmpeg-shared-v1", "mpv-jni-some-other-v2"),
                RuntimeManifest.class);

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), other, moduleRoot(), "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("compatibility contract mismatch"));
    }

    @Test
    public void rejectsCompatibilityContractUnknownToHost() throws IOException {
        String json = manifestJson().replace("mpv-jni-ffmpeg-shared-v1", "unknown-contract-v9");
        RuntimeManifest unknown = GSON.fromJson(json, RuntimeManifest.class);
        RuntimeCatalog.RuntimeModule catalogModule = GSON.fromJson(
                catalogJson().replace("mpv-jni-ffmpeg-shared-v1", "unknown-contract-v9"),
                RuntimeCatalog.class).find("mpv");

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                catalogModule, unknown, moduleRoot(), "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("unsupported compatibility contract"));
    }

    @Test
    public void rejectsAbiMismatch() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), moduleRoot(), "armeabi-v7a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("abi mismatch"));
    }

    @Test
    public void rejectsMissingModuleDirectory() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), new File(folder.getRoot(), "absent"), "arm64-v8a", host(hostLibcxx()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("module directory is unavailable"));
    }

    @Test
    public void rejectsNullInputsInsteadOfThrowing() {
        assertFalse(RuntimeVerifier.verify(null, manifest(), null, "arm64-v8a", null).isOk());
        assertFalse(RuntimeVerifier.verify(module(), null, null, "arm64-v8a", null).isOk());
    }

    @Test
    public void reportsEveryProblemAtOnce() throws IOException {
        File root = moduleRoot();
        write(new File(root, "libmpv.so"), "wrong".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(root, "libplayer.so").delete());

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module(), manifest(), root, "arm64-v8a", name -> null);

        assertFalse(result.isOk());
        // One pass must surface every fault: the oversized/undersized file, the deleted file and
        // the unavailable host library. Reporting only the first would hide the rest.
        assertEquals(result.describe(), 3, result.getProblems().size());
        assertTrue(result.describe(), result.describe().contains("declared file size mismatch: " + LIBMPV));
        assertTrue(result.describe(), result.describe().contains("declared file is missing: " + LIBPLAYER));
        assertTrue(result.describe(), result.describe().contains("host library is missing: libc++_shared.so"));
    }
}
