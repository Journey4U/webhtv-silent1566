package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a dependency chain survives when the depended-on library has no {@code DT_SONAME}.
 *
 * <p>This decides whether the Python runtime (Chaquopy, ~21 MB) can be modularised. Moving it into a
 * module also moves {@code libpython3.10.so}, and that library is unusual: it carries <em>no</em>
 * {@code DT_SONAME}, while 13 other libraries depend on it by bare name
 * ({@code DT_NEEDED libpython3.10.so}) — including {@code libchaquopy_java.so} and all 12
 * {@code bootstrap-native} extension modules.
 *
 * <p>When a library is loaded by absolute path, the linker resolves its {@code DT_NEEDED} entries
 * against libraries that are already loaded, and otherwise by searching the namespace's library
 * paths. A module's directory is in neither. bionic's own comment warns that this is exactly where a
 * missing soname bites:
 * <blockquote>
 * Before API 23, the linker used the basename in place of DT_SONAME. After we switched, apps with
 * libraries without a DT_SONAME stopped working: they could no longer be found by DT_NEEDED from
 * another library.
 * </blockquote>
 *
 * <p><b>Design.</b> The host's own copies of these libraries are copied into two <em>different</em>
 * directories under the app's files directory — neither of which is on the linker's search path.
 * Using two directories mirrors the real layout, where an extension module is extracted to
 * {@code filesDir/chaquopy/bootstrap-native/<abi>/} while the interpreter lives in the module's
 * native library directory. The experiment therefore does not depend on the two files being
 * siblings.
 *
 * <p>The soname-carrying pair is the control that makes the result attributable: it has the same
 * shape (preload one library by absolute path, then load another that depends on it by name) but the
 * depended-on library <em>does</em> carry a soname. If the control passes and the soname-less case
 * fails, the cause is the missing soname rather than absolute-path loading in general.
 *
 * <p>Both cases assert that the dependency is mapped from <em>one</em> directory. That matters
 * because the host ships its own copy of {@code libpython3.10.so}: if a second mapping from the host
 * directory appeared, the name would have been resolved through the search path rather than by
 * reusing the preloaded copy — and in a real module the host does not ship these libraries at all.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeNoSonameDependencyDeviceTest {

    /** Carries no DT_SONAME, and 13 libraries depend on it by bare name. */
    private static final String NO_SONAME_LIB = "libpython3.10.so";
    /** Depends on {@link #NO_SONAME_LIB} by bare name. */
    private static final String NO_SONAME_DEPENDENT = "libchaquopy_java.so";

    /** Carries a DT_SONAME. */
    private static final String SONAME_LIB = "libcrypto_python.so";
    /** Depends on {@link #SONAME_LIB} by bare name. */
    private static final String SONAME_DEPENDENT = "libssl_python.so";

    private static File hostLibDir() {
        return new File(App.get().getApplicationInfo().nativeLibraryDir);
    }

    /**
     * Copies a host-shipped library into a files-directory subdirectory that is not on the linker's
     * search path.
     */
    private static File stage(String dirName, String libName) throws IOException {
        File source = new File(hostLibDir(), libName);
        assumeTrue("host does not ship " + libName, source.isFile());

        File dir = new File(ApplicationProvider.getApplicationContext().getFilesDir(), dirName);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File target = new File(dir, libName);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
        return target;
    }

    /** Distinct directories that {@code libName} is currently mapped from. */
    private static List<String> mappingOrigins(String libName) throws IOException {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        List<String> origins = new ArrayList<>();
        for (String line : maps.split("\n")) {
            // Format: address perms offset dev inode pathname
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

    /** The single directory {@code libName} is mapped from, failing loudly if it is not exactly one. */
    private static String soleOrigin(String libName) throws IOException {
        List<String> origins = mappingOrigins(libName);
        assertEquals(libName + " should be mapped from exactly one directory; origins=" + origins,
                1, origins.size());
        return origins.get(0);
    }

    /**
     * Control: a preloaded library <em>with</em> a soname satisfies a later absolute-path load's
     * {@code DT_NEEDED}, and is not loaded a second time.
     */
    @Test
    public void preloadedLibraryWithSonameSatisfiesDependency() throws Exception {
        File lib = stage("s10-soname-lib", SONAME_LIB);
        File dependent = stage("s10-soname-dependent", SONAME_DEPENDENT);

        System.load(lib.getAbsolutePath());
        assertEquals("the preloaded copy should be the only mapping of " + SONAME_LIB,
                lib.getParentFile().getCanonicalPath(), soleOrigin(SONAME_LIB));

        System.load(dependent.getAbsolutePath());
        assertEquals("loading the dependent library must reuse the preloaded copy rather than "
                        + "mapping a second one",
                lib.getParentFile().getCanonicalPath(), soleOrigin(SONAME_LIB));
    }

    /**
     * Finding: a preloaded library <em>without</em> a soname also satisfies a later absolute-path
     * load's {@code DT_NEEDED}, and is likewise not loaded a second time.
     *
     * <p>This is what makes the Python runtime modularisable: the host can preload the interpreter
     * from the module directory and the extension modules, extracted elsewhere, will bind to it.
     */
    @Test
    public void preloadedLibraryWithoutSonameSatisfiesDependency() throws Exception {
        File lib = stage("s10-nosoname-lib", NO_SONAME_LIB);
        File dependent = stage("s10-nosoname-dependent", NO_SONAME_DEPENDENT);

        System.load(lib.getAbsolutePath());
        assertEquals("the preloaded copy should be the only mapping of " + NO_SONAME_LIB,
                lib.getParentFile().getCanonicalPath(), soleOrigin(NO_SONAME_LIB));

        System.load(dependent.getAbsolutePath());
        assertEquals("loading the dependent library must reuse the preloaded soname-less copy; a "
                        + "second mapping from the host directory would mean the name was resolved "
                        + "through the search path, which would not work for a module",
                lib.getParentFile().getCanonicalPath(), soleOrigin(NO_SONAME_LIB));
        assertTrue("the dependent library should be mapped",
                !mappingOrigins(NO_SONAME_DEPENDENT).isEmpty());
    }
}
