package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Whether the forcetech protocol extractor can work at all in an arm64 build.
 *
 * <p>An earlier measurement found that the 3.7 MB {@code assets/libmitv.so} is never loaded. This
 * test asks the larger question that finding implies: if the asset is unusable, is the extractor
 * that depends on it usable either?
 *
 * <p>The forcetech AAR ships its native code only under {@code jni/armeabi-v7a/}, so the arm64
 * flavor's {@code abiFilters "arm64-v8a"} drops all of it. The extractor reaches its libraries
 * through a bare-name lookup: {@code Force} binds {@code P2PService}, whose {@code onBind} passes the
 * scheme through to {@code com.forcetech.android.ForceTV.start(String lib, int port)}, which calls
 * {@code System.loadLibrary(lib)}. With no {@code libp2p.so}, {@code libp3p.so} or {@code libmitv.so}
 * anywhere in the arm64 APK, that lookup cannot succeed — and the asset copy does not help, because
 * the cache directory is not on the linker's search path and the asset is 32-bit besides.
 *
 * <p>Two consequences follow, and they are different in kind:
 *
 * <ul>
 *   <li>The 3.7 MB asset is removable with no loss of capability in an arm64 build.
 *   <li>The extractor is already non-functional there, which is a user-visible issue independent of
 *       the asset: a {@code p2p://} or {@code mitv://} source would fail rather than being offered.
 * </ul>
 *
 * <p>The test asserts only the first, because that is what the removal depends on. The second is
 * recorded as an observation, not acted on here.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeForcetechExtractorAvailabilityDeviceTest {

    /** Bare names the vendor path asks for, one per service. */
    private static final String[] BARE_NAMES = {"p2p", "p3p", "mitv"};
    /** The corresponding file names the vendor path expects to load. */
    private static final String[] LIBRARY_NAMES = {"libp2p.so", "libp3p.so", "libmitv.so"};

    private static File nativeLibraryDir() {
        return new File(App.get().getApplicationInfo().nativeLibraryDir);
    }

    /**
     * Finding: none of the forcetech libraries is present in the arm64 build's native library
     * directory.
     *
     * <p>This is the structural reason the extractor cannot work there: the only copies the AAR
     * provides are armeabi-v7a, and the flavor filter removes them.
     */
    @Test
    public void forcetechLibrariesAreAbsentFromTheArm64NativeLibraryDirectory() {
        assumeTrue("this build is 32-bit, where the libraries are present", android.os.Process.is64Bit());

        File dir = nativeLibraryDir();
        StringBuilder present = new StringBuilder();
        for (String name : LIBRARY_NAMES) {
            if (new File(dir, name).isFile()) {
                present.append(name).append(' ');
            }
        }
        System.out.println("S19FORCE nativeLibraryDir=" + dir + " present=[" + present.toString().trim() + "]");

        assertTrue("the arm64 build should not ship the forcetech libraries; found: " + present,
                present.length() == 0);
    }

    /**
     * Finding: the bare-name lookups the vendor code performs all fail.
     *
     * <p>This is the behavioural counterpart to the structural finding above, and it is what makes
     * the asset removable: the code path that would consume it cannot reach its libraries by any
     * route.
     *
     * <p>Each lookup is attempted independently and the results are reported together, so a partial
     * result is visible rather than masked by the first failure.
     */
    @Test
    public void bareNameLookupsForForcetechLibrariesFail() {
        assumeTrue("this build is 32-bit, where the libraries are present", android.os.Process.is64Bit());

        StringBuilder report = new StringBuilder();
        int failures = 0;
        for (String name : BARE_NAMES) {
            String outcome;
            try {
                System.loadLibrary(name);
                outcome = "loaded";
            } catch (Throwable e) {
                outcome = e.getClass().getSimpleName();
                failures++;
            }
            report.append(name).append('=').append(outcome).append(' ');
        }
        System.out.println("S19FORCE bareNameLookups " + report.toString().trim());

        assertTrue("every forcetech bare-name lookup should fail in an arm64 build; got: " + report,
                failures == BARE_NAMES.length);
    }

    /**
     * The asset is still packaged, so its removal is a real change rather than a no-op.
     *
     * <p>Without this, the tests above could pass simply because the asset was already gone.
     */
    @Test
    public void theAssetIsStillPackaged() throws Exception {
        boolean exists;
        try (java.io.InputStream in = App.get().getAssets()
                .open("libmitv.so", android.content.res.AssetManager.ACCESS_STREAMING)) {
            exists = in != null;
        } catch (java.io.IOException e) {
            exists = false;
        }
        assertTrue("the asset should still be packaged, otherwise there is nothing to remove", exists);
    }
}
