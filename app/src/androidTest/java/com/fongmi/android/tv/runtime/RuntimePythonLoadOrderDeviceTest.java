package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.util.Log;

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
 * Whether Chaquopy's native library load order survives an absolute-path load from a module.
 *
 * <p>This decides the shape of the Python module's contract. {@code chaquo/Platform.java} loads its
 * libraries by bare name in this order:
 *
 * <pre>
 * crypto_chaquopy, ssl_chaquopy, sqlite3_chaquopy, python3.10, chaquopy_java
 * </pre>
 *
 * <p>but the dependency graph measured from the APK is not consistent with it:
 *
 * <pre>
 * libcrypto_chaquopy.so  -&gt; libcrypto_python.so
 * libssl_chaquopy.so     -&gt; libssl_python.so
 * libsqlite3_chaquopy.so -&gt; libsqlite3_python.so
 * libssl_python.so       -&gt; libcrypto_python.so
 * libchaquopy_java.so    -&gt; libpython3.10.so
 * </pre>
 *
 * <p>{@code crypto_chaquopy} is loaded before {@code crypto_python}. With bare names this still
 * works because the linker falls back to searching the namespace's library paths and finds the
 * dependency in the same directory. Loading by absolute path removes that fallback: a
 * {@code DT_NEEDED} entry is resolved against libraries that are already loaded, and a module's
 * directory is not on any search path.
 *
 * <p><b>Why the assertion is about the mapping origin, not about success.</b> The host ships its own
 * copy of every one of these libraries, and the host's native library directory <em>is</em> on the
 * namespace search path. So loading the dependent library without preloading its dependency may
 * still succeed — by finding the <em>host's</em> copy. That success would be an artifact: in a real
 * module the host does not ship these libraries at all. The experiment therefore asserts where the
 * dependency was mapped from, which is the property that transfers to a module.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimePythonLoadOrderDeviceTest {

    private static final String TAG = "S12Order";

    /** Loaded first by Chaquopy, but it depends on a library loaded later. */
    private static final String DEPENDENT = "libcrypto_chaquopy.so";
    /** The dependency Chaquopy loads after its dependent. */
    private static final String DEPENDENCY = "libcrypto_python.so";

    private static final String STAGE_DIR = "s12-python-order";

    private static File hostLibDir() {
        return new File(App.get().getApplicationInfo().nativeLibraryDir);
    }

    private static File stage(String libName) throws IOException {
        File source = new File(hostLibDir(), libName);
        assumeTrue("host does not ship " + libName, source.isFile());

        File dir = new File(ApplicationProvider.getApplicationContext().getFilesDir(), STAGE_DIR);
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

    /**
     * Finding: the dependency is not satisfied from the module directory when it is loaded after its
     * dependent.
     *
     * <p>Two outcomes both support the conclusion, and the test reports which occurred:
     *
     * <ul>
     *   <li>the load fails outright — the dependency is simply unreachable;
     *   <li>the load succeeds but the dependency is mapped from the <em>host's</em> directory — the
     *       name was resolved through the search path, which a module cannot rely on.
     * </ul>
     *
     * <p>What must <em>not</em> happen is the dependency appearing from the staged directory: that
     * would mean the order does not matter and the module could mirror the current sequence.
     */
    @Test
    public void dependencyIsNotSatisfiedFromTheModuleDirectoryWhenLoadedLate() throws Exception {
        File dependency = stage(DEPENDENCY);
        File dependent = stage(DEPENDENT);
        String stagePath = dependency.getParentFile().getCanonicalPath();

        // Precondition: the dependency must not already be loaded, or the load below would be a
        // no-op and the experiment would prove nothing.
        assumeTrue("another test already loaded " + DEPENDENCY + " in this process; run this class "
                        + "alone to observe the finding",
                mappingOrigins(DEPENDENCY).isEmpty());

        String loadResult;
        try {
            System.load(dependent.getAbsolutePath());
            loadResult = "loaded";
        } catch (Throwable e) {
            loadResult = "failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        List<String> origins = mappingOrigins(DEPENDENCY);
        // Record what actually happened: a failed load and a fallback to the host's copy are both
        // acceptable outcomes, but they have different consequences for a real module, so the
        // evidence is logged rather than only asserted.
        Log.i(TAG, "late-load " + DEPENDENT + " = " + loadResult
                + "; " + DEPENDENCY + " origins = " + origins
                + "; hostDir = " + hostLibDir().getAbsolutePath());
        boolean fromStage = origins.contains(stagePath);
        assertTrue("the dependency was satisfied from the module directory even though it was loaded "
                        + "after its dependent (loadResult=" + loadResult + ", origins=" + origins
                        + "); the module could then mirror Chaquopy's current order and no "
                        + "topological sort would be needed — revisit that decision",
                !fromStage);
    }

    /**
     * Control: preloading the dependency makes the dependent loadable from the module directory.
     *
     * <p>This is what the module's {@code loadOrder} must do. It also confirms the finding above is
     * about ordering rather than about these libraries being unloadable by absolute path.
     */
    @Test
    public void preloadingTheDependencyMakesTheDependentLoadable() throws Exception {
        File dependency = stage(DEPENDENCY);
        File dependent = stage(DEPENDENT);
        String stagePath = dependency.getParentFile().getCanonicalPath();

        System.load(dependency.getAbsolutePath());
        assertEquals("the preloaded dependency should be the only mapping of " + DEPENDENCY,
                stagePath, mappingOrigins(DEPENDENCY).get(0));
        assertEquals("the dependency should be mapped from exactly one directory",
                1, mappingOrigins(DEPENDENCY).size());

        System.load(dependent.getAbsolutePath());
        assertTrue("the dependent library should be mapped",
                !mappingOrigins(DEPENDENT).isEmpty());
    }
}
