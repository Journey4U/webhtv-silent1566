package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares the ways the host can read an installed module's assets.
 *
 * <p>Modularising the Python runtime (Chaquopy, ~21 MB) means its {@code assets/chaquopy/**} payload
 * must move into a module, and Chaquopy locates that payload through an {@code AssetManager}. The
 * host must therefore be able to read the module's assets. Three mechanisms exist, and the choice
 * matters because one of them is hidden API:
 *
 * <ol>
 *   <li>{@code AssetManager.addAssetPath} — proven to work, but it is <em>hidden</em> API, and it
 *       mutates the process-wide {@code AssetManager} irreversibly.
 *   <li>{@code createPackageContext(...).getAssets()} — public API, no process-wide mutation.
 *   <li>Reading the module APK as a zip — plain Java, no Android API at all.
 * </ol>
 *
 * <p>The IJK module is the subject: it ships {@code assets/runtime.json}, which the host does not
 * have, so a successful read cannot be explained by the host's own assets.
 *
 * <p>If mechanism 2 or 3 works, the Python module does not need hidden API.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeModuleAssetAccessDeviceTest {

    private static final String MODULE_PACKAGE = "com.silent.runtime.ijk";
    private static final String MODULE_ASSET = "runtime.json";

    private static boolean moduleInstalled() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Mechanism 2: the public API. */
    @Test
    public void moduleAssetsAreReadableThroughCreatePackageContext() throws Exception {
        assumeTrue("module not installed", moduleInstalled());

        Context host = ApplicationProvider.getApplicationContext();
        Context module = host.createPackageContext(MODULE_PACKAGE, 0);
        assertNotNull("createPackageContext should return the module's context", module);

        AssetManager am = module.getAssets();
        assertNotNull("the module context should expose an AssetManager", am);

        String content;
        try (InputStream in = am.open(MODULE_ASSET, AssetManager.ACCESS_STREAMING)) {
            content = readAll(in);
        }
        assertTrue("the module's asset should have content; got: " + content, content.length() > 0);
    }

    /**
     * Mechanism 3: read the module APK as a zip, with no Android API involved.
     *
     * <p>This is the fallback that cannot break: it works on any API level and any module container.
     */
    @Test
    public void moduleAssetsAreReadableByReadingTheApkDirectly() throws Exception {
        assumeTrue("module not installed", moduleInstalled());

        File apk = RuntimeAndroid.packageInfo().apkPath(MODULE_PACKAGE);
        assertNotNull("module APK path should resolve", apk);
        assertTrue("module APK should exist", apk.isFile());

        String content;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/" + MODULE_ASSET);
            assertNotNull("the module APK should contain assets/" + MODULE_ASSET, entry);
            try (InputStream in = zip.getInputStream(entry)) {
                content = readAll(in);
            }
        }
        assertTrue("the module's asset should have content; got: " + content, content.length() > 0);
    }

    /**
     * Both mechanisms must yield the same bytes, so neither is silently reading something else.
     */
    @Test
    public void bothMechanismsAgreeOnTheContent() throws Exception {
        assumeTrue("module not installed", moduleInstalled());

        Context host = ApplicationProvider.getApplicationContext();
        AssetManager am = host.createPackageContext(MODULE_PACKAGE, 0).getAssets();
        String viaContext;
        try (InputStream in = am.open(MODULE_ASSET, AssetManager.ACCESS_STREAMING)) {
            viaContext = readAll(in);
        }

        File apk = RuntimeAndroid.packageInfo().apkPath(MODULE_PACKAGE);
        String viaZip;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/" + MODULE_ASSET);
            assertNotNull(entry);
            try (InputStream in = zip.getInputStream(entry)) {
                viaZip = readAll(in);
            }
        }

        assertEquals("the two mechanisms should read the same bytes", viaZip, viaContext);
    }

    /**
     * The module's context must not expose the host's assets, otherwise a read could be served by
     * the host's own copy and the tests above would not prove anything about module assets.
     */
    @Test
    public void moduleContextDoesNotExposeHostAssets() throws Exception {
        assumeTrue("module not installed", moduleInstalled());

        Context host = ApplicationProvider.getApplicationContext();
        AssetManager am = host.createPackageContext(MODULE_PACKAGE, 0).getAssets();
        boolean hostAssetVisible;
        try (InputStream in = am.open("lab_template.json", AssetManager.ACCESS_STREAMING)) {
            hostAssetVisible = in != null;
        } catch (IOException e) {
            hostAssetVisible = false;
        }
        assertTrue("the module context should not expose the host's own assets", !hostAssetVisible);
    }

    /** A missing module package must fail loudly rather than silently return the host's assets. */
    @Test
    public void missingModulePackageIsReported() {
        try {
            ApplicationProvider.getApplicationContext()
                    .createPackageContext("com.silent.runtime.does.not.exist", 0);
            throw new AssertionError("createPackageContext should fail for a missing package");
        } catch (PackageManager.NameNotFoundException expected) {
            // The documented behaviour.
        }
    }
}
