package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * DV5 renderer module resolution.
 *
 * <p>{@code ExoDv5Native} calls {@code System.loadLibrary("exo_dovi_renderer")} by bare name, which
 * searches only the host's own native library directory. A module's library lives elsewhere, so the
 * host installs a resolver mapping the bare name to the module's file. These tests pin that mapping
 * and, importantly, that it degrades to the platform lookup rather than throwing when no module is
 * present — the renderer is an optional enhancement, so absence must be a value the caller can
 * branch on.
 */
public class RuntimeDv5Test {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static RuntimePlatform.PackageInfo packageInfo(File libDir) {
        return new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                return libDir;
            }

            @Override
            public int versionCode(String packageName) {
                return libDir == null ? -1 : 1;
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

    /** Creates a directory holding the module's library, so the resolver has a real file. */
    private File moduleDir() throws IOException {
        File dir = folder.newFolder("dv5");
        assertTrue(new File(dir, RuntimeDv5.LIBRARY_FILE).createNewFile());
        return dir;
    }

    @Test
    public void availabilityIsTrueWhenEitherSourceExists() {
        assertTrue("a module satisfies availability", RuntimeDv5.isAvailable(true, false));
        assertTrue("a bundled copy satisfies availability", RuntimeDv5.isAvailable(false, true));
        assertTrue("both sources satisfy availability", RuntimeDv5.isAvailable(true, true));
        assertFalse("neither source means unavailable", RuntimeDv5.isAvailable(false, false));
    }

    @Test
    public void moduleIsInstalledOnlyWhenItsDirectoryExists() throws IOException {
        assertTrue(RuntimeDv5.isModuleInstalled(packageInfo(moduleDir())));
        assertFalse(RuntimeDv5.isModuleInstalled(packageInfo(null)));
        assertFalse("a null platform must not be read as installed",
                RuntimeDv5.isModuleInstalled(null));
    }

    @Test
    public void installReportsFailureWithoutAModule() {
        RuntimeDv5.Status status = RuntimeDv5.install(packageInfo(null));
        assertFalse(status.isOk());
        assertFalse("a failure should explain itself", status.getProblem().isEmpty());
        assertNull(status.getLibraryDir());
    }

    @Test
    public void installReportsSuccessWithAModule() throws IOException {
        File dir = moduleDir();
        RuntimeDv5.Status status = RuntimeDv5.install(packageInfo(dir));
        assertTrue(status.isOk());
        assertEquals(dir, status.getLibraryDir());
    }

    /**
     * The resolver maps the bare name to the module's file, which is what lets the host load a
     * module-delivered library despite the bare-name lookup being unable to see it.
     */
    @Test
    public void resolverMapsTheBareNameToTheModuleFile() throws IOException {
        File dir = moduleDir();
        RuntimeDv5.install(packageInfo(dir));

        File resolved = RuntimeDv5.resolve(RuntimeDv5.LIBRARY);
        assertEquals(new File(dir, RuntimeDv5.LIBRARY_FILE), resolved);
    }

    /**
     * Only the renderer's own bare name is redirected.
     *
     * <p>Returning a file for any other name would let this resolver answer for libraries it knows
     * nothing about, which could redirect an unrelated load into the module directory.
     */
    @Test
    public void resolverIgnoresOtherNames() throws IOException {
        RuntimeDv5.install(packageInfo(moduleDir()));

        assertNull(RuntimeDv5.resolve("exo_ass"));
        assertNull(RuntimeDv5.resolve("c++_shared"));
        assertNull(RuntimeDv5.resolve(""));
        assertNull(RuntimeDv5.resolve(null));
    }

    /**
     * Without a module the resolver returns null, so the caller falls back to the platform lookup.
     *
     * <p>This is the degradation contract: absence must be reported as null rather than throwing,
     * because {@code ExoDv5Native} turns a failure into an unavailable probe and the player then
     * uses one of its other Dolby Vision routes.
     */
    @Test
    public void resolverReturnsNullWithoutAModule() {
        RuntimeDv5.install(packageInfo(null));
        assertNull(RuntimeDv5.resolve(RuntimeDv5.LIBRARY));
    }

    /** After uninstall the resolver is gone, restoring plain {@code System.loadLibrary} behaviour. */
    @Test
    public void uninstallClearsTheResolver() throws IOException {
        RuntimeDv5.install(packageInfo(moduleDir()));
        assertTrue(RuntimeDv5.resolve(RuntimeDv5.LIBRARY) != null);

        RuntimeDv5.uninstall();
        assertNull(RuntimeDv5.resolve(RuntimeDv5.LIBRARY));
    }

    /**
     * A module directory that exists but does not contain the library resolves to null.
     *
     * <p>Returning a non-existent file would make the caller attempt a load that must fail, turning a
     * clean fallback into an exception path.
     */
    @Test
    public void resolverReturnsNullWhenTheFileIsMissing() throws IOException {
        File empty = folder.newFolder("dv5-empty");
        RuntimeDv5.install(packageInfo(empty));

        assertNull(RuntimeDv5.resolve(RuntimeDv5.LIBRARY));
    }

    /**
     * The contract and package name are registered host-side.
     *
     * <p>A module whose contract the host does not recognise is rejected at load time, so the two
     * tables must agree with what the module ships.
     */
    @Test
    public void contractAndPackageNameAreRegistered() {
        assertEquals(RuntimeDv5.MODULE_PACKAGE, RuntimeCompatibility.packageName(RuntimeDv5.MODULE_ID));
        assertTrue(RuntimeCompatibility.isAccepted("exo-dv5-renderer-v1"));
    }
}
