package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * The load gate: nothing may reach {@code System.load} unless the installed module still matches
 * what the catalog authorized.
 */
public class RuntimeLoaderTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String LIB_NAME = "libsherpa-onnx-jni.so";
    private static final byte[] LIB_BYTES = "sherpa-native-payload".getBytes(StandardCharsets.UTF_8);
    private static final String LIB_SHA = RuntimeIntegrity.sha256(LIB_BYTES);

    private static final byte[] HOST_BYTES = "host-libcxx-payload".getBytes(StandardCharsets.UTF_8);
    private static final String HOST_SHA = RuntimeIntegrity.sha256(HOST_BYTES);

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
    private final List<String> loaded = new ArrayList<>();

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

    private String manifestJson(String library, String hostLibraries) {
        return "{"
                + "\"module\":\"sherpa\","
                + "\"packageName\":\"com.silent.runtime.sherpa\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.3.0\","
                + "\"compatibilityId\":\"sherpa-onnx-jni-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + LIB_SHA + "\","
                + "\"size\":" + LIB_BYTES.length + ","
                + "\"library\":\"" + library + "\","
                + "\"hostLibraries\":{" + hostLibraries + "},"
                + "\"files\":[{\"path\":\"" + LIB_NAME + "\",\"size\":" + LIB_BYTES.length
                + ",\"sha256\":\"" + LIB_SHA + "\"}]"
                + "}";
    }

    /** Builds a module directory holding the declared library. */
    private File libDir(byte[] payload) throws IOException {
        File dir = folder.newFolder("libs");
        Files.write(new File(dir, LIB_NAME).toPath(), payload);
        return dir;
    }

    private RuntimePlatform.PackageInfo packageInfo(File libDir) {
        return new RuntimePlatform.PackageInfo() {
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
        };
    }

    private RuntimeLoader loader(File libDir, String manifest, boolean loadSucceeds) {
        return new RuntimeLoader(
                store,
                packageInfo(libDir),
                path -> {
                    if (!loadSucceeds) throw new UnsatisfiedLinkError("cannot load " + path);
                    loaded.add(path);
                },
                name -> {
                    try {
                        File file = folder.newFile(name);
                        Files.write(file.toPath(), HOST_BYTES);
                        return file;
                    } catch (IOException e) {
                        return null;
                    }
                },
                packageName -> manifest);
    }

    @Test
    public void verifiesAndLoadsAHealthyModule() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertTrue(outcome.getProblem(), outcome.isOk());
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0), loaded.get(0).endsWith(LIB_NAME));
        assertEquals("sherpa", outcome.getEntry().id);
    }

    @Test
    public void refusesToLoadAModuleThatIsNotInstalled() throws IOException {
        // "Not installed" means the platform reports no native library directory. A real directory
        // with no registry record is a sideloaded module, which must load.
        RuntimeLoader loader = loader(null, manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("模块未安装"));
        assertTrue(loaded.isEmpty());
    }

    /**
     * A module installed by another route has no registry record but is present on the device.
     *
     * <p>Rejecting it would make the engine appear available (availability asks the platform) and
     * then fail at load time. The host must resolve the package from its own trusted table and
     * verify the module's own manifest instead.
     */
    @Test
    public void loadsASideloadedModuleThatHasNoRegistryRecord() throws IOException {
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertTrue(outcome.getProblem(), outcome.isOk());
        assertEquals("sherpa", outcome.getEntry().id);
        assertEquals("com.silent.runtime.sherpa", outcome.getEntry().packageName);
        assertEquals(1, loaded.size());
    }

    /** An id the host does not know must still be refused, not guessed at. */
    @Test
    public void refusesAnUnknownModuleIdWithoutARecord() throws IOException {
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("not-a-module", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("未知模块"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheModuleShipsNoManifest() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), null, true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("清单"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadATamperedLibrary() throws IOException {
        recordInstalled();
        // Same length as the authorized payload, so this exercises the digest check rather than
        // the cheaper size check: the file on disk no longer matches what the catalog authorized.
        byte[] tampered = new byte[LIB_BYTES.length];
        java.util.Arrays.fill(tampered, (byte) 'X');
        assertEquals(LIB_BYTES.length, tampered.length);
        RuntimeLoader loader = loader(libDir(tampered), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("declared file digest mismatch"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadALibraryWhoseSizeDoesNotMatch() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(
                libDir("sherpa-native-payload-plus".getBytes(StandardCharsets.UTF_8)),
                manifestJson(LIB_NAME, ""),
                true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("declared file size mismatch"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheHostLibraryDigestIsWrong() throws IOException {
        recordInstalled();
        RuntimeLoader loader = new RuntimeLoader(
                store,
                packageInfo(libDir(LIB_BYTES)),
                path -> loaded.add(path),
                name -> {
                    try {
                        File file = folder.newFile("wrong-" + name);
                        Files.write(file.toPath(), "a-different-libcxx".getBytes(StandardCharsets.UTF_8));
                        return file;
                    } catch (IOException e) {
                        return null;
                    }
                },
                packageName -> manifestJson(LIB_NAME, "\"libc++_shared.so\":\"" + HOST_SHA + "\""));

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("host library digest mismatch"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheDeclaredLibraryIsMissing() throws IOException {
        recordInstalled();
        File empty = folder.newFolder("empty-libs");
        RuntimeLoader loader = loader(empty, manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("declared file is missing"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheEntryLibraryIsAbsentFromDisk() throws IOException {
        recordInstalled();
        // The manifest declares an entry point that is not among the verified files.
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson("libother.so", ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("入口库不存在"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void reportsALoadFailureInsteadOfThrowing() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), false);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("原生库加载失败"));
    }

    @Test
    public void refusesToLoadWhenTheRecordedContractIsUnknownToTheHost() throws IOException {
        RuntimeCatalog.RuntimeModule module = module();
        module.compatibilityId = "unknown-contract-v9";
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 1L));

        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);
        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("不支持的模块契约"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheNativeLibraryDirectoryIsGone() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(null, manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "arm64-v8a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("原生库目录"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void verifyDoesNotLoadAnything() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.verify("sherpa", "arm64-v8a");

        assertTrue(outcome.getProblem(), outcome.isOk());
        assertNotNull(outcome.getEntryLibrary());
        assertTrue("verify must not load", loaded.isEmpty());
    }

    @Test
    public void rejectsAbiMismatch() throws IOException {
        recordInstalled();
        RuntimeLoader loader = loader(libDir(LIB_BYTES), manifestJson(LIB_NAME, ""), true);

        RuntimeLoader.Outcome outcome = loader.load("sherpa", "armeabi-v7a");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getProblem(), outcome.getProblem().contains("abi mismatch"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void fileTreeSourceReadsTheManifestAsset() throws IOException {
        File root = folder.newFolder("module-root");
        File assets = new File(root, "assets");
        assertTrue(assets.mkdirs());
        Files.write(new File(assets, RuntimeLoader.manifestAssetName()).toPath(),
                manifestJson(LIB_NAME, "").getBytes(StandardCharsets.UTF_8));

        String raw = RuntimeLoader.fileTreeSource(root).read("com.silent.runtime.sherpa");

        assertNotNull(raw);
        assertTrue(raw.contains("\"sherpa\""));
    }

    @Test
    public void fileTreeSourceReturnsNullWhenTheManifestIsAbsent() throws IOException {
        File root = folder.newFolder("module-root-empty");

        assertNull(RuntimeLoader.fileTreeSource(root).read("com.silent.runtime.sherpa"));
    }
}
