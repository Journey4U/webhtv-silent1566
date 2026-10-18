package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.k2fsa.sherpa.onnx.LibraryUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * End-to-end proof that the sherpa SDK loads its native library from the installed module.
 *
 * <p>This is the one claim that neither unit tests nor path assertions can settle: that setting
 * {@code sherpa_onnx.native.path} actually redirects the SDK's own loader. The SDK reads the
 * property from {@code LibraryUtils.loadFromSherpaOnnxNativePath()} before falling back to
 * {@code System.loadLibrary}, and caches the result for the process lifetime.
 *
 * <p>The evidence is {@code /proc/self/maps}: after the load, the mapped library path must sit
 * under the module's package directory rather than the host's.
 *
 * <p><b>Run this class alone.</b> The SDK loads once per process and caches the outcome, so a
 * sibling test that touches a sherpa class first would make the result depend on ordering:
 * <pre>
 * adb shell am instrument -w \
 *   -e class com.fongmi.android.tv.runtime.RuntimeSherpaLoadDeviceTest \
 *   com.silent.android.webhtv.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeSherpaLoadDeviceTest {

    private static final String MODULE_ID = "sherpa";
    private static final String MODULE_PACKAGE = "com.silent.runtime.sherpa";
    private static final String HOST_PACKAGE = "com.silent.android.webhtv";
    private static final String ABI = "arm64-v8a";
    private static final String LIBRARY = "libsherpa-onnx-jni.so";

    /** In-memory persistence that honours the read/write contract. */
    private static final class MemoryStorage implements RuntimeStore.Storage {
        private String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }
    }

    private static RuntimeStore storeWith(RuntimeCatalog.RuntimeModule module, RuntimeCatalog.RuntimeArtifact artifact) {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    private static RuntimeCatalog.RuntimeModule catalogModule(RuntimeManifest manifest) {
        RuntimeCatalog.RuntimeModule module = new RuntimeCatalog.RuntimeModule();
        module.id = manifest.module;
        module.packageName = manifest.packageName;
        module.compatibilityId = manifest.compatibilityId;
        module.versionCode = manifest.versionCode;
        module.versionName = manifest.versionName;
        module.artifacts = List.of(artifact(manifest));
        return module;
    }

    private static RuntimeCatalog.RuntimeArtifact artifact(RuntimeManifest manifest) {
        RuntimeCatalog.RuntimeArtifact artifact = new RuntimeCatalog.RuntimeArtifact();
        artifact.abi = manifest.abi;
        artifact.size = 1;
        artifact.sha256 = RuntimeIntegrity.sha256("module-placeholder".getBytes(StandardCharsets.UTF_8));
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        return artifact;
    }

    /** Lines of {@code /proc/self/maps} that mention the sherpa library. */
    private static String mappedLibraryLines() throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        StringBuilder found = new StringBuilder();
        for (String line : maps.split("\n")) {
            if (line.contains(LIBRARY)) found.append(line).append('\n');
        }
        return found.toString();
    }

    @Test
    public void sdkLoadsTheNativeLibraryFromTheInstalledModule() throws Exception {
        RuntimePlatform.PackageInfo packageInfo = RuntimeAndroid.packageInfo();
        assumeTrue("module " + MODULE_PACKAGE + " is not installed",
                packageInfo.nativeLibraryDir(MODULE_PACKAGE) != null);

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        assertNotNull(manifest);

        RuntimeStore store = storeWith(catalogModule(manifest), artifact(manifest));
        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo, path -> {
        }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource());

        // Mirror the production wiring exactly: the configurer must set the system property the
        // SDK reads, not merely record the path. Recording alone would leave the property unset and
        // the SDK would silently fall back to System.loadLibrary.
        StringBuilder configured = new StringBuilder();
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(
                loader, ABI, path -> {
                    configured.append(path);
                    System.setProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY, path);
                }, () -> true);

        assertEquals("gate should select the module: " + status.getProblem(),
                RuntimeSherpa.Source.MODULE, status.getSource());
        assertEquals(status.getNativePath(), configured.toString());
        assertEquals(configured.toString(), System.getProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY));

        // Nothing has touched a sherpa class yet, so the SDK has not loaded anything.
        assertTrue("sherpa should not be loaded before this point:\n" + mappedLibraryLines(),
                mappedLibraryLines().isEmpty());

        // Trigger the SDK's own loader. It reads the property we just set.
        LibraryUtils.load();

        String mapped = mappedLibraryLines();
        assertTrue("expected " + LIBRARY + " to be mapped after load", !mapped.isEmpty());
        assertTrue("expected the module's copy to be loaded, got:\n" + mapped,
                mapped.contains(MODULE_PACKAGE));
        assertTrue("expected the host's bundled copy NOT to be loaded, got:\n" + mapped,
                !mapped.contains(HOST_PACKAGE));
    }

    /**
     * A module installed by another route has no registry record but is present on the device.
     *
     * <p>It must be used: availability asks the platform, so rejecting it here would make the
     * feature appear available and then fail at load time.
     */
    @Test
    public void gateUsesASideloadedModuleThatHasNoRegistryRecord() throws Exception {
        RuntimePlatform.PackageInfo packageInfo = RuntimeAndroid.packageInfo();
        assumeTrue("module " + MODULE_PACKAGE + " is not installed",
                packageInfo.nativeLibraryDir(MODULE_PACKAGE) != null);

        // An empty registry: the host never recorded an install for this module.
        RuntimeLoader loader = new RuntimeLoader(
                new RuntimeStore(new MemoryStorage()), packageInfo, path -> {
        }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource());

        StringBuilder configured = new StringBuilder();
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(
                loader, ABI, path -> {
                    configured.append(path);
                    System.setProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY, path);
                }, () -> true);

        assertEquals("a sideloaded module must be used: " + status.getProblem(),
                RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isFromModule());
        assertEquals(status.getNativePath(), configured.toString());
    }

    /**
     * With no module on the device, the gate falls back to the bundled library.
     *
     * <p>Uses a stub {@code PackageInfo} so the result does not depend on whether this particular
     * device happens to have the module installed.
     */
    @Test
    public void gateFallsBackToTheBundledLibraryWhenNoModuleIsPresent() {
        RuntimePlatform.PackageInfo absent = new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                return null;
            }

            @Override
            public int versionCode(String packageName) {
                return -1;
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

        RuntimeLoader loader = new RuntimeLoader(
                new RuntimeStore(new MemoryStorage()), absent, path -> {
        }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource());

        StringBuilder configured = new StringBuilder();
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(
                loader, ABI, path -> {
                    configured.append(path);
                    System.setProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY, path);
                }, () -> true);

        assertEquals(RuntimeSherpa.Source.BUNDLED, status.getSource());
        assertTrue("bundled path must not be configured", configured.toString().isEmpty());
    }
}
