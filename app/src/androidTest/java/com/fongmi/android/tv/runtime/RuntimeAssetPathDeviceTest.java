package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Whether a module's assets can be made visible to the host's {@code AssetManager}.
 *
 * <p>This decides whether the Python runtime (Chaquopy, ~21 MB) can be modularised. Chaquopy
 * locates its runtime through {@code context.getAssets()} — it reads {@code chaquopy/build.json}
 * and extracts {@code chaquopy/**} from whichever APK the AssetManager covers. Those assets are
 * merged into the host APK at build time, so moving them into a module means the host must make the
 * module's assets readable through its own AssetManager.
 *
 * <p>The only mechanism available is {@code AssetManager.addAssetPath}, which is hidden API. This
 * test measures whether it works on the supported device runtime and whether the added assets
 * become readable.
 *
 * <p>Uses the IJK module as the subject: it ships an {@code assets/runtime.json} the host does not
 * have, so a successful read cannot be explained by the host's own assets.
 *
 * <p><b>Run this class alone.</b> {@code addAssetPath} is process-wide and cannot be undone, so a
 * sibling test that adds the module APK first would invalidate the baseline below.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeAssetPathDeviceTest {

    private static final String MODULE_PACKAGE = "com.silent.runtime.ijk";
    private static final String MODULE_ASSET = "runtime.json";
    private static final String HOST_ASSET = "lab_template.json";

    private static boolean moduleInstalled() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private static boolean canRead(AssetManager am, String name) {
        try (InputStream in = am.open(name, AssetManager.ACCESS_STREAMING)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Baseline: the host's own assets are readable, so the probe itself works. */
    @Test
    public void hostAssetsAreReadable() {
        AssetManager am = ApplicationProvider.getApplicationContext().getAssets();
        assertTrue("host should read its own asset " + HOST_ASSET, canRead(am, HOST_ASSET));
    }

    /**
     * Baseline: the module's assets are not visible before anything is done.
     *
     * <p>Guarded by its own process: {@code addAssetPath} is process-wide and cannot be undone, so
     * once another test in this class has added the module APK this baseline no longer holds. Run
     * this method alone to observe it:
     * <pre>
     * adb shell am instrument -w -e class \
     *   com.fongmi.android.tv.runtime.RuntimeAssetPathDeviceTest#moduleAssetsAreNotVisibleByDefault \
     *   com.silent.android.webhtv.test/androidx.test.runner.AndroidJUnitRunner
     * </pre>
     */
    @Test
    public void moduleAssetsAreNotVisibleByDefault() {
        assumeTrue("module not installed", moduleInstalled());

        AssetManager am = ApplicationProvider.getApplicationContext().getAssets();
        // If the module APK was already added in this process, the premise no longer holds; skip
        // rather than report a false failure.
        assumeTrue("another test already added the module APK to this process's AssetManager",
                !canRead(am, MODULE_ASSET));

        assertTrue("the module's asset must not be visible through the host AssetManager "
                        + "before addAssetPath",
                !canRead(am, MODULE_ASSET));
    }

    /**
     * The decisive experiment: add the module APK to the host AssetManager and try to read the
     * module's asset through it.
     *
     * <p>If this fails, the Python runtime cannot be modularised without patching Chaquopy, because
     * Chaquopy has no seam for supplying assets from elsewhere.
     */
    @Test
    public void addAssetPathMakesModuleAssetsReadable() throws Exception {
        assumeTrue("module not installed", moduleInstalled());

        Context context = ApplicationProvider.getApplicationContext();
        RuntimePlatform.PackageInfo info = RuntimeAndroid.packageInfo();
        java.io.File apk = info.apkPath(MODULE_PACKAGE);
        assertNotNull("module APK path should resolve", apk);
        assertTrue("module APK should exist", apk.isFile());

        AssetManager am = context.getAssets();
        Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
        Object result = addAssetPath.invoke(am, apk.getAbsolutePath());
        assertNotNull("addAssetPath should return a cookie", result);

        assertTrue("after addAssetPath the module's asset should be readable through the host "
                        + "AssetManager; if this fails, Chaquopy has no seam for external assets",
                canRead(am, MODULE_ASSET));
    }
}
