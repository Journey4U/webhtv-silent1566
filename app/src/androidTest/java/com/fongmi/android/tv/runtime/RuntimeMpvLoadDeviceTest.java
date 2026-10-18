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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Proves the MPV module's libraries actually load on device.
 *
 * <p>{@link RuntimeMpvDeviceTest} checks that the right paths are chosen, in the right order, with
 * the right digests — but it never dlopens anything. Ordering and digests being correct does not
 * prove the libraries link: the recorded failure was a soname collision where every check passed
 * and the load still failed with a missing symbol, surfacing as "MPV failed to load media" for
 * nearly every source. Only a real load can rule that out.
 *
 * <p>The evidence is {@code /proc/self/maps}: after loading, every module library must be mapped
 * from the module's package directory, and {@code libc++_shared.so} must be the host's copy.
 *
 * <p><b>Run this class alone.</b> Native libraries load once per process and cannot be unloaded, so
 * a sibling test that loads a different variant first would make the result depend on ordering:
 * <pre>
 * adb shell am instrument -w \
 *   -e class com.fongmi.android.tv.runtime.RuntimeMpvLoadDeviceTest \
 *   com.silent.android.webhtv.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeMpvLoadDeviceTest {

    private static final String MODULE_ID = "mpv";
    private static final String MODULE_PACKAGE = "com.silent.runtime.mpv";
    private static final String HOST_PACKAGE = "com.silent.android.webhtv";
    private static final String ABI = "arm64-v8a";
    private static final String HOST_LIB = "libc++_shared.so";

    private static final List<String> MODULE_LIBS = List.of(
            "libmvutil.so", "libmwresample.so", "libmwscale.so", "libmvcodec.so",
            "libmvformat.so", "libmvfilter.so", "libmvdevice.so", "libmpv.so", "libplayer.so");

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

    /** Seeds the registry from the module's own manifest, as an install would. */
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
        artifact.sha256 = RuntimeIntegrity.sha256("seeded".getBytes(StandardCharsets.UTF_8));
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        module.artifacts = List.of(artifact);

        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    /** Lines of {@code /proc/self/maps} mentioning any library this test cares about. */
    private static String mapsFor(List<String> names) throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        StringBuilder found = new StringBuilder();
        for (String line : maps.split("\n")) {
            for (String name : names) {
                if (line.contains("/" + name)) {
                    found.append(line).append('\n');
                    break;
                }
            }
        }
        return found.toString();
    }

    /**
     * The decisive test: load the whole chain for real, then prove every library in the process
     * came from the module and the host supplied the one shared runtime.
     *
     * <p>Deliberately does not assert a clean process: native libraries cannot be unloaded, so any
     * sibling test in this class that ran first has already mapped them. The mapping origins are
     * the evidence, and they do not depend on who triggered the load.
     */
    @Test
    public void loadsEveryModuleLibraryForRealAndMapsThemFromTheModule() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        assertNotNull(manifest);

        RuntimeMpv.Status status = RuntimeMpv.prepare(
                new RuntimeLoader(seededStore(manifest), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                ABI);

        assertTrue("real load failed: " + status.getProblem(), status.isOk());
        assertEquals("all nine module libraries should have loaded", 9, status.getLoaded().size());

        // Every module library must be mapped from the module's own directory. A library loaded
        // earlier in the process still has to trace back to the module, so this holds regardless
        // of test order.
        for (String name : MODULE_LIBS) {
            String mapped = mapsFor(List.of(name));
            assertFalse("expected " + name + " to be mapped", mapped.isEmpty());
            assertTrue("expected " + name + " to come from the module, got:\n" + mapped,
                    mapped.contains(MODULE_PACKAGE));
            assertFalse("expected " + name + " NOT to come from the host, got:\n" + mapped,
                    mapped.contains(HOST_PACKAGE));
        }
    }

    /**
     * The soname collision that caused the original "MPV failed to load media" outage: exactly one
     * {@code libc++_shared.so} may be mapped, and it must be the host's.
     */
    @Test
    public void exactlyOneLibcxxSharedIsMappedAndItIsTheHosts() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                new RuntimeLoader(seededStore(manifest), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                ABI);
        assertTrue("load failed: " + status.getProblem(), status.isOk());

        String mapped = mapsFor(List.of(HOST_LIB));
        assertFalse("libc++_shared.so should be mapped", mapped.isEmpty());
        assertTrue("libc++_shared.so must be the host's copy, got:\n" + mapped,
                mapped.contains(HOST_PACKAGE));
        assertFalse("the module must not contribute a second copy, got:\n" + mapped,
                mapped.contains(MODULE_PACKAGE));

        // A second copy is the failure mode; count lines by origin. String.lines() is a Java 11
        // API absent on this device runtime, so splitting is not a stylistic choice.
        int hostLines = 0;
        int moduleLines = 0;
        for (String line : mapped.split("\n")) {
            if (line.contains(HOST_PACKAGE)) hostLines++;
            if (line.contains(MODULE_PACKAGE)) moduleLines++;
        }
        assertEquals("the module must not contribute its own copy", 0, moduleLines);
        assertTrue("host copy should be mapped", hostLines > 0);
    }

    /**
     * The bundle must be usable, not merely mapped: the JNI surface has to resolve. A missing
     * symbol shows up here rather than as a silent mapping.
     */
    @Test
    public void entryLibraryResolvesItsSymbols() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                new RuntimeLoader(seededStore(manifest), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                ABI);
        assertTrue("load failed: " + status.getProblem(), status.isOk());

        // Loading a library resolves its DT_NEEDED graph eagerly, so reaching this point already
        // means every dependency resolved. Assert the file is the expected ELF to rule out having
        // loaded a stub.
        File entry = new File(status.getEntryLibraryPath());
        assertTrue(entry.isFile());
        byte[] header = Files.readAllBytes(Paths.get(entry.getAbsolutePath()));
        assertEquals(0x7F, header[0] & 0xFF);
        assertEquals('E', header[1]);
        assertEquals('L', header[2]);
        assertEquals('F', header[3]);
    }
}