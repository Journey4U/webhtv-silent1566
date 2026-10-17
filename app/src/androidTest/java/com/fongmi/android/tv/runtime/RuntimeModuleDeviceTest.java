package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/**
 * Device verification for the runtime module path.
 *
 * <p>These assertions cover what a plain JVM test cannot: that the platform really exposes an
 * installed module's native library directory, that its manifest is readable, and that the
 * verifier resolves the manifest's {@code lib/<abi>/} paths against the flat directory the
 * platform actually provides.
 *
 * <p>Requires {@code com.silent.runtime.sherpa} to be installed. When it is absent the tests skip
 * rather than fail, so a machine without the module still gets a green suite — but a skip here
 * means the module path is unverified, not verified.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeModuleDeviceTest {

    private static final String MODULE_ID = "sherpa";
    private static final String PACKAGE_NAME = "com.silent.runtime.sherpa";
    private static final String ABI = "arm64-v8a";
    private static final String LIBRARY = "libsherpa-onnx-jni.so";

    private final RuntimePlatform.PackageInfo packageInfo = RuntimeAndroid.packageInfo();

    private void requireModule() {
        assumeTrue("module " + PACKAGE_NAME + " is not installed", isInstalled());
    }

    private boolean isInstalled() {
        return packageInfo.nativeLibraryDir(PACKAGE_NAME) != null;
    }

    @Test
    public void platformExposesTheInstalledModuleNativeLibraryDirectory() {
        requireModule();

        File dir = packageInfo.nativeLibraryDir(PACKAGE_NAME);

        assertNotNull(dir);
        assertTrue("nativeLibraryDir should exist: " + dir, dir.isDirectory());
        assertTrue("module version should be readable", packageInfo.versionCode(PACKAGE_NAME) > 0);
    }

    @Test
    public void nativeLibraryDirectoryContainsTheDeclaredLibrary() {
        requireModule();

        File dir = packageInfo.nativeLibraryDir(PACKAGE_NAME);
        File library = new File(dir, LIBRARY);

        assertTrue("expected " + library + " to be present", library.isFile());
        assertTrue("library should be non-empty", library.length() > 0);
    }

    @Test
    public void readsTheModuleManifestFromTheInstalledApk() throws Exception {
        requireModule();

        String raw = RuntimeAndroid.manifestSource().read(PACKAGE_NAME);

        assertNotNull("runtime.json should be readable from the module", raw);
        assertTrue(raw.contains("\"module\""));
        assertTrue(raw.contains(PACKAGE_NAME));
        assertTrue(raw.contains(LIBRARY));
    }

    /**
     * The decisive check: the manifest declares {@code lib/arm64-v8a/libsherpa-onnx-jni.so} while
     * the platform's directory is a flat {@code .../lib/arm64/}. Verifying must succeed anyway.
     */
    @Test
    public void verifiesTheInstalledModuleAgainstTheFlatPlatformDirectory() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(PACKAGE_NAME));
        assertNotNull(manifest);
        assertTrue("manifest should be valid: " + manifest.library, manifest.isValid());

        File dir = packageInfo.nativeLibraryDir(PACKAGE_NAME);
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                catalogModule(manifest), manifest, dir, ABI, RuntimeAndroid.hostLibraries());

        assertTrue("verification failed: " + result.describe(), result.isOk());
        assertEquals(LIBRARY, RuntimeManifest.nativeLibraryEntry(result.getEntryLibrary(), manifest.abi));
        assertTrue(new File(dir, RuntimeManifest.nativeLibraryEntry(result.getEntryLibrary(), manifest.abi)).isFile());
    }

    /** In-memory persistence that actually honours the read/write contract. */
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

    /** Seeds a store that reports the installed module, without touching app preferences. */
    private static RuntimeStore storeWith(RuntimeCatalog.RuntimeModule module, RuntimeCatalog.RuntimeArtifact artifact) {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    @Test
    public void loadsTheInstalledModuleThroughTheFullLoaderPath() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(PACKAGE_NAME));
        RuntimeStore store = storeWith(catalogModule(manifest), artifact(manifest));

        RuntimeLoader loader = new RuntimeLoader(
                store,
                packageInfo,
                path -> {
                    // Do not actually dlopen here: the point is that the path resolved and verified.
                },
                RuntimeAndroid.hostLibraries(),
                RuntimeAndroid.manifestSource());

        RuntimeLoader.Outcome outcome = loader.load(MODULE_ID, ABI);

        assertTrue("loader failed: " + outcome.getProblem(), outcome.isOk());
        assertNotNull(outcome.getEntryLibrary());
        assertEquals(LIBRARY, outcome.getEntryLibrary().getName());
        assertTrue(outcome.getEntryLibrary().isFile());
    }

    @Test
    public void sherpaGateResolvesToTheInstalledModule() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(PACKAGE_NAME));
        RuntimeStore store = storeWith(catalogModule(manifest), artifact(manifest));

        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo, path -> {
        }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource());

        StringBuilder configured = new StringBuilder();
        RuntimeSherpa.Status status = RuntimeSherpa.prepare(
                loader, ABI, configured::append, () -> true);

        assertEquals("expected the module to be used: " + status.getProblem(),
                RuntimeSherpa.Source.MODULE, status.getSource());
        assertTrue(status.isFromModule());
        assertEquals(status.getNativePath(), configured.toString());
        assertFalse("module path should be set", configured.toString().isEmpty());
        assertTrue(configured.toString(), new File(configured.toString()).isDirectory());
    }

    /**
     * The SDK reads this property from a static initializer, so a wrong name would silently fall
     * back to {@code System.loadLibrary} and load the bundled copy instead of the module.
     */
    @Test
    public void settingTheNativePathPropertyIsVisibleToTheSdk() {
        String previous = System.getProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY);
        try {
            System.setProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY, "/tmp/example");
            assertEquals("/tmp/example", System.getProperty("sherpa_onnx.native.path"));
        } finally {
            if (previous == null) System.clearProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY);
            else System.setProperty(RuntimeSherpa.NATIVE_PATH_PROPERTY, previous);
        }
    }

    @Test
    public void rejectsAModuleDirectoryThatDoesNotMatchTheCatalog() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(PACKAGE_NAME));
        RuntimeCatalog.RuntimeModule module = catalogModule(manifest);
        module.versionCode = manifest.versionCode + 1;

        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                module, manifest, packageInfo.nativeLibraryDir(PACKAGE_NAME), ABI, RuntimeAndroid.hostLibraries());

        assertFalse("a version mismatch must be rejected", result.isOk());
        assertTrue(result.describe(), result.describe().contains("version mismatch"));
    }

    @Test
    public void rejectsTheWrongAbiAgainstTheInstalledModule() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(RuntimeAndroid.manifestSource().read(PACKAGE_NAME));
        RuntimeVerifier.Result result = RuntimeVerifier.verify(
                catalogModule(manifest), manifest, packageInfo.nativeLibraryDir(PACKAGE_NAME),
                "armeabi-v7a", RuntimeAndroid.hostLibraries());

        assertFalse("an ABI mismatch must be rejected", result.isOk());
        assertTrue(result.describe(), result.describe().contains("abi mismatch"));
    }

    @Test
    public void reportsAbsentModulesInsteadOfThrowing() {
        assertNotNull(RuntimeAndroid.packageInfo());
        assertEquals(null, packageInfo.nativeLibraryDir("com.silent.runtime.does.not.exist"));
        assertEquals(-1, packageInfo.versionCode("com.silent.runtime.does.not.exist"));
    }

    /** Rebuilds the catalog view of the installed module from its own manifest. */
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
        artifact.sha256 = RuntimeIntegrity.sha256("module-placeholder".getBytes());
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        return artifact;
    }
}
