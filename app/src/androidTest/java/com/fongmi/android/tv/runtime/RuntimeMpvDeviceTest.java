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
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Device verification for the MPV module.
 *
 * <p>This is the case the design cares most about: nine interdependent libraries plus a
 * host-provided {@code libc++_shared.so}. Two copies of that soname break dynamic linking for every
 * native consumer, so the host must supply exactly one and the module must prove it matches.
 *
 * <p>Loads are recorded through the injected seam rather than performed, so the test can assert the
 * order without leaving the process holding a half-initialised native bundle. The real load is
 * exercised by playing a video.
 *
 * <p>Requires {@code com.silent.runtime.mpv}; skips when absent.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeMpvDeviceTest {

    private static final String MODULE_ID = "mpv";
    private static final String MODULE_PACKAGE = "com.silent.runtime.mpv";
    private static final String ABI = "arm64-v8a";
    private static final String HOST_LIB = "libc++_shared.so";
    private static final String ENTRY = "libplayer.so";

    private final List<String> loadCalls = new ArrayList<>();

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

    private static boolean installed() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private void requireModule() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", installed());
    }

    /** Seeds the store from the module's own manifest, as the installer would. */
    private RuntimeStore seededStore(RuntimeManifest manifest) {
        RuntimeCatalog.RuntimeModule module = new RuntimeCatalog.RuntimeModule();
        module.id = manifest.module;
        module.packageName = manifest.packageName;
        module.compatibilityId = manifest.compatibilityId;
        module.versionCode = manifest.versionCode;
        module.versionName = manifest.versionName;
        RuntimeCatalog.RuntimeArtifact artifact = new RuntimeCatalog.RuntimeArtifact();
        artifact.abi = manifest.abi;
        artifact.size = 1;
        artifact.sha256 = RuntimeIntegrity.sha256("seeded".getBytes());
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        module.artifacts = List.of(artifact);

        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    private RuntimeLoader loader(RuntimeStore store) {
        return new RuntimeLoader(
                store, RuntimeAndroid.packageInfo(), path -> {
        }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource());
    }

    private RuntimeMpv.Status prepare(RuntimeStore store) {
        return RuntimeMpv.prepare(
                loader(store),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                path -> loadCalls.add(new File(path).getName()),
                ABI);
    }

    @Test
    public void moduleExposesAllNineLibrariesAndNotItsOwnHostLibrary() {
        requireModule();

        File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
        assertNotNull(dir);

        String[] expected = {
                "libmvutil.so", "libmwresample.so", "libmwscale.so", "libmvcodec.so",
                "libmvformat.so", "libmvfilter.so", "libmvdevice.so", "libmpv.so", "libplayer.so"};
        for (String name : expected) {
            assertTrue("module should expose " + name, new File(dir, name).isFile());
        }
        // Two copies of this soname would collide; the host supplies the only one.
        assertFalse("module must not bundle libc++_shared.so",
                new File(dir, HOST_LIB).isFile());
    }

    @Test
    public void hostProvidesTheLibraryTheModuleDeclares() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        assertNotNull(manifest);
        assertTrue("manifest should declare the host library it needs",
                manifest.hostLibraries().containsKey(HOST_LIB));

        File host = RuntimeAndroid.hostLibraries().resolve(HOST_LIB);
        assertNotNull("host should provide " + HOST_LIB, host);
        assertTrue(host.isFile());

        String actual = RuntimeIntegrity.sha256(host);
        assertTrue("the host copy must be the one the module was built against\n"
                        + "declared: " + manifest.hostLibraries().get(HOST_LIB) + "\nactual:   " + actual,
                RuntimeIntegrity.matches(manifest.hostLibraries().get(HOST_LIB), actual));
    }

    @Test
    public void moduleVerifiesAgainstTheRealPlatformDirectory() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = prepare(seededStore(manifest));

        assertTrue(status.getProblem(), status.isOk());
    }

    @Test
    public void loadsHostLibraryFirstThenTheModulesInDeclaredOrder() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = prepare(seededStore(manifest));

        assertTrue(status.getProblem(), status.isOk());
        assertEquals("host libc++_shared.so must be loaded first, or the module's libraries "
                + "cannot resolve it", HOST_LIB, loadCalls.get(0));
        assertEquals("nine module libraries should follow the host copy",
                10, loadCalls.size());
        assertEquals("entry library is last: it depends on the rest", ENTRY, loadCalls.get(9));
    }

    @Test
    public void entryLibraryResolvesToARealFile() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = prepare(seededStore(manifest));

        assertTrue(status.getProblem(), status.isOk());
        File entry = new File(status.getEntryLibraryPath());
        assertTrue("entry library should exist: " + entry, entry.isFile());
        assertEquals(ENTRY, entry.getName());
        assertTrue("all nine loads should target real files", status.getLoaded().size() == 9);
    }

    /** A registry that points at the module must be rejected when the entry is wrong. */
    @Test
    public void rejectsAModuleWhoseContractIsUnknown() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeStore store = seededStore(manifest);
        RuntimeStore.Entry entry = store.get(MODULE_ID);
        entry.compatibilityId = "unknown-contract-v9";
        store.put(entry);

        RuntimeMpv.Status status = prepare(store);

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("不支持的模块契约"));
        assertTrue(loadCalls.isEmpty());
    }

    @Test
    public void reportsNotInstalledInsteadOfThrowingWhenAbsent() {
        RuntimeStore empty = new RuntimeStore(new MemoryStorage());
        RuntimeMpv.Status status = prepare(empty);

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("未安装"));
        assertTrue(loadCalls.isEmpty());
    }

    /** Reads the raw manifest for diagnostics; keeps the import surface honest. */
    @Test
    public void manifestDeclaresOrderedLibraries() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        List<String> order = manifest.librariesToLoad();

        assertEquals("the module ships nine libraries", 9, order.size());
        assertEquals("first load must be the lowest-level library",
                "lib/arm64-v8a/libmvutil.so", order.get(0));
        assertEquals("last load must be the entry library",
                "lib/arm64-v8a/libplayer.so", order.get(8));
        // No declared library may also be a host library.
        for (String declared : order) {
            String name = declared.substring(declared.lastIndexOf('/') + 1);
            assertFalse("loadOrder must not include the host library: " + name,
                    manifest.hostLibraries().containsKey(name));
        }
    }

    /** Byte-level check that the module's entry library is intact. */
    @Test
    public void moduleFilesAreReadableFromThePlatformDirectory() throws Exception {
        requireModule();

        File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
        File entry = new File(dir, ENTRY);
        try (FileInputStream in = new FileInputStream(entry)) {
            byte[] header = new byte[4];
            int read = in.read(header);
            assertEquals("should read the ELF magic", 4, read);
            assertEquals(0x7F, header[0] & 0xFF);
            assertEquals('E', header[1]);
            assertEquals('L', header[2]);
            assertEquals('F', header[3]);
        }
    }
}
