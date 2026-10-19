package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Documents a hard boundary of the module architecture: preloading by absolute path does
 * <em>not</em> satisfy a later bare-name lookup.
 *
 * <p>This decides whether the protocol extractors (thunder, jianpian) can be modularised. Their
 * native libraries are loaded by <em>vendor code</em> calling
 * {@code System.loadLibrary("xl_thunder_sdk")}. Unlike IJK — whose loader is repository source and
 * could be given a resolver — that code is a binary dependency, so the only lever the host has is to
 * preload the module's copy by absolute path and hope the later bare-name request reuses it.
 *
 * <p>It does not. Android resolves a bare name to a path through the class loader's
 * {@code nativeLibraryDirectories} <em>before</em> {@code dlopen} runs, so a library that lives
 * outside those directories is never found — even when it is already mapped in the process. The
 * failure is at name resolution, not at loading.
 *
 * <p>The first test is the control that makes the second one meaningful: a library that <em>is</em>
 * in the host directory loads by bare name, so the failure below is attributable to the file's
 * location rather than to the mechanism being broken in general.
 *
 * <p>If the second test ever starts passing, Android has begun deduplicating by soname and the
 * protocol extractors become modularisable without touching vendor code.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimePreloadDedupDeviceTest {

    private static final String MODULE_PACKAGE = "com.silent.runtime.ijk";
    private static final String MODULE_LIB = "libijkffmpeg.so";
    private static final String MODULE_BARE = "ijkffmpeg";
    /** Present in the host's own native library directory, so the control has a subject. */
    private static final String HOST_LIB = "libc++_shared.so";
    private static final String HOST_BARE = "c++_shared";

    private static boolean moduleInstalled() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    /** How many mappings of {@code libName} originate from {@code packageName}'s directory. */
    private static int originCount(String libName, String packageName) throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : maps.split("\n")) {
            if (line.contains("/" + libName) && line.contains(packageName)) count++;
        }
        return count;
    }

    /**
     * Control: a library inside the host's native library directory loads by bare name.
     *
     * <p>Without this, the finding below could be explained by the bare-name mechanism being broken
     * for unrelated reasons.
     */
    @Test
    public void bareNameLookupSucceedsForALibraryInTheHostDirectory() {
        File dir = new File(com.fongmi.android.tv.App.get().getApplicationInfo().nativeLibraryDir);
        assumeTrue("host does not ship " + HOST_LIB, new File(dir, HOST_LIB).isFile());

        System.loadLibrary(HOST_BARE);
    }

    /**
     * Finding: a module library preloaded by absolute path is still invisible to a bare-name
     * lookup.
     *
     * <p>This is why thunder and jianpian cannot leave the APK without patching vendor code.
     */
    @Test
    public void preloadedLibraryDoesNotSatisfyALaterBareNameLookup() throws Exception {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
        File lib = new File(dir, MODULE_LIB);
        assertTrue("module should ship " + MODULE_LIB, lib.isFile());

        // Step 1: the host preloads the module's copy by absolute path. This succeeds.
        System.load(lib.getAbsolutePath());
        assertTrue("the module copy should be mapped after the preload",
                originCount(MODULE_LIB, MODULE_PACKAGE) > 0);

        // Step 2: the bare-name lookup vendor code performs. Android resolves the name through the
        // class loader's nativeLibraryDirectories, which do not include the module's directory, so
        // the lookup fails even though the library is already mapped.
        try {
            System.loadLibrary(MODULE_BARE);
            fail("bare-name lookup unexpectedly succeeded; Android now dedupes by soname, which "
                    + "would make the protocol extractors modularisable — revisit that decision");
        } catch (UnsatisfiedLinkError expected) {
            // The documented boundary. The library stays mapped from the module, which is what makes
            // this attributable to name resolution rather than to a failed load.
            assertTrue("the preloaded copy should still be mapped",
                    originCount(MODULE_LIB, MODULE_PACKAGE) > 0);
        }
    }

    /**
     * The module's own libraries remain reachable through the resolver path.
     *
     * <p>Contrast with the finding: the host <em>can</em> load a module library when it controls the
     * call, which is exactly what {@code RuntimeIjk} does.
     */
    @Test
    public void moduleLibraryLoadsWhenTheHostControlsTheCall() throws Exception {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        File dir = RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE);
        File lib = new File(dir, MODULE_LIB);
        assertTrue(lib.isFile());

        // Already loaded by the test above in the same process; loading again is a no-op and must
        // not throw. The point is that a host-controlled call has a path to the module.
        System.load(lib.getAbsolutePath());
        assertTrue(originCount(MODULE_LIB, MODULE_PACKAGE) > 0);
    }
}
