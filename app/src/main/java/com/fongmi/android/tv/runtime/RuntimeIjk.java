package com.fongmi.android.tv.runtime;

import java.io.File;
import java.util.List;

/**
 * Prepares the IJK native libraries from the installed module.
 *
 * <p>IJK differs from MPV in how it loads: {@code IjkMediaPlayer} calls
 * {@code System.loadLibrary("ijkffmpeg")} by bare name, and that searches only the host's own
 * {@code nativeLibraryDir}. A module's libraries live in the module's directory, so the bare-name
 * lookup cannot see them.
 *
 * <p>Rather than duplicating the search path, this class installs a resolver into
 * {@link tv.danmaku.ijk.media.player.IjkLibLoader}: when the module is installed and verified, the
 * bare name is mapped to the module's file and loaded by absolute path.
 *
 * <p>Unlike MPV, the IJK libraries depend only on system libraries plus each other
 * ({@code libijkffmpeg} ← {@code libijksdl} ← {@code libijkplayer}), so there is no host-provided
 * shared library and therefore no {@code hostLibraries} contract to satisfy.
 */
public final class RuntimeIjk {

    public static final String MODULE_ID = "ijk";

    /** Package name of the IJK module APK. */
    public static final String MODULE_PACKAGE = "com.silent.runtime.ijk";

    /** Bare names IJK asks for, in the order {@code IjkLibLoader} requests them. */
    public static final List<String> LIBRARIES = List.of("ijkffmpeg", "ijksdl", "ijkplayer");

    private RuntimeIjk() {
    }

    /**
     * Whether IJK can be offered as a playback engine.
     *
     * <p>Two sources satisfy this: an installed module, or the transitional copy still bundled in
     * the APK. Pure function so the policy is testable without a device.
     */
    public static boolean isAvailable(boolean moduleInstalled, boolean bundled) {
        return moduleInstalled || bundled;
    }

    /**
     * Whether the module package is actually present on the device.
     *
     * <p>Asks the platform rather than the host's install registry: a module installed by any route
     * is functional, and gating on our own bookkeeping would hide the engine from users who
     * installed it another way.
     */
    public static boolean isModuleInstalled(RuntimePlatform.PackageInfo packageInfo) {
        if (packageInfo == null) return false;
        try {
            return packageInfo.nativeLibraryDir(MODULE_PACKAGE) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Installs the resolver that redirects IJK's bare-name lookups at the module.
     *
     * <p>The resolver re-checks the module on every lookup rather than capturing a snapshot, so the
     * order of "install module" and "start playback" does not matter. That is affordable because
     * {@code IjkLibLoader} caches its attempt: the lookup runs at most once per library per process.
     *
     * <p>No ABI parameter: the platform's {@code nativeLibraryDir} is already ABI-resolved, so a
     * module that does not match this device has no such directory and the resolver returns null.
     *
     * <p>Safe to call repeatedly.
     *
     * @param packageInfo resolves the module's native library directory
     */
    public static Status install(RuntimePlatform.PackageInfo packageInfo) {
        tv.danmaku.ijk.media.player.IjkLibLoader.setResolver(name -> {
            if (RuntimeText.isBlank(name) || packageInfo == null) return null;
            File dir = packageInfo.nativeLibraryDir(MODULE_PACKAGE);
            if (dir == null || !dir.isDirectory()) return null;
            // IJK asks by bare name ("ijkffmpeg"); the module stores platform-conventional names.
            File file = new File(dir, "lib" + name + ".so");
            return file.isFile() ? file : null;
        });

        if (!isModuleInstalled(packageInfo)) {
            return new Status(false, "IJK 模块未安装", null);
        }
        return new Status(true, "", new File(packageInfo.nativeLibraryDir(MODULE_PACKAGE).getAbsolutePath()));
    }

    /** Clears the resolver, restoring plain {@code System.loadLibrary} behaviour. */
    public static void uninstall() {
        tv.danmaku.ijk.media.player.IjkLibLoader.setResolver(null);
    }

    /**
     * Whether a declared manifest path corresponds to the bare name IJK asked for.
     *
     * <p>IJK asks for {@code ijkffmpeg}; the manifest declares
     * {@code lib/arm64-v8a/libijkffmpeg.so}. Both the {@code lib} prefix and the {@code .so} suffix
     * are platform conventions, not part of the name.
     */
    static boolean matchesBareName(String declaredPath, String bareName) {
        if (RuntimeText.isBlank(declaredPath) || RuntimeText.isBlank(bareName)) return false;
        String fileName = declaredPath.substring(declaredPath.lastIndexOf('/') + 1);
        String withoutPrefix = fileName.startsWith("lib") ? fileName.substring(3) : fileName;
        String withoutSuffix = withoutPrefix.endsWith(".so")
                ? withoutPrefix.substring(0, withoutPrefix.length() - 3) : withoutPrefix;
        return withoutSuffix.equals(bareName);
    }

    /** Outcome of preparing the IJK libraries. */
    public static final class Status {

        private final boolean ok;
        private final String problem;
        private final File libraryDir;

        private Status(boolean ok, String problem, File libraryDir) {
            this.ok = ok;
            this.problem = problem == null ? "" : problem;
            this.libraryDir = libraryDir;
        }

        public boolean isOk() {
            return ok;
        }

        public String getProblem() {
            return problem;
        }

        public File getLibraryDir() {
            return libraryDir;
        }
    }
}
