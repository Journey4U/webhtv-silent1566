package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.List;

/**
 * Python runtime module resolution.
 *
 * <p>The runtime's payload is unusual among the modules: besides eight native libraries it ships a
 * tree of {@code assets/chaquopy/**} files, and Chaquopy reaches all of it through a single context.
 * These tests cover the part that is pure Java — resolving the library paths and their order — so the
 * ordering rule is pinned without a device.
 *
 * <p>Order matters here in a way it does not for a single-library module. Measured from the APK,
 * {@code libcrypto_chaquopy} depends on {@code libcrypto_python}, {@code libssl_chaquopy} on
 * {@code libssl_python}, {@code libsqlite3_chaquopy} on {@code libsqlite3_python}, and
 * {@code libssl_python} on {@code libcrypto_python} — while Chaquopy's own bare-name sequence loads
 * several of those dependents first. Bare names survive that because the linker searches the
 * namespace's library paths; a module's directory is not on that path, so the manifest must declare a
 * topological order.
 */
public class RuntimePythonTest {

    private static final Gson GSON = new Gson();

    /** The order measured in S12, which is what the module must declare. */
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

    private static final String SHA =
            "ede6123bee76d965791548af01123316b29c1b18c6419ac392e2088df867987e";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static String manifestJson(String[] libraries) {
        StringBuilder loadOrder = new StringBuilder();
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < libraries.length; i++) {
            if (i > 0) {
                loadOrder.append(',');
                files.append(',');
            }
            String path = "lib/arm64-v8a/" + libraries[i];
            loadOrder.append('"').append(path).append('"');
            files.append("{\"path\":\"").append(path).append("\",\"size\":100,\"sha256\":\"")
                    .append(SHA).append("\"}");
        }
        // The entry point is the last library, as the packer writes it.
        String entry = "lib/arm64-v8a/" + libraries[libraries.length - 1];
        return "{"
                + "\"module\":\"python\","
                + "\"packageName\":\"com.silent.runtime.python\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"chaquopy-python310-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + SHA + "\","
                + "\"size\":100,"
                + "\"library\":\"" + entry + "\","
                + "\"loadOrder\":[" + loadOrder + "],"
                + "\"hostLibraries\":{},"
                + "\"files\":[" + files + "]}";
    }

    private static RuntimeManifest manifest(String[] libraries) {
        return GSON.fromJson(manifestJson(libraries), RuntimeManifest.class);
    }

    /** Materializes the module directory the way the platform lays out nativeLibraryDir. */
    private File moduleDir(String[] libraries) throws IOException {
        File dir = folder.newFolder("module");
        for (String name : libraries) {
            File file = new File(dir, name);
            Files.write(file.toPath(), name.getBytes(StandardCharsets.UTF_8));
        }
        return dir;
    }

    @Test
    public void resolvesEveryLibraryInTheDeclaredOrder() throws IOException {
        RuntimePython.Resolution resolution =
                RuntimePython.resolveLibraryPaths(moduleDir(TOPOLOGICAL), manifest(TOPOLOGICAL));

        assertTrue(resolution.getProblem(), resolution.isOk());
        List<String> paths = resolution.getPaths();
        assertEquals(TOPOLOGICAL.length, paths.size());
        for (int i = 0; i < TOPOLOGICAL.length; i++) {
            assertEquals("position " + i + " should be " + TOPOLOGICAL[i],
                    TOPOLOGICAL[i], new File(paths.get(i)).getName());
        }
    }

    /**
     * The resolved order is the manifest's, not a re-sort.
     *
     * <p>This is the property that makes the module work: the host must not reorder, because only the
     * manifest knows the dependency graph.
     */
    @Test
    public void preservesTheDeclaredOrderRatherThanSorting() throws IOException {
        String[] reversed = new String[TOPOLOGICAL.length];
        for (int i = 0; i < TOPOLOGICAL.length; i++) {
            reversed[i] = TOPOLOGICAL[TOPOLOGICAL.length - 1 - i];
        }

        RuntimePython.Resolution resolution =
                RuntimePython.resolveLibraryPaths(moduleDir(reversed), manifest(reversed));

        assertTrue(resolution.getProblem(), resolution.isOk());
        for (int i = 0; i < reversed.length; i++) {
            assertEquals(reversed[i], new File(resolution.getPaths().get(i)).getName());
        }
    }

    @Test
    public void failsWhenALibraryIsMissing() throws IOException {
        // Create every library except the last, so the failure is attributable to the missing one.
        String[] partial = new String[TOPOLOGICAL.length - 1];
        System.arraycopy(TOPOLOGICAL, 0, partial, 0, partial.length);

        RuntimePython.Resolution resolution =
                RuntimePython.resolveLibraryPaths(moduleDir(partial), manifest(TOPOLOGICAL));

        assertFalse(resolution.isOk());
        assertTrue(resolution.getProblem(), resolution.getProblem().contains("模块库不存在"));
        assertTrue(resolution.getPaths().isEmpty());
    }

    @Test
    public void failsWithoutALibraryDirectory() {
        RuntimePython.Resolution resolution =
                RuntimePython.resolveLibraryPaths(null, manifest(TOPOLOGICAL));

        assertFalse(resolution.isOk());
        assertTrue(resolution.getProblem(), resolution.getProblem().contains("目录不可用"));
    }

    @Test
    public void failsWithoutAManifest() throws IOException {
        RuntimePython.Resolution resolution =
                RuntimePython.resolveLibraryPaths(moduleDir(TOPOLOGICAL), null);

        assertFalse(resolution.isOk());
        assertTrue(resolution.getProblem(), resolution.getProblem().contains("清单不可用"));
    }

    /** A single-library module needs no explicit load order, as for the other modules. */
    @Test
    public void acceptsASingleLibraryWithoutALoadOrder() throws IOException {
        String json = "{"
                + "\"module\":\"python\","
                + "\"packageName\":\"com.silent.runtime.python\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"chaquopy-python310-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"sha256\":\"" + SHA + "\","
                + "\"size\":100,"
                + "\"library\":\"lib/arm64-v8a/libchaquopy_java.so\","
                + "\"hostLibraries\":{},"
                + "\"files\":[{\"path\":\"lib/arm64-v8a/libchaquopy_java.so\",\"size\":100,"
                + "\"sha256\":\"" + SHA + "\"}]}";

        RuntimePython.Resolution resolution = RuntimePython.resolveLibraryPaths(
                moduleDir(new String[]{"libchaquopy_java.so"}),
                GSON.fromJson(json, RuntimeManifest.class));

        assertTrue(resolution.getProblem(), resolution.isOk());
        assertEquals(1, resolution.getPaths().size());
    }

    @Test
    public void availabilityIsTrueWhenEitherSourceExists() {
        assertTrue("a module satisfies availability", RuntimePython.isAvailable(true, false));
        assertTrue("a bundled copy satisfies availability", RuntimePython.isAvailable(false, true));
        assertTrue("both sources satisfy availability", RuntimePython.isAvailable(true, true));
        assertFalse("neither source means unavailable", RuntimePython.isAvailable(false, false));
    }

    @Test
    public void moduleIsInstalledOnlyWhenItsDirectoryExists() throws IOException {
        File dir = folder.newFolder("installed");
        assertTrue(RuntimePython.isModuleInstalled(packageInfo(dir)));
        assertFalse(RuntimePython.isModuleInstalled(packageInfo(null)));
        assertFalse("a null platform must not be read as installed",
                RuntimePython.isModuleInstalled(null));
    }

    /**
     * The contract and package name are registered host-side.
     *
     * <p>A module whose contract the host does not recognise is rejected at load time, so the two
     * tables must agree with what the module ships.
     */
    @Test
    public void contractAndPackageNameAreRegistered() {
        assertEquals(RuntimePython.MODULE_PACKAGE,
                RuntimeCompatibility.packageName(RuntimePython.MODULE_ID));
        assertTrue(RuntimeCompatibility.isAccepted("chaquopy-python310-v1"));
    }

    /** Uninstalling clears the provider so the bundled behaviour is restored. */
    @Test
    public void uninstallClearsTheProvider() {
        RuntimePython.uninstall();
        assertNull(com.fongmi.chaquo.Platform.getRuntimeModule());
    }

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
}
