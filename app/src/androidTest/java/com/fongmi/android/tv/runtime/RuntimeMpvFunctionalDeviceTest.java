package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import is.xyz.mpv.MPVLib;

/**
 * Proves the modularised MPV is functional, not merely loadable.
 *
 * <p>{@link RuntimeMpvLoadDeviceTest} shows the nine libraries dlopen and map from the module. That
 * still does not prove the bundle works: a library can load and still fail at first use. This test
 * initialises the real mpv core and reads a property from it, which exercises the JNI surface and
 * the whole dependency graph end to end.
 *
 * <p>This is the evidence required before removing the bundled {@code assets/mpv-libs}: if the
 * module cannot initialise a core, removing the assets would break MPV for everyone.
 *
 * <p><b>Run this class alone.</b> The mpv context is process-wide and cannot be re-created after
 * destroy, so a sibling test that initialises it first would make the result order-dependent:
 * <pre>
 * adb shell am instrument -w \
 *   -e class com.fongmi.android.tv.runtime.RuntimeMpvFunctionalDeviceTest \
 *   com.silent.android.webhtv.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeMpvFunctionalDeviceTest {

    private static final String MODULE_ID = "mpv";
    private static final String MODULE_PACKAGE = "com.silent.runtime.mpv";
    private static final String ABI = "arm64-v8a";

    private static final class MemoryStorage implements RuntimeStore.Storage {
        private String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }
    }

    private static boolean installed() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private void requireModule() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", installed());
    }

    private RuntimeStore seededStore(RuntimeManifest manifest) {
        RuntimeCatalog.RuntimeModule module = new RuntimeCatalog.RuntimeModule();
        module.id = manifest.module;
        module.packageName = manifest.packageName;
        module.compatibilityId = manifest.compatibilityId;
        module.versionCode = manifest.versionCode;
        module.versionName = manifest.versionName;
        RuntimeCatalog.RuntimeArtifact artifact = new RuntimeCatalog.RuntimeArtifact();
        artifact.abi = manifest.abi;
        artifact.size = 1;
        artifact.sha256 = RuntimeIntegrity.sha256("seeded".getBytes(StandardCharsets.UTF_8));
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        module.artifacts = List.of(artifact);

        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    /**
     * Initialises the mpv core from the module and reads a property from it.
     *
     * <p>{@code mpv-version} is only answerable once the core is running, so a value here means the
     * whole bundle — not just the loader — is functional.
     */
    @Test
    public void moduleInitialisesAWorkingMpvCore() throws Exception {
        requireModule();

        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                new RuntimeLoader(seededStore(manifest), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                ABI);
        assertTrue("module libraries failed to load: " + status.getProblem(), status.isOk());

        App context = ApplicationProvider.getApplicationContext();
        assertTrue("mpv context creation failed", MPVLib.tryCreate(context));
        try {
            MPVLib.initializeCreatedContext();

            // A property read proves the JNI surface resolves and the core answers.
            String version = MPVLib.getPropertyString("mpv-version");
            assertNotNull("mpv-version should be readable once the core is up", version);
            assertFalse("mpv-version should not be empty", version.trim().isEmpty());
            assertTrue("mpv-version should look like a version, got: " + version,
                    version.contains("mpv") || Character.isDigit(version.trim().charAt(0)));

            // ffmpeg is a separate library in the bundle; reading it exercises the codec stack too.
            String ffmpeg = MPVLib.getPropertyString("ffmpeg-version");
            assertNotNull("ffmpeg-version should be readable", ffmpeg);
            assertFalse("ffmpeg-version should not be empty", ffmpeg.trim().isEmpty());
        } finally {
            MPVLib.destroyCreatedContext();
        }
    }

    /** The availability rule must report MPV usable while the module is installed. */
    @Test
    public void availabilityReportsMpvUsableWithTheModuleInstalled() {
        requireModule();

        PlayerSetting.resetMpvAvailability();
        assertTrue("MPV should be offered when its module is installed",
                PlayerSetting.isMpvAvailable());
        assertTrue(PlayerSetting.isPlayerAvailable(PlayerSetting.MPV));
    }

    /** The probe must reflect reality rather than a hardcoded true. */
    @Test
    public void bundledProbeMatchesWhatTheApkActuallyShips() throws Exception {
        requireModule();

        App context = ApplicationProvider.getApplicationContext();
        boolean bundled = MPVLib.isBundled(context);
        boolean hasAssets = new File(context.getApplicationInfo().sourceDir).isFile()
                && hasMpvAssets(context);

        assertEquals("isBundled must agree with what the APK ships", hasAssets, bundled);
    }

    /** Reads the APK's asset listing without extracting it. */
    private static boolean hasMpvAssets(App context) {
        try {
            String[] abis = {"arm64-v8a", "armeabi-v7a"};
            for (String abi : abis) {
                for (String lib : new String[]{"libmpv.so", "libplayer.so"}) {
                    try (java.io.InputStream ignored = context.getAssets()
                            .open("mpv-libs/" + abi + "/" + lib, android.content.res.AssetManager.ACCESS_STREAMING)) {
                        return true;
                    } catch (java.io.IOException ignored) {
                        // try the next candidate
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
