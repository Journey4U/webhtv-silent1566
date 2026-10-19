package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Python module's positive path, exercised through the real loader.
 *
 * <p>Two things are being proved here that no unit test could. First, that the module verifies when
 * driven by {@link RuntimeLoader} rather than by a hand-built verifier call — the payload seam was
 * added in one stage and left unwired, so a module declaring a payload was rejected with "cannot be
 * verified without a reader" even though the host could read the file. Second, that the host no
 * longer ships the runtime, so the module is genuinely the only source.
 *
 * <p>Every existing module declares only {@code lib/**}, which is why the unwired seam went unnoticed:
 * nothing reached it. This module is the first that does.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimePythonModuleDeviceTest {

    private static final String MODULE_PACKAGE = RuntimePython.MODULE_PACKAGE;

    /** The order the manifest must declare, measured in S12. */
    private static final String[] TOPOLOGICAL = {
            "libcrypto_python.so",
            "libpython3.10.so",
            "libsqlite3_python.so",
            "libssl_python.so",
            "libcrypto_chaquopy.so",
            "libssl_chaquopy.so",
            "libsqlite3_chaquopy.so",
            "libchaquopy_java.so",
    };

    private static String abi() {
        return RuntimeAbi.resolve(BuildConfig.FLAVOR_abi, android.os.Build.SUPPORTED_ABIS);
    }

    private static boolean moduleInstalled() {
        return RuntimePython.isModuleInstalled(RuntimeAndroid.packageInfo());
    }

    /**
     * The module verifies when the loader drives verification.
     *
     * <p>This is the check that would have caught the unwired payload seam: the loader is the
     * production path, and it must supply a reader for a module that declares a payload.
     */
    @Test
    public void moduleVerifiesThroughTheLoader() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeLoader loader = RuntimeAndroid.loader(RuntimeAndroid.store());
        RuntimeLoader.Outcome outcome = loader.verify(RuntimePython.MODULE_ID, abi());

        System.out.println("S27PY verify ok=" + outcome.isOk() + " problem=" + outcome.getProblem());
        assertTrue("the module must verify through the production loader path: "
                + outcome.getProblem(), outcome.isOk());
        assertNotNull(outcome.getManifest());
        assertNotNull(outcome.getLibraryDir());
    }

    /**
     * The manifest declares the libraries in dependency order.
     *
     * <p>Order is load-bearing: a module's directory is not on the linker's search path, so each
     * dependency has to be mapped before its dependents.
     */
    @Test
    public void manifestDeclaresTheTopologicalOrder() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeLoader loader = RuntimeAndroid.loader(RuntimeAndroid.store());
        RuntimeLoader.Outcome outcome = loader.verify(RuntimePython.MODULE_ID, abi());
        assumeTrue("module did not verify; covered by moduleVerifiesThroughTheLoader",
                outcome.isOk());

        List<String> declared = new ArrayList<>();
        for (String entry : outcome.getManifest().librariesToLoad()) {
            declared.add(new File(entry).getName());
        }

        assertEquals("the manifest must declare every library in dependency order",
                List.of(TOPOLOGICAL), declared);
    }

    /**
     * Every declared library exists in the module's directory.
     *
     * <p>Verification already checks digests; this states the layout expectation directly, so a
     * failure distinguishes "the module is missing files" from "a digest drifted".
     */
    @Test
    public void everyDeclaredLibraryIsPresent() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeLoader loader = RuntimeAndroid.loader(RuntimeAndroid.store());
        RuntimeLoader.Outcome outcome = loader.verify(RuntimePython.MODULE_ID, abi());
        assumeTrue("module did not verify", outcome.isOk());

        File dir = outcome.getLibraryDir();
        for (String name : TOPOLOGICAL) {
            File file = new File(dir, name);
            assertTrue("declared library should exist: " + name, file.isFile());
            assertTrue("declared library should be non-empty: " + name, file.length() > 0);
        }
    }

    /**
     * The payload tree is readable through the module's context.
     *
     * <p>This is what the payload seam exists for: Chaquopy reads its stdlib and bootstrap from
     * {@code assets/chaquopy/**}, which lives inside the module APK rather than in a directory the
     * host can reach by path.
     */
    @Test
    public void payloadIsReadableThroughTheModuleContext() throws IOException {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        RuntimeLoader loader = RuntimeAndroid.loader(RuntimeAndroid.store());
        RuntimeLoader.Outcome outcome = loader.verify(RuntimePython.MODULE_ID, abi());
        assumeTrue("module did not verify", outcome.isOk());

        // Chaquopy's own entry point: the file its Platform constructor opens first.
        String path = "assets/chaquopy/build.json";
        try (InputStream in = RuntimeAndroid.payloadReader().open(MODULE_PACKAGE, path)) {
            assertNotNull("the payload reader should open " + path, in);
            assertTrue("the payload should have content", in.read() != -1);
        }
    }

    /**
     * The host no longer ships the runtime, so the module is the only source.
     *
     * <p>Without this the tests above could pass against a bundled runtime and say nothing about the
     * module. It is also what keeps the exclusion honest: a future change that reintroduced the
     * bundled copy would fail here.
     */
    @Test
    public void hostNoLongerShipsTheRuntime() {
        File libDir = new File(App.get().getApplicationInfo().nativeLibraryDir);
        for (String name : TOPOLOGICAL) {
            assertFalse("the host should not ship " + name + "; the module is the only source",
                    new File(libDir, name).isFile());
        }

        boolean bundled;
        try (InputStream in = App.get().getAssets().open("chaquopy/build.json")) {
            bundled = in != null;
        } catch (IOException e) {
            bundled = false;
        }
        assertFalse("the host should not ship the runtime's payload", bundled);
    }

    /** With the module installed, the runtime reports available. */
    @Test
    public void runtimeReportsAvailableWithTheModuleInstalled() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", moduleInstalled());

        assertTrue("an installed module makes the runtime available",
                RuntimePython.isRuntimeAvailable(App.get()));
    }
}
