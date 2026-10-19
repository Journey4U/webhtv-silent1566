package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Whether the copy of {@code libmitv.so} that the forcetech service makes is ever used.
 *
 * <p>The vendor service {@code com.gsoft.mitv.MainActivity} does two things in its constructor:
 * {@code checkLibrary()} copies {@code assets/libmitv.so} into {@code getCacheDir()}, and then
 * {@code System.loadLibrary("mitv")} is called. This test reproduces that sequence exactly and
 * observes what the linker does with it.
 *
 * <p>Those two steps do not connect. {@code System.loadLibrary} resolves a bare name through the
 * class loader's native library directories — the app's {@code nativeLibraryDir} and the system
 * paths — and the cache directory is not among them. So the copy is not what the bare-name lookup
 * loads. In an arm64 build the lookup fails outright, because the flavor's {@code abiFilters}
 * removes every forcetech library; in a v7a build it succeeds from
 * {@code lib/armeabi-v7a/libmitv.so} instead.
 *
 * <p><b>Correction to an earlier reading of this evidence.</b> The measurement above was first taken
 * to mean the asset is dead weight in both builds. That was wrong, and the mistake was stopping at
 * the bare-name lookup. The two files that share the name {@code libmitv.so} are different
 * libraries: the 54 KB {@code jni/armeabi-v7a/libmitv.so} is a <em>loader</em> that reads
 * {@code <cacheDir>/libmitv.so} and calls {@code System.load} on it, while the 3.7 MB asset is the
 * engine it loads (soname {@code libforcetv.so}, exporting
 * {@code Java_com_forcetech_android_ForceTV_start/stop} — the very natives
 * {@code com.forcetech.android.ForceTV} declares). So in a v7a build the asset is live: the loader
 * is present and the asset is what it loads. Only arm64 can drop it, because there the loader is
 * filtered out by ABI and nothing can reach the engine.
 *
 * <p>An independent arm64-specific reason the asset cannot be used there: it is 32-bit ARM while the
 * process is 64-bit.
 *
 * <p>These measurements are skipped where the asset is no longer packaged, which is now the case for
 * arm64. They are kept because they are the evidence for the arm64 removal, and can be re-run against
 * a v7a build or a build made with the exclusion disabled.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeForcetechCacheCopyDeviceTest {

    private static final String ASSET_LIBRARY = "libmitv.so";
    private static final String BARE_NAME = "mitv";

    /**
     * Whether the asset is present in this build.
     *
     * <p>The arm64 variant no longer packages it, so the measurements below can only be taken where
     * it still exists. They are skipped rather than deleted because they remain the evidence for why
     * the asset was removable from arm64, and they can be re-run against a v7a build or a build made
     * with the exclusion disabled.
     */
    private static boolean assetIsPackaged() {
        try (InputStream in = com.fongmi.android.tv.App.get().getAssets()
                .open(ASSET_LIBRARY, AssetManager.ACCESS_STREAMING)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

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

    /** Reproduces {@code MainActivity.checkLibrary()}: copy the asset into the cache directory. */
    private static File copyAssetToCache() throws IOException {
        File target = new File(com.fongmi.android.tv.App.get().getCacheDir(), ASSET_LIBRARY);
        try (InputStream in = com.fongmi.android.tv.App.get().getAssets()
                .open(ASSET_LIBRARY, AssetManager.ACCESS_STREAMING);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
        return target;
    }

    /** Whether {@code libName} is mapped from {@code dir}. */
    private static boolean mappedFrom(String libName, File dir) throws IOException {
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")), StandardCharsets.UTF_8);
        String canonical = dir.getCanonicalPath();
        for (String line : maps.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 6) continue;
            String path = fields[5];
            if (!path.endsWith("/" + libName)) continue;
            if (new File(path).getParentFile().getCanonicalPath().equals(canonical)) return true;
        }
        return false;
    }

    /**
     * Finding: the cache copy is never loaded, so it is dead weight.
     *
     * <p>The sequence is the vendor's own: copy the asset into the cache directory, then ask for the
     * bare name. The assertion is that the copy is not what gets mapped — either the lookup fails
     * outright, or it is served from somewhere else. What must not happen is the cache copy being
     * mapped, because that would mean the copy has a purpose and the asset must stay.
     */
    @Test
    public void cacheCopyIsNotLoadedByTheBareNameLookup() throws Exception {
        assumeTrue("the arm64 build no longer packages " + ASSET_LIBRARY + "; run this against a "
                        + "build that does to re-observe the measurement",
                assetIsPackaged());

        File cacheCopy = copyAssetToCache();
        assertTrue("the reproduction should have written the cache copy", cacheCopy.isFile());

        String outcome;
        try {
            System.loadLibrary(BARE_NAME);
            outcome = "loaded";
        } catch (Throwable e) {
            outcome = "failed: " + e.getClass().getSimpleName();
        }

        boolean usedCacheCopy = mappedFrom(ASSET_LIBRARY, cacheCopy.getParentFile());
        System.out.println("S17CACHE bareName=" + BARE_NAME + " outcome=" + outcome
                + " cacheCopyMapped=" + usedCacheCopy
                + " cacheCopySize=" + cacheCopy.length());

        assertTrue("the cache copy must not be what the bare-name lookup loads; if it is, the copy "
                        + "has a purpose and the asset cannot be removed",
                !usedCacheCopy);
    }

    /**
     * The asset's own architecture, recorded alongside the finding.
     *
     * <p>In an arm64 build this is 32-bit ARM while the process is 64-bit, which independently makes
     * the asset unusable there.
     */
    @Test
    public void assetArchitectureIsRecorded() throws Exception {
        assumeTrue("the arm64 build no longer packages " + ASSET_LIBRARY,
                assetIsPackaged());

        byte[] data = readAsset();
        int elfClass = data[4] & 0xff;
        int machine = (data[0x12] & 0xff) | ((data[0x13] & 0xff) << 8);
        System.out.println("S17CACHE asset=" + ASSET_LIBRARY + " size=" + data.length
                + " elfClass=" + elfClass + " machine=0x" + Integer.toHexString(machine)
                + " process64=" + android.os.Process.is64Bit());

        assertTrue("the asset should be an ELF file", data[0] == 0x7f && data[1] == 'E');
    }

    /**
     * A library placed in the cache directory is not visible to a bare-name lookup, stated directly.
     *
     * <p>This is the mechanism, isolated from the vendor code: the cache directory is not on the
     * linker's search path, so nothing placed there can satisfy {@code loadLibrary}.
     */
    @Test
    public void cacheDirectoryIsNotOnTheBareNameSearchPath() throws Exception {
        assumeTrue("host does not ship a library to probe with",
                new File(com.fongmi.android.tv.App.get().getApplicationInfo().nativeLibraryDir,
                        "libc++_shared.so").isFile());

        // libc++_shared is already loaded by the host, so a bare-name lookup for it succeeds from
        // the native library directory. Copying it into the cache directory must not change where
        // the loader looks: the mapped copy stays the native-library one.
        File cacheCopy = new File(com.fongmi.android.tv.App.get().getCacheDir(), "libc++_shared.so");
        try (InputStream in = new java.io.FileInputStream(
                new File(com.fongmi.android.tv.App.get().getApplicationInfo().nativeLibraryDir,
                        "libc++_shared.so"));
             OutputStream out = new FileOutputStream(cacheCopy)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }

        System.loadLibrary("c++_shared");

        assertTrue("a copy in the cache directory must not become the mapped copy",
                !mappedFrom("libc++_shared.so", cacheCopy.getParentFile()));
    }
}
