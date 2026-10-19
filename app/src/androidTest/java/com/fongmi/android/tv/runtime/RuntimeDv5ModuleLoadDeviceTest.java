package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The positive path for the DV5 module: with the module installed, the renderer's library loads
 * from the module's directory.
 *
 * <p>This is the counterpart to the degradation case. Once the library is excluded from the host
 * build, the module is the only source, so the resolver and the load path have to work — a test
 * that only covers absence would pass just as well if the feature were simply broken.
 *
 * <p>The assertion is about <em>where</em> the library was mapped from, not merely that a load
 * succeeded. The host no longer ships a copy, so a successful load can only have come from the
 * module, but checking the mapping origin states that directly and would catch a regression that
 * reintroduced a bundled copy.
 *
 * <p>Run this class alone. {@code ExoDv5Native} caches its attempt in a static field, and a process
 * maps a given library once, so a sibling test that already triggered either would make the
 * observation below meaningless rather than wrong — hence the preconditions.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeDv5ModuleLoadDeviceTest {

    private static final String MODULE_PACKAGE = RuntimeDv5.MODULE_PACKAGE;
    private static final String LIBRARY_FILE = RuntimeDv5.LIBRARY_FILE;

    private static RuntimePlatform.PackageInfo packageInfo() {
        return RuntimeAndroid.packageInfo();
    }

    private static boolean moduleInstalled() {
        return RuntimeDv5.isModuleInstalled(packageInfo());
    }

    /** Distinct directories that {@code libName} is currently mapped from. */
    private static List<String> mappingOrigins(String libName) throws IOException {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        List<String> origins = new ArrayList<>();
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 6) continue;
            String path = fields[5];
            if (!path.endsWith("/" + libName)) continue;
            // The kernel reports /data/data/... while the app sees /data/user/0/...; both name the
            // same directory, so compare canonical paths.
            String dir = new File(path).getParentFile().getCanonicalPath();
            if (!origins.contains(dir)) origins.add(dir);
        }
        return origins;
    }

    /** The module's native library directory, or null when it is not installed. */
    private static File moduleLibraryDir() {
        return packageInfo().nativeLibraryDir(MODULE_PACKAGE);
    }

    /**
     * The resolver points at the module's file, which is what lets the host load a module-delivered
     * library despite the bare-name lookup being unable to see it.
     */
    @Test
    public void resolverPointsAtTheModuleFile() throws Exception {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeDv5.install(packageInfo());

        File resolved = RuntimeDv5.resolve(RuntimeDv5.LIBRARY);
        assertNotNull("the resolver should return the module's file", resolved);
        assertEquals(new File(moduleLibraryDir(), LIBRARY_FILE), resolved);
        assertTrue("the resolved file should exist", resolved.isFile());
    }

    /**
     * Loading the resolved file maps it from the module's directory.
     *
     * <p>This is the load path {@code ExoDv5Native} takes, exercised directly so the result does not
     * depend on the renderer's static caching.
     */
    @Test
    public void loadingTheResolvedFileMapsItFromTheModuleDirectory() throws Exception {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());
        assumeTrue("another test already loaded " + LIBRARY_FILE + " in this process, so the module "
                        + "copy cannot become the mapping; run this class alone to observe it",
                mappingOrigins(LIBRARY_FILE).isEmpty());

        RuntimeDv5.install(packageInfo());
        File resolved = RuntimeDv5.resolve(RuntimeDv5.LIBRARY);
        assertNotNull(resolved);

        System.load(resolved.getAbsolutePath());

        List<String> origins = mappingOrigins(LIBRARY_FILE);
        assertEquals("the library should be mapped from exactly one directory; origins=" + origins,
                1, origins.size());
        assertEquals("the library should be mapped from the module's directory",
                moduleLibraryDir().getCanonicalPath(), origins.get(0));
    }

    /**
     * The renderer reports an outcome rather than throwing, with the module present.
     *
     * <p>Whether the answer is available depends on the device — the capability probe needs Vulkan
     * 1.2 and AImageReader, which an emulator lacks — so the assertion is about the contract, not
     * about availability. On an emulator the expected reason is {@code missing-capability}, which
     * still proves the library loaded.
     */
    @Test
    public void probeReportsAnOutcomeWithTheModuleInstalled() throws Exception {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeDv5.install(packageInfo());

        Class<?> nativeClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5Native");
        java.lang.reflect.Method probe = nativeClass.getDeclaredMethod("probe");
        probe.setAccessible(true);
        Object result = probe.invoke(null);
        assertNotNull("probe() must return a result rather than null", result);

        java.lang.reflect.Method available = result.getClass().getDeclaredMethod("available");
        available.setAccessible(true);
        java.lang.reflect.Method reason = result.getClass().getDeclaredMethod("reason");
        reason.setAccessible(true);

        boolean isAvailable = (Boolean) available.invoke(result);
        String why = String.valueOf(reason.invoke(result));
        System.out.println("S23DV5 moduleInstalled=true probeAvailable=" + isAvailable + " reason=" + why);

        assertTrue("probe() must report a recognised state, not an arbitrary string; got: " + why,
                isAvailable || "api-below-26".equals(why)
                        || "missing-capability".equals(why)
                        || why.startsWith("load-"));
    }

    /**
     * The host no longer ships its own copy, so the module is genuinely the only source.
     *
     * <p>Without this the tests above could pass against a bundled library and say nothing about
     * the module. It is also the check that keeps the exclusion honest: if a future change
     * reintroduced the bundled copy, this would fail.
     */
    @Test
    public void hostDoesNotShipItsOwnCopy() {
        File bundled = new File(App.get().getApplicationInfo().nativeLibraryDir, LIBRARY_FILE);
        assertTrue("the host should not ship " + LIBRARY_FILE + "; the module is the only source",
                !bundled.isFile());
    }
}
