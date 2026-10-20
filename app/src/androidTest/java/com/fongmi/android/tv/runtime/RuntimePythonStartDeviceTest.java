package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.chaquo.Loader;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the Python runtime actually <em>starts</em> from the installed module.
 *
 * <p>Every earlier Python test checked a piece: that the manifest verifies, that the libraries are
 * present, that the payload can be read. None of them started the interpreter, so none of them would
 * have failed if Chaquopy could not boot from a module at all — for instance if the context it reads
 * assets through were wrong, or if an absolute-path load of the eight libraries could not satisfy
 * their {@code DT_NEEDED} entries.
 *
 * <p>{@code Loader}'s constructor is the production entry point and does the whole sequence:
 * {@code Python.start(Platform.create())}, which reads {@code chaquopy/build.json} through the
 * module's assets and loads the libraries by absolute path, then
 * {@code Python.getInstance().getModule("app")}, which executes the bundled Python sources. If this
 * constructor returns, the interpreter really is running out of the module.
 *
 * <p>This is deliberately the last thing to run rather than the first: it extracts ~12 MiB into the
 * host's files directory on first use, so it is slow, and it mutates process-wide state that the
 * other tests would then observe.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimePythonStartDeviceTest {

    private static boolean moduleInstalled() {
        return RuntimePython.isModuleInstalled(RuntimeAndroid.packageInfo());
    }

    /** Directories that {@code libName} is mapped from. */
    private static List<String> mappingOrigins(String libName) throws Exception {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        List<String> origins = new ArrayList<>();
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 6) continue;
            String path = fields[5];
            if (!path.endsWith("/" + libName)) continue;
            String dir = new File(path).getParentFile().getCanonicalPath();
            if (!origins.contains(dir)) origins.add(dir);
        }
        return origins;
    }

    /**
     * The production entry point starts the interpreter from the module.
     *
     * <p>Asserts the constructor returns and the {@code app} module is reachable, then reports the
     * interpreter version as evidence that real Python code ran rather than a stub being cached.
     */
    @Test
    public void pythonStartsFromTheModule() {
        assumeTrue("module " + RuntimePython.MODULE_PACKAGE + " is not installed", moduleInstalled());

        Loader loader = new Loader();
        assertNotNull("the production entry point should construct", loader);

        com.chaquo.python.Python python = com.chaquo.python.Python.getInstance();
        assertNotNull("Python should be started after constructing the loader", python);

        com.chaquo.python.PyObject sys = python.getModule("sys");
        String version = String.valueOf(sys.get("version"));
        System.out.println("S28PY started version=" + version);

        assertTrue("the interpreter should report a real version, got: " + version,
                version.contains("3.10"));
    }

    /**
     * The interpreter's own library is mapped from the module directory, not from the host.
     *
     * <p>Confirms the absolute-path load path was actually taken. The host no longer ships these
     * libraries, so a mapping can only have come from the module — but stating the origin keeps the
     * claim checkable and would catch a regression that reintroduced a bundled copy.
     */
    @Test
    public void interpreterLibraryIsMappedFromTheModule() throws Exception {
        assumeTrue("module " + RuntimePython.MODULE_PACKAGE + " is not installed", moduleInstalled());

        // Starting is what maps the libraries; a no-op if a previous test already started it.
        new Loader();

        File moduleDir = RuntimeAndroid.packageInfo()
                .nativeLibraryDir(RuntimePython.MODULE_PACKAGE);
        assertNotNull(moduleDir);

        List<String> origins = mappingOrigins("libpython3.10.so");
        System.out.println("S28PY libpython3.10.so origins=" + origins
                + " moduleDir=" + moduleDir.getCanonicalPath());

        assertTrue("libpython3.10.so should be mapped", !origins.isEmpty());
        assertTrue("the interpreter should be mapped from the module directory; origins=" + origins,
                origins.contains(moduleDir.getCanonicalPath()));
    }

    /**
     * A Python spider can be built through the bridge.
     *
     * <p>This exercises the surface the application actually uses — {@code Loader.spider} — rather
     * than only the interpreter's own startup. {@code spider} accepts inline source when the argument
     * is neither an {@code assets://} path nor an {@code http} URL, so an inline class keeps this
     * independent of any particular site script; what matters is that the bridge hands the source to
     * Python and gets an object back.
     */
    @Test
    public void aSpiderCanBeBuiltThroughTheBridge() {
        assumeTrue("module " + RuntimePython.MODULE_PACKAGE + " is not installed", moduleInstalled());

        Loader loader = new Loader();

        String source = "class Spider:\n"
                + "    def init(self, extend):\n"
                + "        self.extend = extend\n";

        com.fongmi.chaquo.Spider spider = loader.spider(source);
        assertNotNull("the bridge should return a spider object", spider);
        System.out.println("S28PY spider built=" + spider.getClass().getName());
    }
}
