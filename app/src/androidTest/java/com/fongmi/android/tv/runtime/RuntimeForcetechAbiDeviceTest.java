package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Whether the forcetech libraries shipped in an arm64 build can actually be loaded.
 *
 * <p>This revisits a boundary that was closed earlier. The protocol extractors were judged
 * non-modularisable because vendor code loads them by bare name, and that conclusion stands. But
 * measuring the APK's ABI composition turned up a different problem with the same libraries: the
 * forcetech AAR ships its native code in two places with different consequences.
 *
 * <ul>
 *   <li>{@code jni/armeabi-v7a/…} — subject to the build's {@code abiFilters}, so an arm64 build
 *       drops it.
 *   <li>{@code assets/libmitv.so} — an <em>asset</em>, so {@code abiFilters} does not apply and it
 *       is packaged into the arm64 APK regardless of its architecture.
 * </ul>
 *
 * <p>The measured architecture of {@code assets/libmitv.so} is 32-bit ARM (ELF class 1, machine
 * 0x28). A 64-bit process cannot load a 32-bit library, and a library can only be loaded into a
 * process of its own bitness. So on an arm64 build this asset appears to be dead weight: the code
 * that would use it, {@code com.gsoft.mitv.MainActivity}, reads it through the host's
 * {@code Asset.open} and then calls {@code System.loadLibrary("mitv")} — which cannot succeed for a
 * 32-bit library inside a 64-bit process.
 *
 * <p>If that holds, the asset is removable without losing any capability, and it is larger than
 * several of the modules still on the candidate list. The test states the precondition explicitly
 * (the process is 64-bit) so the finding cannot be misread as applying to a 32-bit build, where the
 * asset may well be live.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeForcetechAbiDeviceTest {

    private static final String ASSET_LIBRARY = "libmitv.so";

    /** The asset as packaged in the APK, read through the host's asset manager. */
    private static byte[] readAsset() throws IOException {
        try (InputStream in = com.fongmi.android.tv.App.get().getAssets()
                .open(ASSET_LIBRARY, AssetManager.ACCESS_STREAMING)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toByteArray();
        }
    }

    /**
     * Whether the asset is present in this build.
     *
     * <p>The arm64 variant no longer packages it, so the measurement below can only be taken where it
     * still exists. It is skipped rather than deleted because it is the evidence for the arm64
     * removal, and can be re-run against a v7a build or one made with the exclusion disabled.
     */
    private static boolean assetIsPackaged() {
        try (InputStream in = com.fongmi.android.tv.App.get().getAssets()
                .open(ASSET_LIBRARY, AssetManager.ACCESS_STREAMING)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** ELF class and machine type, or a description of why it is not an ELF file. */
    private static String describeElf(byte[] data) {
        if (data.length < 20 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            return "not-an-elf";
        }
        int elfClass = data[4] & 0xff;
        int machine = (data[0x12] & 0xff) | ((data[0x13] & 0xff) << 8);
        String className = elfClass == 2 ? "ELF64" : elfClass == 1 ? "ELF32" : "class" + elfClass;
        String machineName = machine == 0xB7 ? "AArch64"
                : machine == 0x28 ? "ARM"
                : "machine-0x" + Integer.toHexString(machine);
        return className + "/" + machineName;
    }

    /** Whether this process is 64-bit, which decides whether a 32-bit library can be loaded. */
    private static boolean processIs64Bit() {
        return android.os.Process.is64Bit();
    }

    /**
     * The precondition: this build runs as a 64-bit process.
     *
     * <p>Recorded as its own test so the finding below is only read in the context it applies to.
     */
    @Test
    public void buildRunsAsA64BitProcess() {
        assertTrue("the arm64 build should run as a 64-bit process; if this fails the finding below "
                        + "does not apply",
                processIs64Bit());
    }

    /**
     * Finding: the packaged forcetech asset is a 32-bit ARM library, so a 64-bit process cannot load
     * it.
     *
     * <p>The assertion is about the mismatch rather than about the file existing, because that is
     * what makes the asset removable: the library is present but unusable in this build.
     */
    @Test
    public void packagedForcetechAssetIsIncompatibleWithA64BitProcess() throws Exception {
        assumeTrue("this build is 32-bit, so the asset may be live and the finding does not apply",
                processIs64Bit());
        assumeTrue("the arm64 build no longer packages " + ASSET_LIBRARY + "; run this against a "
                        + "build that does to re-observe the measurement",
                assetIsPackaged());

        byte[] data = readAsset();
        String described = describeElf(data);
        System.out.println("S17MITV asset=" + ASSET_LIBRARY + " size=" + data.length
                + " elf=" + described + " process64=" + processIs64Bit());

        assertEquals("the packaged asset should be a 32-bit ARM library; if it is now 64-bit the "
                        + "asset is live and must not be removed",
                "ELF32/ARM", described);
    }

    /**
     * The host's own native library directory holds only 64-bit libraries, so the bare-name lookup
     * the vendor code performs cannot be satisfied by a 32-bit asset either.
     *
     * <p>This is what closes the argument: the vendor path copies the asset into the cache directory
     * and then asks for {@code "mitv"} by name, and neither the cache directory nor the native
     * library directory can supply a 32-bit library to a 64-bit process.
     */
    @Test
    public void hostNativeLibraryDirectoryHoldsNo32BitMitv() {
        assumeTrue("this build is 32-bit, so the asset may be live and the finding does not apply",
                processIs64Bit());

        File dir = new File(com.fongmi.android.tv.App.get().getApplicationInfo().nativeLibraryDir);
        File mitv = new File(dir, ASSET_LIBRARY);
        assertTrue("the arm64 build should not ship a native-library copy of " + ASSET_LIBRARY
                        + "; if it does, the asset and the jni copy need to be considered together",
                !mitv.isFile());
    }
}
