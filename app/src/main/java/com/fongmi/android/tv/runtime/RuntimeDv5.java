package com.fongmi.android.tv.runtime;

import java.io.File;

/**
 * Prepares the DV5 GPU renderer's native library from the installed module.
 *
 * <p>{@code ExoDv5Native} loads its library by bare name
 * ({@code System.loadLibrary("exo_dovi_renderer")}), and that searches only the host's own
 * {@code nativeLibraryDir}. A module's libraries live in the module's directory, so the bare-name
 * lookup cannot see them. Rather than duplicating the search path, this class resolves the bare name
 * to the module's file so the host can load it by absolute path — the same approach
 * {@link RuntimeIjk} takes for IJK.
 *
 * <p>The library is a good module candidate: measured with {@code readelf} it depends only on system
 * libraries ({@code libandroid}, {@code liblog}, {@code libmediandk}, {@code libvulkan},
 * {@code libdl}, {@code libm}, {@code libc}) and carries a soname, so there is no
 * {@code hostLibraries} contract to satisfy.
 *
 * <p>Unlike IJK, this renderer is an <em>optional enhancement</em> rather than a playback engine:
 * {@code ExoDv5GpuMappingPolicy} already has fallback routes for Dolby Vision Profile 5, and
 * {@code ExoUtil} wraps renderer construction in {@code catch (Throwable ignored)}. So a missing
 * module degrades one route instead of breaking playback, which is why the resolver may simply
 * return {@code null}.
 */
public final class RuntimeDv5 {

    public static final String MODULE_ID = "dv5";

    /** Package name of the DV5 module APK. */
    public static final String MODULE_PACKAGE = "com.silent.runtime.dv5";

    /** Bare name {@code ExoDv5Native} asks for. */
    public static final String LIBRARY = "exo_dovi_renderer";

    /** File name the module stores the library under. */
    public static final String LIBRARY_FILE = "libexo_dovi_renderer.so";

    /** Receives the resolved library file. */
    public interface Resolver {
        File resolve(String bareName);
    }

    private static volatile Resolver resolver;

    private RuntimeDv5() {
    }

    /**
     * Whether the module package is actually present on the device.
     *
     * <p>Asks the platform rather than the host's install registry: a module installed by any route
     * is functional, and gating on our own bookkeeping would hide the renderer from users who
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
     * Whether the DV5 GPU renderer can be offered at all.
     *
     * <p>Two sources satisfy this: an installed module, or the transitional copy still bundled in
     * the APK. Pure function so the policy is testable without a device.
     */
    public static boolean isAvailable(boolean moduleInstalled, boolean bundled) {
        return moduleInstalled || bundled;
    }

    /**
     * Installs the resolver that redirects {@code ExoDv5Native}'s bare-name lookup at the module.
     *
     * <p>The resolver re-checks the module on every lookup rather than capturing a snapshot, so the
     * order of "install module" and "start playback" does not matter. That is affordable because
     * {@code ExoDv5Native} caches its attempt in a static {@code LOAD_RESULT}: the lookup runs at
     * most once per process.
     *
     * <p>No ABI parameter: the platform's {@code nativeLibraryDir} is already ABI-resolved, so a
     * module that does not match this device has no such directory and the resolver returns null.
     *
     * <p>Safe to call repeatedly.
     *
     * @param packageInfo resolves the module's native library directory
     */
    public static Status install(RuntimePlatform.PackageInfo packageInfo) {
        resolver = name -> {
            if (RuntimeText.isBlank(name) || !LIBRARY.equals(name) || packageInfo == null) return null;
            File dir = packageInfo.nativeLibraryDir(MODULE_PACKAGE);
            if (dir == null || !dir.isDirectory()) return null;
            File file = new File(dir, LIBRARY_FILE);
            return file.isFile() ? file : null;
        };

        if (!isModuleInstalled(packageInfo)) {
            return new Status(false, "DV5 模块未安装", null);
        }
        return new Status(true, "", packageInfo.nativeLibraryDir(MODULE_PACKAGE));
    }

    /** Clears the resolver, restoring plain {@code System.loadLibrary} behaviour. */
    public static void uninstall() {
        resolver = null;
    }

    /**
     * The file to load for {@code bareName}, or {@code null} to fall back to a bare-name lookup.
     *
     * <p>Called by {@code ExoDv5Native} immediately before it would otherwise call
     * {@code System.loadLibrary}. Returns null whenever no resolver is installed, which is the case
     * in a build that still bundles the library.
     */
    public static File resolve(String bareName) {
        Resolver current = resolver;
        if (current == null) return null;
        try {
            return current.resolve(bareName);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Outcome of preparing the renderer library. */
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
