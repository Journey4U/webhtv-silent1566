package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Payload verification for modules that ship more than native libraries.
 *
 * <p>The Python runtime is the case that motivated this: Chaquopy's payload is a tree of
 * {@code assets/chaquopy/**} files inside the module APK, not files under
 * {@code nativeLibraryDir}. Those payloads are read through an injected reader, so the verification
 * logic stays free of Android APIs.
 *
 * <p>The tests pin two things that matter for safety. A declared payload must be digest-checked, and
 * a manifest that declares one while no reader is available must be <em>rejected</em>: silently
 * accepting it would let an unverified payload reach the loader, which is the opposite of what
 * fail-closed verification is for.
 */
public class RuntimePayloadVerificationTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String LIB = "lib/arm64-v8a/libchaquopy_java.so";
    private static final String ASSET = "assets/chaquopy/build.json";

    private static final byte[] LIB_BYTES = "chaquopy-java-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ASSET_BYTES = "{\"python_version\":\"3.10\"}".getBytes(StandardCharsets.UTF_8);

    private static String sha(byte[] data) {
        return RuntimeIntegrity.sha256(data);
    }

    private String manifestJson(String assetSha, long assetSize) {
        return "{"
                + "\"module\":\"python\","
                + "\"packageName\":\"com.silent.runtime.python\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"chaquopy-python310-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + sha(LIB_BYTES) + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + LIB + "\","
                + "\"hostLibraries\":{},"
                + "\"files\":["
                + "  {\"path\":\"" + LIB + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + sha(LIB_BYTES) + "\"},"
                + "  {\"path\":\"" + ASSET + "\",\"size\":" + assetSize
                + ",\"sha256\":\"" + assetSha + "\"}"
                + "]}";
    }

    private RuntimeManifest manifest(String assetSha, long assetSize) {
        return GSON.fromJson(manifestJson(assetSha, assetSize), RuntimeManifest.class);
    }

    private RuntimeManifest manifest() {
        return manifest(sha(ASSET_BYTES), ASSET_BYTES.length);
    }

    /** Materializes the module the way the platform lays out nativeLibraryDir. */
    private File moduleRoot() throws IOException {
        File root = folder.newFolder("module");
        File file = new File(root, "libchaquopy_java.so");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), LIB_BYTES);
        return root;
    }

    /** A reader over an in-memory payload map, standing in for the module APK's assets. */
    private static RuntimeVerifier.PayloadReader reader(Map<String, byte[]> payloads) {
        return (packageName, path) -> {
            byte[] data = payloads.get(path);
            return data == null ? null : new ByteArrayInputStream(data);
        };
    }

    private static Map<String, byte[]> payloads() {
        Map<String, byte[]> map = new HashMap<>();
        map.put(ASSET, ASSET_BYTES);
        return map;
    }

    @Test
    public void acceptsAModuleWhosePayloadMatches() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                manifest(), moduleRoot(), "arm64-v8a", null, reader(payloads()));

        assertTrue(result.describe(), result.isOk());
    }

    /**
     * A payload whose bytes do not match the declared digest is rejected.
     *
     * <p>This is the check that makes the contract worth having: without it a module could ship any
     * payload and the host would hand it to the runtime.
     */
    @Test
    public void rejectsAPayloadWhoseDigestDoesNotMatch() throws IOException {
        Map<String, byte[]> tampered = new HashMap<>();
        tampered.put(ASSET, "tampered".getBytes(StandardCharsets.UTF_8));

        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                manifest(), moduleRoot(), "arm64-v8a", null, reader(tampered));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("payload digest mismatch"));
    }

    @Test
    public void rejectsAMissingPayload() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                manifest(), moduleRoot(), "arm64-v8a", null, reader(new HashMap<>()));

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("payload is missing"));
    }

    /**
     * A declared payload with no reader available is rejected rather than accepted unverified.
     *
     * <p>Accepting it would be a silent hole: the manifest would look checked while the payload was
     * never read. Rejecting is the fail-closed choice, and it also means a host build that cannot read
     * module assets cannot be talked into loading one.
     */
    @Test
    public void rejectsAPayloadWhenNoReaderIsAvailable() throws IOException {
        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                manifest(), moduleRoot(), "arm64-v8a", null, null);

        assertFalse(result.isOk());
        assertTrue(result.describe(),
                result.describe().contains("cannot be verified without a reader"));
    }

    /**
     * A reader that throws is reported as a problem rather than propagating.
     *
     * <p>Verification returns a result so callers can degrade to another engine; an exception here
     * would turn a recoverable rejection into a crash.
     */
    @Test
    public void reportsAReaderFailureAsAProblem() throws IOException {
        RuntimeVerifier.PayloadReader failing = (packageName, path) -> {
            throw new IOException("simulated read failure");
        };

        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                manifest(), moduleRoot(), "arm64-v8a", null, failing);

        assertFalse(result.isOk());
        assertTrue(result.describe(), result.describe().contains("payload is unreadable"));
    }

    /**
     * A module with only native libraries still verifies when no reader is supplied.
     *
     * <p>This is what keeps the change backward compatible: every existing module declares only
     * {@code lib/**}, so none of them needs a reader.
     */
    @Test
    public void acceptsALibraryOnlyModuleWithoutAReader() throws IOException {
        String json = "{"
                + "\"module\":\"ijk\","
                + "\"packageName\":\"com.silent.runtime.ijk\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"ijk-jni-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + sha(LIB_BYTES) + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + LIB + "\","
                + "\"hostLibraries\":{},"
                + "\"files\":[{\"path\":\"" + LIB + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + sha(LIB_BYTES) + "\"}]}";

        RuntimeVerifier.Result result = RuntimeVerifier.verifyInstalled(
                GSON.fromJson(json, RuntimeManifest.class), moduleRoot(), "arm64-v8a", null, null);

        assertTrue(result.describe(), result.isOk());
    }

    /** The path classifier decides which verifier runs, so it is pinned directly. */
    @Test
    public void classifiesPathsByPayloadRoot() {
        assertTrue(RuntimeManifest.isNativeLibraryPath("lib/arm64-v8a/libmpv.so"));
        assertTrue(RuntimeManifest.isNativeLibraryPath("libmpv.so"));
        assertFalse(RuntimeManifest.isNativeLibraryPath("assets/chaquopy/build.json"));
        assertFalse(RuntimeManifest.isNativeLibraryPath("assets/chaquopy/bootstrap-native/arm64-v8a/zlib.cpython-310.so"));
        assertFalse(RuntimeManifest.isNativeLibraryPath(""));
        assertFalse(RuntimeManifest.isNativeLibraryPath(null));
    }

    /** A payload path must not be able to escape the module through traversal. */
    @Test
    public void rejectsAPayloadPathWithTraversal() {
        String json = "{"
                + "\"module\":\"python\","
                + "\"packageName\":\"com.silent.runtime.python\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"chaquopy-python310-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + sha(LIB_BYTES) + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + LIB + "\","
                + "\"hostLibraries\":{},"
                + "\"files\":["
                + "  {\"path\":\"" + LIB + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + sha(LIB_BYTES) + "\"},"
                + "  {\"path\":\"assets/../../etc/passwd\",\"size\":10,\"sha256\":\"" + sha(LIB_BYTES) + "\"}"
                + "]}";

        RuntimeManifest manifest = GSON.fromJson(json, RuntimeManifest.class);
        assertFalse("a manifest with a traversal path must be invalid", manifest.isValid());
    }

    /** An absolute payload path is rejected for the same reason. */
    @Test
    public void rejectsAnAbsolutePayloadPath() {
        String json = "{"
                + "\"module\":\"python\","
                + "\"packageName\":\"com.silent.runtime.python\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"chaquopy-python310-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + sha(LIB_BYTES) + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + LIB + "\","
                + "\"hostLibraries\":{},"
                + "\"files\":["
                + "  {\"path\":\"" + LIB + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + sha(LIB_BYTES) + "\"},"
                + "  {\"path\":\"/etc/passwd\",\"size\":10,\"sha256\":\"" + sha(LIB_BYTES) + "\"}"
                + "]}";

        RuntimeManifest manifest = GSON.fromJson(json, RuntimeManifest.class);
        assertFalse("a manifest with an absolute path must be invalid", manifest.isValid());
    }

    /** The stream overload digests the same bytes as the file overload. */
    @Test
    public void streamDigestMatchesFileDigest() throws IOException {
        File file = folder.newFile("payload.bin");
        Files.write(file.toPath(), ASSET_BYTES);

        String fromFile = RuntimeIntegrity.sha256(file);
        String fromStream;
        try (InputStream in = new ByteArrayInputStream(ASSET_BYTES)) {
            fromStream = RuntimeIntegrity.sha256(in);
        }

        assertEquals(fromFile, fromStream);
        assertEquals(sha(ASSET_BYTES), fromStream);
    }
}
