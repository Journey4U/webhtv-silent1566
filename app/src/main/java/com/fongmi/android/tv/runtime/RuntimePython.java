package com.fongmi.android.tv.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;

import com.fongmi.chaquo.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares the Python runtime from the installed module.
 *
 * <p>Chaquopy locates everything through one {@code Context}: its assets, its extraction root and
 * its native libraries. Normally that context is the host's own, because the runtime is bundled in
 * the host APK. When the runtime is delivered as a module its payload lives in the module's APK
 * instead, so the host has to supply a context that reads the module's assets while still writing
 * into its own files directory.
 *
 * <p>That split is not optional. Measured from Chaquopy's own {@code importer.py}, the injected
 * context is used for:
 *
 * <ul>
 *   <li>{@code getAssets()} — reading {@code chaquopy/**};
 *   <li>{@code getFilesDir()} — the root the runtime extracts into, which must be writable by us;
 *   <li>{@code getSharedPreferences()} — bookkeeping for that extraction;
 *   <li>{@code getApplicationInfo().nativeLibraryDir} — assigned but never read, so it does not
 *       matter which context supplies it.
 * </ul>
 *
 * <p>The module's data directory is not writable by the host, so pointing {@code getFilesDir()} at
 * it would break extraction. Hence the wrapper overrides only {@code getAssets()}.
 *
 * <p>Native libraries are loaded by absolute path in the manifest's declared order. The order is
 * load-bearing: measured from the APK, {@code libcrypto_chaquopy} depends on
 * {@code libcrypto_python}, {@code libssl_chaquopy} on {@code libssl_python},
 * {@code libsqlite3_chaquopy} on {@code libsqlite3_python}, and {@code libssl_python} on
 * {@code libcrypto_python}, while Chaquopy's own bare-name sequence loads several of those
 * dependents first. Bare names still work because the linker searches the namespace's library
 * paths, but a module's directory is not on that path.
 */
public final class RuntimePython {

    public static final String MODULE_ID = "python";

    /** Package name of the Python module APK. */
    public static final String MODULE_PACKAGE = "com.silent.runtime.python";

    private RuntimePython() {
    }

    /**
     * Whether the module package is actually present on the device.
     *
     * <p>Asks the platform rather than the host's install registry, so a module installed by any
     * route counts.
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
     * Whether the Python runtime can be offered at all.
     *
     * <p>Two sources satisfy this: an installed module, or the transitional copy still bundled in the
     * APK. Pure function so the policy is testable without a device.
     */
    public static boolean isAvailable(boolean moduleInstalled, boolean bundled) {
        return moduleInstalled || bundled;
    }

    /**
     * Resolves the absolute paths of the module's native libraries, in the manifest's declared order.
     *
     * <p>Pure Java, so the ordering rule is testable without a device: the order decides whether the
     * linker can satisfy each library's {@code DT_NEEDED} entries, because a module's directory is not
     * on the namespace search path.
     *
     * @return the paths, or {@code null} with {@code problem} set when the module is unusable
     */
    static Resolution resolveLibraryPaths(File libraryDir, RuntimeManifest manifest) {
        if (libraryDir == null) return Resolution.failure("模块原生库目录不可用");
        if (manifest == null) return Resolution.failure("模块清单不可用");

        List<String> paths = new ArrayList<>();
        for (String declared : manifest.librariesToLoad()) {
            String entry = RuntimeManifest.nativeLibraryEntry(declared, manifest.abi);
            File file = new File(libraryDir, entry);
            if (!file.isFile()) return Resolution.failure("模块库不存在：" + declared);
            paths.add(file.getAbsolutePath());
        }
        if (paths.isEmpty()) return Resolution.failure("模块未声明要加载的库");
        return Resolution.success(paths);
    }

    /**
     * Whether the Python runtime can be started at all on this build.
     *
     * <p>Two sources satisfy this: an installed module, or the runtime still bundled in the host APK.
     * The bundled case is detected by looking for the build manifest Chaquopy itself reads, because
     * that file's presence is exactly what {@code Platform}'s constructor requires.
     *
     * <p>Callers use this before constructing anything that triggers {@code Python.start}, so a build
     * without the runtime reports the feature as unavailable instead of failing to initialise. This
     * matters because the eager path is reached from static initialisers: {@code BaseLoader} creates
     * its {@code PyLoader} while the class is being initialised, so an exception there would take down
     * configuration loading rather than just the Python feature.
     */
    public static boolean isRuntimeAvailable(Context context) {
        if (context == null) return false;
        try {
            if (isModuleInstalled(RuntimeAndroid.packageInfo())) return true;
        } catch (Throwable e) {
            // fall through to the bundled probe
        }
        return isBundled(context);
    }

    /**
     * Whether the host APK still carries the runtime's own payload.
     *
     * <p>Probes the same file Chaquopy's {@code Platform} opens, so the answer cannot disagree with
     * what the runtime will find.
     */
    public static boolean isBundled(Context context) {
        if (context == null) return false;
        try (java.io.InputStream in = context.getAssets().open("chaquopy/build.json")) {
            return in != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Installs the provider Chaquopy reads its payload through.
     *
     * <p>Call before anything touches {@code com.chaquo.python}: {@code Platform}'s constructor
     * reads the build manifest and loads the libraries, so a later change has no effect.
     *
     * <p>Safe to call repeatedly; the last value wins, so a module installed later is picked up on
     * the next process start.
     *
     * @param packageInfo resolves the module's native library directory
     * @param hostContext the host context, used for everything except assets
     * @param loader      verifies the module before anything is read from it
     * @param abi         the ABI this build must match
     */
    public static Status install(
            RuntimePlatform.PackageInfo packageInfo,
            Context hostContext,
            RuntimeLoader loader,
            String abi) {

        if (!isModuleInstalled(packageInfo)) {
            Platform.setRuntimeModule(null);
            return new Status(false, "Python 模块未安装", null);
        }
        if (hostContext == null) {
            Platform.setRuntimeModule(null);
            return new Status(false, "宿主上下文不可用", null);
        }
        if (loader == null) {
            Platform.setRuntimeModule(null);
            return new Status(false, "模块校验器不可用", null);
        }

        RuntimeLoader.Outcome outcome = loader.verify(MODULE_ID, abi);
        if (!outcome.isOk()) {
            Platform.setRuntimeModule(null);
            return new Status(false, outcome.getProblem(), null);
        }

        File libDir = outcome.getLibraryDir();
        Resolution resolution = resolveLibraryPaths(libDir, outcome.getManifest());
        if (!resolution.isOk()) {
            Platform.setRuntimeModule(null);
            return new Status(false, resolution.getProblem(), null);
        }

        AssetManager moduleAssets = moduleAssets(hostContext, MODULE_PACKAGE);
        if (moduleAssets == null) {
            Platform.setRuntimeModule(null);
            return new Status(false, "无法读取模块资产：" + MODULE_PACKAGE, null);
        }

        Platform.setRuntimeModule(new Provider(hostContext, moduleAssets, resolution.getPaths()));
        return new Status(true, "", libDir);
    }

    /** Clears the provider, restoring the bundled-runtime behaviour. */
    public static void uninstall() {
        Platform.setRuntimeModule(null);
    }

    /**
     * The module's asset manager, or {@code null} when it cannot be obtained.
     *
     * <p>Uses {@code createPackageContext}, a public API, rather than the hidden
     * {@code AssetManager.addAssetPath}: the latter mutates the process-wide asset manager
     * irreversibly, which is both fragile and hard to undo when a module is uninstalled.
     */
    private static AssetManager moduleAssets(Context hostContext, String packageName) {
        try {
            return hostContext.createPackageContext(packageName, 0).getAssets();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Supplies Chaquopy with the module's assets and native library paths.
     *
     * <p>Only {@code getAssets()} is overridden. Everything else — the files directory the runtime
     * extracts into, the preferences it records that in, and the application info — must stay the
     * host's, because the module's data directory is not writable by us.
     */
    private static final class Provider extends ContextWrapper implements Platform.RuntimeModule {

        private final AssetManager assets;
        private final List<String> libraryPaths;

        private Provider(Context hostContext, AssetManager assets, List<String> libraryPaths) {
            super(hostContext);
            this.assets = assets;
            this.libraryPaths = List.copyOf(libraryPaths);
        }

        @Override
        public AssetManager getAssets() {
            return assets;
        }

        @Override
        public Context assetContext() {
            return this;
        }

        @Override
        public List<String> nativeLibraryPaths() {
            return libraryPaths;
        }
    }

    /** Outcome of resolving the module's native library paths. */
    static final class Resolution {

        private final boolean ok;
        private final String problem;
        private final List<String> paths;

        private Resolution(boolean ok, String problem, List<String> paths) {
            this.ok = ok;
            this.problem = problem == null ? "" : problem;
            this.paths = paths == null ? List.of() : List.copyOf(paths);
        }

        static Resolution success(List<String> paths) {
            return new Resolution(true, "", paths);
        }

        static Resolution failure(String problem) {
            return new Resolution(false, problem, null);
        }

        boolean isOk() {
            return ok;
        }

        String getProblem() {
            return problem;
        }

        List<String> getPaths() {
            return paths;
        }
    }

    /** Outcome of preparing the Python runtime. */
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
