package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import tv.danmaku.ijk.media.player.IjkLibLoader;

/**
 * IJK module resolution.
 *
 * <p>IJK differs from MPV: it calls {@code System.loadLibrary("ijkffmpeg")} by bare name, and that
 * searches only the host's own directory. The module's libraries are elsewhere, so the host
 * installs a resolver that maps the bare name to the module's file. These tests pin that mapping
 * and the fact that it degrades to the platform lookup when no module is present.
 */
public class RuntimeIjkTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String[] LIBS = {"libijkffmpeg.so", "libijksdl.so", "libijkplayer.so"};

    private static final class MemoryStorage implements RuntimeStore.Storage {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }
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

    private File libDir() throws IOException {
        File dir = folder.newFolder();
        for (String name : LIBS) {
            Files.write(new File(dir, name).toPath(), ("payload-" + name).getBytes(StandardCharsets.UTF_8));
        }
        return dir;
    }

    @Test
    public void availabilityIsSatisfiedByEitherSource() {
        assertTrue(RuntimeIjk.isAvailable(true, false));
        assertTrue(RuntimeIjk.isAvailable(false, true));
        // The post-removal state for a user who has not installed the module.
        assertFalse(RuntimeIjk.isAvailable(false, false));
        assertTrue(RuntimeIjk.isAvailable(true, true));
    }

    @Test
    public void moduleInstalledIsDecidedByThePlatformNotTheRegistry() {
        assertTrue(RuntimeIjk.isModuleInstalled(packageInfo(new File("/data/app/com.silent.runtime.ijk/lib/arm64"))));
        assertFalse(RuntimeIjk.isModuleInstalled(packageInfo(null)));
        assertFalse(RuntimeIjk.isModuleInstalled(null));
    }

    @Test
    public void platformProbeFailureMeansNotInstalled() {
        RuntimePlatform.PackageInfo throwing = new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                throw new IllegalStateException("package manager unavailable");
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

        assertFalse(RuntimeIjk.isModuleInstalled(throwing));
    }

    /**
     * The core contract: a bare name IJK asks for must resolve to the module's file.
     *
     * <p>Without this the module's libraries are unreachable, because {@code System.loadLibrary}
     * only searches the host's own directory.
     */
    @Test
    public void resolverMapsBareNamesToTheModulesFiles() throws IOException {
        File dir = libDir();
        RuntimeIjk.Status status = RuntimeIjk.install(packageInfo(dir));
        try {
            assertTrue(status.getProblem(), status.isOk());
            assertEquals(dir.getAbsolutePath(), status.getLibraryDir().getAbsolutePath());

            IjkLibLoader.Resolver resolver = IjkLibLoader.getResolver();
            assertNotNull("a resolver should be installed", resolver);

            for (String name : LIBS) {
                String bare = name.startsWith("lib") ? name.substring(3, name.length() - 3) : name;
                File resolved = resolver.resolve(bare);
                assertNotNull("bare name should resolve: " + bare, resolved);
                assertEquals(new File(dir, name).getAbsolutePath(), resolved.getAbsolutePath());
                assertTrue(resolved.isFile());
            }
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    @Test
    public void resolverReturnsNullWithoutAModule() {
        RuntimeIjk.Status status = RuntimeIjk.install(packageInfo(null));
        try {
            assertFalse(status.isOk());
            assertTrue(status.getProblem(), status.getProblem().contains("未安装"));

            // A null answer makes IjkLibLoader fall back to System.loadLibrary, which is the
            // correct behaviour for a build that still bundles the libraries.
            assertNull(IjkLibLoader.getResolver().resolve("ijkffmpeg"));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    @Test
    public void resolverReturnsNullForUnknownNames() throws IOException {
        File dir = libDir();
        RuntimeIjk.install(packageInfo(dir));
        try {
            IjkLibLoader.Resolver resolver = IjkLibLoader.getResolver();
            assertNull(resolver.resolve("not-a-library"));
            assertNull(resolver.resolve(""));
            assertNull(resolver.resolve(null));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    /** The resolver must re-check on each lookup, so install order does not matter. */
    @Test
    public void resolverPicksUpAModuleInstalledAfterStartup() throws IOException {
        File dir = libDir();
        // Install the resolver while no module is present.
        RuntimeIjk.install(packageInfo(null));
        try {
            assertNull(IjkLibLoader.getResolver().resolve("ijkffmpeg"));

            // The module appears afterwards; the same resolver must now find it.
            RuntimeIjk.install(packageInfo(dir));
            assertNotNull(IjkLibLoader.getResolver().resolve("ijkffmpeg"));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    @Test
    public void uninstallRestoresThePlatformLookup() throws IOException {
        RuntimeIjk.install(packageInfo(libDir()));
        assertNotNull(IjkLibLoader.getResolver());

        RuntimeIjk.uninstall();

        assertNull("uninstall must clear the resolver so System.loadLibrary is used again",
                IjkLibLoader.getResolver());
    }

    /** IJK asks for bare names; the manifest stores platform-conventional file names. */
    @Test
    public void matchesBareNameBridgesTheTwoNamingConventions() {
        assertTrue(RuntimeIjk.matchesBareName("lib/arm64-v8a/libijkffmpeg.so", "ijkffmpeg"));
        assertTrue(RuntimeIjk.matchesBareName("libijkplayer.so", "ijkplayer"));
        assertTrue(RuntimeIjk.matchesBareName("ijksdl.so", "ijksdl"));

        assertFalse(RuntimeIjk.matchesBareName("lib/arm64-v8a/libijkffmpeg.so", "ijksdl"));
        assertFalse(RuntimeIjk.matchesBareName("", "ijkffmpeg"));
        assertFalse(RuntimeIjk.matchesBareName("libijkffmpeg.so", ""));
        assertFalse(RuntimeIjk.matchesBareName(null, "ijkffmpeg"));
    }

    /** The declared library list must be the three IJK actually asks for. */
    @Test
    public void libraryListMatchesWhatIjkRequests() {
        assertEquals(3, RuntimeIjk.LIBRARIES.size());
        assertTrue(RuntimeIjk.LIBRARIES.contains("ijkffmpeg"));
        assertTrue(RuntimeIjk.LIBRARIES.contains("ijksdl"));
        assertTrue(RuntimeIjk.LIBRARIES.contains("ijkplayer"));
    }
}
