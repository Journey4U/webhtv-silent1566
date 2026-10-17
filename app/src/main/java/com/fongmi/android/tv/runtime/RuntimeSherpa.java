package com.fongmi.android.tv.runtime;

import java.io.File;

/**
 * Prepares the sherpa-onnx native library for the realtime-subtitle engine.
 *
 * <p>The SDK resolves its native library through {@code sherpa_onnx.native.path} before falling
 * back to {@code System.loadLibrary}. Setting that property to a verified module directory is
 * therefore enough to redirect the SDK at the module, with no patching of SDK internals.
 *
 * <p>The property must be set <em>before</em> any {@code com.k2fsa.sherpa.onnx} class is touched:
 * the SDK's loader runs from a static initializer and caches the result, so a later change has no
 * effect.
 *
 * <p>This class deliberately does not reference the SDK, so it can be exercised on a plain JVM and
 * so the gate cannot accidentally trigger the very load it is guarding.
 */
public final class RuntimeSherpa {

    public static final String MODULE_ID = "sherpa";

    /** Property the sherpa-onnx SDK reads to locate its native library. */
    public static final String NATIVE_PATH_PROPERTY = "sherpa_onnx.native.path";

    /** Where the native library comes from. */
    public enum Source {
        /** The verified module directory; the SDK will be pointed at it. */
        MODULE,
        /** No module installed; the library bundled in the APK is used (pre-modular behaviour). */
        BUNDLED,
        /** Neither a module nor a bundled library is available. */
        UNAVAILABLE
    }

    /** Receives the resolved native library directory. */
    public interface Configurer {
        void setNativePath(String absolutePath);
    }

    /** Outcome of preparing the engine. */
    public static final class Status {

        private final Source source;
        private final String problem;
        private final String nativePath;

        private Status(Source source, String problem, String nativePath) {
            this.source = source;
            this.problem = problem == null ? "" : problem;
            this.nativePath = nativePath == null ? "" : nativePath;
        }

        public Source getSource() {
            return source;
        }

        /** Empty unless the engine cannot run at all. */
        public String getProblem() {
            return problem;
        }

        public String getNativePath() {
            return nativePath;
        }

        public boolean isUsable() {
            return source != Source.UNAVAILABLE;
        }

        /** True when the module was used, i.e. the user opted in and it verified. */
        public boolean isFromModule() {
            return source == Source.MODULE;
        }
    }

    private RuntimeSherpa() {
    }

    /**
     * Verifies the module (when installed) and points the SDK at it.
     *
     * <p>Falls back to the bundled library when no module is installed, so an app that still ships
     * the native library keeps working exactly as before. Only when neither is present does this
     * report {@link Source#UNAVAILABLE}.
     *
     * @param loader       verifies the installed module; may be {@code null}
     * @param abi          the ABI this build must match
     * @param configurer   applies the resolved native path; may be {@code null}
     * @param bundledProbe reports whether the APK still bundles the native library
     */
    public static Status prepare(RuntimeLoader loader, String abi, Configurer configurer, BundledProbe bundledProbe) {
        boolean bundled = bundledProbe != null && bundledProbe.isBundled();

        if (loader == null) {
            return bundled
                    ? new Status(Source.BUNDLED, "", "")
                    : new Status(Source.UNAVAILABLE, "实时字幕模块未安装", "");
        }

        RuntimeLoader.Outcome outcome = loader.verify(MODULE_ID, abi);

        if (outcome.isOk()) {
            File dir = outcome.getLibraryDir();
            String path = dir == null ? "" : dir.getAbsolutePath();
            if (RuntimeText.isBlank(path)) {
                return new Status(Source.UNAVAILABLE, "实时字幕模块目录无效", "");
            }
            if (configurer != null) configurer.setNativePath(path);
            return new Status(Source.MODULE, "", path);
        }

        // The module is absent or failed verification. Prefer the bundled library while it still
        // exists so a rejected module degrades instead of disabling the feature outright.
        if (bundled) return new Status(Source.BUNDLED, "", "");
        return new Status(Source.UNAVAILABLE, describe(outcome), "");
    }

    /** Reports whether the APK still bundles the sherpa native library. */
    public interface BundledProbe {
        boolean isBundled();
    }

    private static String describe(RuntimeLoader.Outcome outcome) {
        String problem = outcome == null ? "" : outcome.getProblem();
        return RuntimeText.isBlank(problem) ? "实时字幕模块不可用" : problem;
    }
}
