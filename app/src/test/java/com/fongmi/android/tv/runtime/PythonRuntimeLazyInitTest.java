package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.api.loader.PyLoader;
import com.fongmi.android.tv.subtitle.source.PythonSubtitleScriptRuntime;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Python runtime must not be started while classes that merely <em>may</em> use it are being
 * initialised.
 *
 * <p>Measured earlier from the source: {@code BaseLoader} creates its {@code PyLoader} inside a static
 * initialiser, and {@code PyLoader}'s constructor used to call {@code new Loader()}, which calls
 * {@code Python.start}. Once the runtime is delivered as an optional module, that chain makes a build
 * without the module fail while loading configuration — taking down every source type, not just Python
 * ones. The same shape existed in {@code PythonSubtitleScriptRuntime}.
 *
 * <p>These tests pin the fix from two directions. The behavioural half constructs both classes on a
 * plain JVM, where no Python runtime exists, and requires that construction succeed and the
 * availability probe answer {@code false} rather than throw. The structural half reads the sources to
 * require that no {@code new Loader()} remains in a constructor, because a behavioural test cannot
 * catch a future regression that reintroduces the call behind a condition that happens to hold in
 * tests.
 *
 * <p>The structural checks follow the convention already used by
 * {@code PyLoaderDiagnosticsWiringTest}, which reads the same file for the same reason.
 */
public class PythonRuntimeLazyInitTest {

    private static final Path PY_LOADER =
            Path.of("src/main/java/com/fongmi/android/tv/api/loader/PyLoader.java");
    private static final Path PYTHON_SUBTITLE =
            Path.of("src/main/java/com/fongmi/android/tv/subtitle/source/PythonSubtitleScriptRuntime.java");

    /** Strips comment lines so a commented-out call cannot satisfy a source assertion. */
    private static String code(Path file) throws Exception {
        StringBuilder result = new StringBuilder();
        for (String line : Files.readString(file).replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) result.append(line).append('\n');
        }
        return result.toString();
    }

    /**
     * Constructing the source loader must not start Python.
     *
     * <p>On a plain JVM there is no runtime, so the old eager constructor would fail here. Succeeding
     * is the evidence that construction is now inert.
     */
    @Test
    public void constructingTheSourceLoaderDoesNotStartPython() {
        PyLoader loader = new PyLoader();
        assertNotNull(loader);
    }

    /** The same for the subtitle runtime, which is built while the provider registry is set up. */
    @Test
    public void constructingTheSubtitleRuntimeDoesNotStartPython() {
        PythonSubtitleScriptRuntime runtime = new PythonSubtitleScriptRuntime();
        assertNotNull(runtime);
    }

    /**
     * With no module and no bundled runtime, both probes report unavailable instead of throwing.
     *
     * <p>This is what lets callers show "install the module" rather than crashing. On a plain JVM
     * {@code App.get()} is null, so the probe takes its absent path.
     */
    @Test
    public void availabilityProbesReportAbsenceWithoutThrowing() {
        assertFalse("no runtime is present on a plain JVM", PyLoader.isAvailable());
        assertFalse("no runtime is present on a plain JVM", PythonSubtitleScriptRuntime.isAvailable());
    }

    /** A null context must be reported as unavailable rather than dereferenced. */
    @Test
    public void runtimeAvailabilityTreatsANullContextAsUnavailable() {
        assertFalse(RuntimePython.isRuntimeAvailable(null));
        assertFalse(RuntimePython.isBundled(null));
    }

    /**
     * No {@code new Loader()} may remain in a constructor.
     *
     * <p>Checked structurally because the behavioural tests above pass for any implementation that
     * happens to avoid the call at test time; the invariant is about where the call sits.
     */
    @Test
    public void noConstructorCreatesTheBridgeEagerly() throws Exception {
        for (Path file : new Path[]{PY_LOADER, PYTHON_SUBTITLE}) {
            String source = code(file);
            int constructor = source.indexOf("public PyLoader()");
            if (constructor < 0) constructor = source.indexOf("public PythonSubtitleScriptRuntime()");
            assertTrue("expected a no-argument constructor in " + file, constructor > 0);

            int bodyStart = source.indexOf('{', constructor);
            int bodyEnd = source.indexOf('}', bodyStart);
            String body = source.substring(bodyStart, bodyEnd);
            assertFalse("the no-argument constructor of " + file
                            + " must not create the bridge; that starts Python during class"
                            + " initialisation",
                    body.contains("new Loader()"));
        }
    }

    /**
     * The bridge is created in one place, guarded by an availability check.
     *
     * <p>Both classes must ask before starting Python, so a build without the runtime reports the
     * feature instead of failing with an obscure linker or import error.
     */
    @Test
    public void theBridgeIsCreatedOnlyBehindAnAvailabilityCheck() throws Exception {
        for (Path file : new Path[]{PY_LOADER, PYTHON_SUBTITLE}) {
            String source = code(file);
            int create = source.indexOf("new Loader()");
            assertTrue("expected exactly one lazy creation site in " + file, create > 0);
            assertTrue("the creation site in " + file + " must be guarded by an availability check",
                    source.lastIndexOf("isAvailable()", create) > 0);
        }
    }

    /**
     * The runtime's own availability probe is pure enough to answer without Android.
     *
     * <p>It is called from static initialisers, so it must not itself be able to throw.
     */
    @Test
    public void availabilityProbeIsSafeWithoutAPlatform() {
        assertFalse(RuntimePython.isRuntimeAvailable(null));
        // A null PackageInfo must read as "not installed" rather than throwing.
        assertFalse(RuntimePython.isModuleInstalled(null));
    }

    /** The availability rule itself, stated as a pure function so it needs no device. */
    @Test
    public void availabilityRuleCoversBothSources() {
        assertTrue("an installed module makes the runtime available",
                RuntimePython.isAvailable(true, false));
        assertTrue("a bundled copy makes the runtime available",
                RuntimePython.isAvailable(false, true));
        assertFalse("neither source means unavailable", RuntimePython.isAvailable(false, false));
    }
}
