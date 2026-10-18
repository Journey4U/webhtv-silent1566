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
import java.util.ArrayList;
import java.util.List;

/**
 * The sherpa gate decides whether the realtime-subtitle engine may start, and where its native
 * library comes from.
 *
 * <p>Two properties matter: a verified module redirects the SDK at the module directory, and a
 * module that is absent or fails verification must degrade to the bundled library rather than
 * silently disabling the feature.
 */
public class RuntimeSherpaTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String LIB_NAME = "libsherpa-onnx-jni.so";
    private static final byte[] LIB_BYTES = "sherpa-native-payload".getBytes(StandardCharsets.UTF_8);
    private static final String LIB_SHA = RuntimeIntegrity.sha256(LIB_BYTES);

    private static final class MemoryStorage implements RuntimeStore.Storage {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value;
        }
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final RuntimeStore store = new RuntimeStore(storage);
    private final List<String> configured = new ArrayList<>();

    private String catalogJson() {
        return "{"
                + "\"schema\":1,\"modules\":[{"
                + "\"id\":\"sherpa\","
                + "\"packageName\":\"com.silent.runtime.sherpa\","
                + "\"compatibilityId\":\"sherpa-onnx-jni-v1\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.3.0\","
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":100,"
                + "\"sha256\":\"" + LIB_SHA + "\","
                + "\"url\":\"https://example.invalid/sherpa.apk\"}]"
                + "}]}";
    }

    private RuntimeCatalog.RuntimeModule module() {
        return GSON.fromJson(catalogJson(), RuntimeCatalog.class).find("sherpa");
    }

    private void recordInstalled() {
        RuntimeCatalog.RuntimeModule module = module();
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 1000L));
    }

    private String manifestJson() {
        return "{"
                + "\"module\":\"sherpa\","
                + "\"packageName\":\"com.silent.runtime.sherpa\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.3.0\","
                + "\"compatibilityId\":\"sherpa-onnx-jni-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + LIB_SHA + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + LIB_NAME + "\","
                + "\"files\":[{\"path\":\"" + LIB_NAME + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + LIB_SHA + "\"}]"
                + "}";
    }

    private File libDir(byte[] payload) throws IOException {
        File dir = folder.newFolder("libs");
        Files.write(new File(dir, LIB_NAME).toPath(), payload);
        return dir;
    }

    private RuntimeLoader loader(File libDir, String manifest) {
        return new RuntimeLoader(
                store,
                new RuntimePlatform.PackageInfo() {
                    @Override
                    public File nativeLibraryDir(String packageName) {
                        return libDir;
                    }

                    @Override
                    public int versionCode(String packageName) {
                        return 3;
                    }

                    @Override
                    public File apkPath(String packageName) {
                        return null;
                    }

                    @Override
                    public String apkDigest(String packageName) {
                        return null;
                    }
                },
                path -> {
                },
                name -> null,
                packageName -> manifest);
    }

    private RuntimeSherpa.Configurer configurer() {
        return configured::add;
    }

    private static RuntimeSherpa.BundledProbe bundled(boolean present) {
        return () -> present;
    }

    @Test
    public void pointsTheSdkAtAVerifiedModule() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isUsable());
        assertTrue(status.isFromModule());
        assertEquals("", status.getProblem());
        assertEquals(1, configured.size());
        assertTrue(configured.get(0), configured.get(0).endsWith("libs"));
        assertEquals(configured.get(0), status.getNativePath());
    }

    @Test
    public void moduleTakesPrecedenceOverTheBundledLibrary() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        // Even while the APK still bundles the library, an installed module wins so the user's
        // opt-in is honoured.
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.MODULE, status.getSource());
    }

    @Test
    public void fallsBackToTheBundledLibraryWhenNoModuleIsInstalled() throws IOException {
        // "Not installed" means the platform reports no native library directory. A real directory
        // with no registry record is a sideloaded module, which is usable.
        RuntimeLoader loader = loader(null, manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.BUNDLED, status.getSource());
        assertTrue(status.isUsable());
        assertFalse(status.isFromModule());
        assertTrue("bundled path must not be configured", configured.isEmpty());
    }

    /** A sideloaded module is usable even though the host never recorded an install. */
    @Test
    public void usesASideloadedModuleThatHasNoRegistryRecord() throws IOException {
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isFromModule());
        assertEquals(1, configured.size());
    }

    @Test
    public void reportsUnavailableWhenNeitherModuleNorBundledLibraryExists() throws IOException {
        RuntimeLoader loader = loader(null, manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(false));

        assertEquals(RuntimeSherpa.Source.UNAVAILABLE, status.getSource());
        assertFalse(status.isUsable());
        assertFalse(status.getProblem().isEmpty());
        assertTrue(configured.isEmpty());
    }

    @Test
    public void degradesToBundledWhenTheModuleFailsVerification() throws IOException {
        recordInstalled();
        // The file on disk no longer matches what the catalog authorized.
        RuntimeLoader loader = loader(libDir("tampered".getBytes(StandardCharsets.UTF_8)), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.BUNDLED, status.getSource());
        assertTrue("a rejected module must not be configured", configured.isEmpty());
    }

    @Test
    public void reportsTheVerificationProblemWhenNothingCanBeBundled() throws IOException {
        recordInstalled();
        // Equal-length payload so the digest check (not the cheaper size check) is the fault.
        byte[] tampered = new byte[LIB_BYTES.length];
        java.util.Arrays.fill(tampered, (byte) 'X');
        RuntimeLoader loader = loader(libDir(tampered), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(false));

        assertEquals(RuntimeSherpa.Source.UNAVAILABLE, status.getSource());
        assertTrue(status.getProblem(), status.getProblem().contains("declared file digest mismatch"));
        assertTrue(configured.isEmpty());
    }

    @Test
    public void rejectsAModuleBuiltForAnotherAbi() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "armeabi-v7a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.BUNDLED, status.getSource());
        assertTrue(configured.isEmpty());
    }

    @Test
    public void handlesAMissingLoader() {
        RuntimeSherpa.Status bundledStatus = RuntimeSherpa.prepare(null, "arm64-v8a", configurer(), bundled(true));
        assertEquals(RuntimeSherpa.Source.BUNDLED, bundledStatus.getSource());

        RuntimeSherpa.Status unavailable = RuntimeSherpa.prepare(null, "arm64-v8a", configurer(), bundled(false));
        assertEquals(RuntimeSherpa.Source.UNAVAILABLE, unavailable.getSource());
        assertTrue(unavailable.getProblem(), unavailable.getProblem().contains("未安装"));
    }

    @Test
    public void handlesAMissingBundledProbe() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        // A null probe means "cannot determine", which only matters when no module verifies.
        // A valid module is still used, since it is the one source we can actually confirm.
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), null);

        assertEquals(RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isUsable());
    }

    @Test
    public void treatsAnUnknownBundledProbeAsNotBundled() throws IOException {
        // No module present and no way to confirm a bundled library: fail closed with a clear
        // message rather than letting the SDK fail later with an opaque link error.
        RuntimeLoader loader = loader(null, manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), null);

        assertEquals(RuntimeSherpa.Source.UNAVAILABLE, status.getSource());
        assertFalse(status.isUsable());
        assertTrue(configured.isEmpty());
    }

    @Test
    public void toleratesAMissingConfigurer() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", null, bundled(true));

        assertEquals(RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isUsable());
    }

    @Test
    public void usesThePropertyTheSdkActuallyReads() {
        // Verified against the SDK bytecode: LibraryUtils.loadFromSherpaOnnxNativePath reads this
        // exact property name. A typo here would silently fall back to System.loadLibrary.
        assertEquals("sherpa_onnx.native.path", RuntimeSherpa.NATIVE_PATH_PROPERTY);
    }

    @Test
    public void rejectsAModuleWhoseLibraryWasRemoved() throws IOException {
        recordInstalled();
        File empty = folder.newFolder("empty-libs");
        RuntimeLoader loader = loader(empty, manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(false));

        assertEquals(RuntimeSherpa.Source.UNAVAILABLE, status.getSource());
        assertTrue(status.getProblem(), status.getProblem().contains("declared file is missing"));
    }

    @Test
    public void rejectsAModuleWithAnUnknownContract() throws IOException {
        RuntimeCatalog.RuntimeModule module = module();
        module.compatibilityId = "unknown-contract-v9";
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 1L));
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson());

        RuntimeSherpa.Status status = RuntimeSherpa.prepare(loader, "arm64-v8a", configurer(), bundled(true));

        assertEquals(RuntimeSherpa.Source.BUNDLED, status.getSource());
        assertTrue(configured.isEmpty());
    }
}
