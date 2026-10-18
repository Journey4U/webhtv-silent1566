package com.fongmi.android.tv.runtime;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Prepares the MPV native libraries from the installed module.
 *
 * <p>Ordering is the whole point of this class. {@code libmvcodec}/{@code libmpv}/{@code libplayer}
 * all declare {@code DT_NEEDED: libc++_shared.so}, and two copies of that library collide on the
 * same soname — the later one fails to {@code dlopen} with a missing-symbol error, which surfaces
 * as "MPV failed to load media" for nearly every source. So the host loads its own copy first, then
 * the module's libraries in the order the manifest declares.
 *
 * <p>The host copy is loaded by <em>soname</em> so the dynamic linker registers it under the name
 * the module's libraries will look up. The module must not ship its own copy; the verifier enforces
 * that plus the digest match.
 */
public final class RuntimeMpv {

    public static final String MODULE_ID = "mpv";

    /** Package name of the MPV module APK. */
    public static final String MODULE_PACKAGE = "com.silent.runtime.mpv";

    /**
     * Whether MPV can be offered as a playback engine.
     *
     * <p>Two sources satisfy this: an installed module, or the transitional copy still bundled in
     * the APK. Keeping the rule in one place makes the asset removal a no-op here — once the
     * bundled probe returns false, only an installed module enables the engine.
     *
     * <p>Pure function so the policy is testable without a device; the callers supply the probes.
     */
    public static boolean isAvailable(boolean moduleInstalled, boolean bundled) {
        return moduleInstalled || bundled;
    }

    /**
     * Whether the module package is actually present on the device.
     *
     * <p>Deliberately asks the platform rather than the host's install registry. A module installed
     * by any route — the in-app flow, a sideloaded APK, a file manager — is functional on the
     * device, and gating availability on our own bookkeeping would hide the engine from users who
     * installed it another way. The registry still tracks version and digest metadata; it is not
     * the source of truth for what exists.
     */
    public static boolean isModuleInstalled(RuntimePlatform.PackageInfo packageInfo) {
        if (packageInfo == null) return false;
        try {
            return packageInfo.nativeLibraryDir(MODULE_PACKAGE) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Outcome of preparing the MPV libraries. */
    public static final class Status {

        private final boolean ok;
        private final String entryLibraryPath;
        private final String problem;
        private final List<String> loaded;

        private Status(boolean ok, String entryLibraryPath, String problem, List<String> loaded) {
            this.ok = ok;
            this.entryLibraryPath = entryLibraryPath == null ? "" : entryLibraryPath;
            this.problem = problem == null ? "" : problem;
            this.loaded = loaded == null ? List.of() : List.copyOf(loaded);
        }

        public boolean isOk() {
            return ok;
        }

        public String getProblem() {
            return problem;
        }

        /** Absolute path of the module's entry library, resolved and verified. */
        public String getEntryLibraryPath() {
            return entryLibraryPath;
        }

        /** Absolute paths loaded, in order. Diagnostics only. */
        public List<String> getLoaded() {
            return loaded;
        }
    }

    /** Resolves the host's own copy of a library, or {@code null} when it is absent. */
    public interface HostLibrary {
        File resolve(String name);
    }

    private RuntimeMpv() {
    }

    /**
     * Verifies the installed module and loads the native libraries in the correct order.
     *
     * @param loader       verifies the module; may be {@code null} when no module is installed
     * @param packageInfo  resolves the module's native library directory
     * @param hostLibrary  resolves the host's own libraries (e.g. {@code libc++_shared.so})
     * @param libraryLoader performs the actual load; injected so tests never dlopen
     * @param abi          the ABI this build must match
     */
    public static Status prepare(
            RuntimeLoader loader,
            RuntimePlatform.PackageInfo packageInfo,
            HostLibrary hostLibrary,
            RuntimePlatform.LibraryLoader libraryLoader,
            String abi) {

        if (loader == null) return new Status(false, "", "MPV 模块未安装", null);
        if (packageInfo == null) return new Status(false, "", "平台信息不可用", null);
        if (libraryLoader == null) return new Status(false, "", "原生库加载器不可用", null);

        RuntimeLoader.Outcome outcome = loader.verify(MODULE_ID, abi);
        if (!outcome.isOk()) {
            return new Status(false, "", outcome.getProblem(), null);
        }

        RuntimeManifest manifest = outcome.getManifest();
        File libDir = outcome.getLibraryDir();
        if (manifest == null || libDir == null) {
            return new Status(false, "", "模块信息不完整", null);
        }

        // Host libraries first, by soname, so the linker registers the name the module's
        // libraries will resolve against.
        for (Map.Entry<String, String> declared : manifest.hostLibraries().entrySet()) {
            String name = declared.getKey();
            File host = hostLibrary == null ? null : hostLibrary.resolve(name);
            if (host == null || !host.isFile()) {
                return new Status(false, "", "宿主库缺失：" + name, null);
            }
            try {
                libraryLoader.load(host.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                return new Status(false, "", "宿主库加载失败（" + name + "）：" + describe(e), null);
            } catch (RuntimeException e) {
                return new Status(false, "", "宿主库加载异常（" + name + "）：" + describe(e), null);
            }
        }

        List<String> order = manifest.librariesToLoad();
        if (order.isEmpty()) return new Status(false, "", "模块未声明要加载的库", null);

        java.util.ArrayList<String> loaded = new java.util.ArrayList<>(order.size());
        String entryPath = "";
        for (String declaredRel : order) {
            String entry = RuntimeManifest.nativeLibraryEntry(declaredRel, manifest.abi);
            File file = new File(libDir, entry);
            if (!file.isFile()) {
                return new Status(false, "", "模块库不存在：" + declaredRel, loaded);
            }
            try {
                libraryLoader.load(file.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                return new Status(false, "", "模块库加载失败（" + entry + "）：" + describe(e), loaded);
            } catch (RuntimeException e) {
                return new Status(false, "", "模块库加载异常（" + entry + "）：" + describe(e), loaded);
            }
            loaded.add(file.getAbsolutePath());
            entryPath = file.getAbsolutePath();
        }
        return new Status(true, entryPath, "", loaded);
    }

    private static String describe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return RuntimeText.isBlank(message)
                ? (t == null ? "未知错误" : t.getClass().getSimpleName())
                : message;
    }
}
