package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import is.xyz.mpv.MPVLib;
import tv.danmaku.ijk.media.player.IjkLibLoader;

/**
 * The state every new user starts in: no modules installed.
 *
 * <p>This is the most common configuration in the field and the easiest to leave untested, because
 * developing a module means having it installed. It matters because the bundled libraries are gone:
 * if availability or loading assumed a module, a fresh install would either crash or offer an
 * engine that cannot start.
 *
 * <p>Asserts the negative space deliberately — the engines must report <em>unavailable</em>, and
 * nothing may throw while discovering that.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeNoModuleDeviceTest {

    private static final String[] MODULE_PACKAGES = {
            "com.silent.runtime.mpv",
            "com.silent.runtime.ijk",
            "com.silent.runtime.sherpa",
    };

    /** True when no module is installed, i.e. the fresh-install configuration. */
    private static boolean noModulesInstalled() {
        RuntimePlatform.PackageInfo info = RuntimeAndroid.packageInfo();
        for (String pkg : MODULE_PACKAGES) {
            if (info.nativeLibraryDir(pkg) != null) return false;
        }
        return true;
    }

    @Test
    public void availabilityReportsEveryModuleEngineUnavailable() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        PlayerSetting.resetMpvAvailability();
        PlayerSetting.resetIjkAvailability();

        assertFalse("MPV must not be offered without its module", PlayerSetting.isMpvAvailable());
        assertFalse("IJK must not be offered without its module", PlayerSetting.isIjkAvailable());
        assertFalse(PlayerSetting.isPlayerAvailable(PlayerSetting.MPV));
        assertFalse(PlayerSetting.isPlayerAvailable(PlayerSetting.IJK));
    }

    /** The engines that need no module must stay available, or the app would be unusable. */
    @Test
    public void builtInEnginesRemainAvailable() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        assertTrue("EXO needs no module", PlayerSetting.isPlayerAvailable(PlayerSetting.EXO));
        assertTrue("the system engine needs no module",
                PlayerSetting.isPlayerAvailable(PlayerSetting.SYSTEM));
    }

    /** Discovery must not throw; a crash here would break every fresh install. */
    @Test
    public void moduleProbesReportAbsenceWithoutThrowing() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        RuntimePlatform.PackageInfo info = RuntimeAndroid.packageInfo();
        assertNotNull(info);
        for (String pkg : MODULE_PACKAGES) {
            assertNull("no native library dir for an absent module: " + pkg,
                    info.nativeLibraryDir(pkg));
        }

        // The bundled-library probes must answer, not throw.
        App context = ApplicationProvider.getApplicationContext();
        assertFalse("no MPV assets should remain in this build", MPVLib.isBundled(context));
    }

    /** With no module there is nothing to resolve, so the resolver must decline. */
    @Test
    public void ijkResolverDeclinesWithoutAModule() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        RuntimeIjk.Status status = RuntimeIjk.install(RuntimeAndroid.packageInfo());
        try {
            assertFalse(status.isOk());
            assertTrue(status.getProblem(), status.getProblem().contains("未安装"));
            // Declining makes IjkLibLoader fall back to System.loadLibrary, which is the correct
            // behaviour for a build that still bundles the libraries.
            assertNull(IjkLibLoader.getResolver().resolve("ijkffmpeg"));
        } finally {
            RuntimeIjk.uninstall();
        }
    }

    /** MPV preparation must fail cleanly rather than half-load. */
    @Test
    public void mpvPreparationFailsCleanlyWithoutAModule() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        RuntimeMpv.Status status = RuntimeMpv.prepare(
                new RuntimeLoader(new RuntimeStore(new RuntimeStore.Storage() {
                    @Override
                    public String read() {
                        return "";
                    }

                    @Override
                    public void write(String value) {
                    }
                }), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                RuntimeAbi.resolve(com.fongmi.android.tv.BuildConfig.FLAVOR_abi,
                        android.os.Build.SUPPORTED_ABIS));

        assertFalse(status.isOk());
        assertFalse("a failed preparation must not report an entry library",
                status.getLoaded() != null && !status.getLoaded().isEmpty());
    }

    /** The host must not claim a module is installed when no package provides it. */
    @Test
    public void hostDoesNotClaimAbsentModulesAreInstalled() {
        org.junit.Assume.assumeTrue("modules are installed; this test covers the fresh-install state",
                noModulesInstalled());

        RuntimePlatform.PackageInfo info = RuntimeAndroid.packageInfo();
        assertFalse(RuntimeMpv.isModuleInstalled(info));
        assertFalse(RuntimeIjk.isModuleInstalled(info));
        assertFalse(RuntimeSherpa.prepare(
                new RuntimeLoader(new RuntimeStore(new RuntimeStore.Storage() {
                    @Override
                    public String read() {
                        return "";
                    }

                    @Override
                    public void write(String value) {
                    }
                }), info, path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAbi.resolve(com.fongmi.android.tv.BuildConfig.FLAVOR_abi,
                        android.os.Build.SUPPORTED_ABIS),
                path -> {
                },
                () -> false).isUsable());
    }

    /** Every module package the host knows about must be one it can actually resolve. */
    @Test
    public void everyKnownModuleIdHasATrustedPackageName() {
        for (String id : new String[]{"mpv", "ijk", "sherpa"}) {
            String pkg = RuntimeCompatibility.packageName(id);
            assertNotNull("host must know the package for module: " + id, pkg);
            assertTrue(pkg, pkg.startsWith("com.silent.runtime."));
        }
        assertNull("an unknown id must not resolve", RuntimeCompatibility.packageName("not-a-module"));
    }

    /** A module directory that does not exist must not be mistaken for an install. */
    @Test
    public void absentModuleDirectoryIsNotAnInstall() {
        RuntimePlatform.PackageInfo info = RuntimeAndroid.packageInfo();
        assertNull(info.nativeLibraryDir("com.silent.runtime.does.not.exist"));
        assertFalse(new File("/data/app/com.silent.runtime.does.not.exist/lib/arm64").isDirectory());
    }
}
