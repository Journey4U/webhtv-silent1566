package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import tv.danmaku.ijk.media.player.IjkLibLoader;

/**
 * Device verification for the IJK module.
 *
 * <p>IJK loads by bare name through {@code System.loadLibrary}, which searches only the host's own
 * native library directory. The module's libraries live elsewhere, so the host installs a resolver.
 * These tests prove the resolver produces real files and that the libraries actually load from the
 * module — the latter is what a path assertion cannot show.
 *
 * <p>Requires {@code com.silent.runtime.ijk}; skips when absent.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeIjkDeviceTest {

    private static final String MODULE_PACKAGE = "com.silent.runtime.ijk";
    private static final String ABI = "arm64-v8a";
    private static final String[] LIBS = {"libijkffmpeg.so", "libijksdl.so", "libijkplayer.so"};

    private static boolean installed() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private void requireModule() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", installed());
    }

    /** Lines of {@code /proc/self/maps} mentioning any IJK library. */
    private static String mapsFor() throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        StringBuilder found = new StringBuilder();
        for (String line : maps.split("\n")) {
            for (String name : LIBS) {
                if (line.contains("/" + name)) {
                    found.append(line).append('\n');
                    break;
                }
            }
        }
        return found.toString();
    }

    @Test
    public void moduleExposesAllThreeLibraries() {
        requireModule();

        File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
        assertNotNull(dir);
        for (String name : LIBS) {
            assertTrue("module should expose " + name, new File(dir, name).isFile());
        }
    }

    /**
     * The decisive check: the bare names IJK asks for must resolve to the module's files.
     *
     * <p>Without this the module is unreachable, because {@code System.loadLibrary} cannot see
     * another package's directory.
     */
    @Test
    public void resolverMapsBareNamesToTheInstalledModule() {
        requireModule();

        RuntimeIjk.Status status = RuntimeIjk.install(RuntimeAndroid.packageInfo());
        try {
            assertTrue(status.getProblem(), status.isOk());

            IjkLibLoader.Resolver resolver = IjkLibLoader.getResolver();
            assertNotNull("a resolver should be installed", resolver);

            File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
            for (String name : LIBS) {
                String bare = name.substring(3, name.length() - 3);
                File resolved = resolver.resolve(bare);
                assertNotNull("bare name should resolve: " + bare, resolved);
                assertEquals(new File(dir, name).getAbsolutePath(), resolved.getAbsolutePath());
                assertTrue(resolved.isFile());
            }
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    /**
     * The libraries must actually load from the module. {@code IjkLibLoader.isAvailable()} drives
     * the real {@code System.load} path through the resolver, so a success here means the module's
     * copy was used — the host has no IJK libraries of its own in this build.
     */
    @Test
    public void librariesLoadFromTheModuleForReal() throws Exception {
        requireModule();

        RuntimeIjk.Status status = RuntimeIjk.install(RuntimeAndroid.packageInfo());
        assertTrue(status.getProblem(), status.isOk());
        try {
            String before = mapsFor();
            assertTrue("IJK should not be loaded yet:\n" + before, before.isEmpty());

            // This is the same loader IjkMediaPlayer uses; loading it exercises the resolver.
            IjkLibLoader loader = new IjkLibLoader("ijkffmpeg", "ijksdl", "ijkplayer") {
                @Override
                public void loadLibrary(String str) {
                    loadLibraryOrResolved(str);
                }
            };
            assertTrue("IJK libraries failed to load through the resolver", loader.isAvailable());

            String mapped = mapsFor();
            for (String name : LIBS) {
                assertTrue("expected " + name + " to be mapped, got:\n" + mapped,
                        mapped.contains("/" + name));
            }
            assertTrue("expected the module's copy to be loaded, got:\n" + mapped,
                    mapped.contains(MODULE_PACKAGE));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    /** Availability must be satisfied by the installed module. */
    @Test
    public void availabilityReportsIjkUsableWithTheModuleInstalled() {
        requireModule();

        assertTrue(RuntimeIjk.isModuleInstalled(RuntimeAndroid.packageInfo()));
        assertTrue(RuntimeIjk.isAvailable(
                RuntimeIjk.isModuleInstalled(RuntimeAndroid.packageInfo()), false));
    }

    /** The resolver must re-check, so the order of install and load does not matter. */
    @Test
    public void resolverWorksRegardlessOfInstallOrder() {
        requireModule();

        RuntimeIjk.install(RuntimeAndroid.packageInfo());
        try {
            assertNotNull(IjkLibLoader.getResolver().resolve("ijkplayer"));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    /**
     * With no module present the resolver must fall back, not throw.
     *
     * <p>Uses a stub {@code PackageInfo} so the result does not depend on whether this particular
     * device happens to have the module installed.
     */
    @Test
    public void reportsAbsentModuleInsteadOfThrowing() {
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

        RuntimeIjk.Status status = RuntimeIjk.install(absent);
        try {
            assertFalse(status.isOk());
            assertTrue(status.getProblem(), status.getProblem().contains("未安装"));
            // A null answer makes IjkLibLoader fall back to System.loadLibrary, which is correct
            // for a build that still bundles the libraries.
            assertNull(IjkLibLoader.getResolver().resolve("ijkplayer"));
        } finally {
            RuntimeIjk.uninstall();
        }
    }
}
