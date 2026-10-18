package com.fongmi.android.tv.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies an installed module and loads its native libraries.
 *
 * <p>Nothing reaches {@code System.load} until the module has been proven to match the catalog it
 * was installed from, to agree with the host's shared libraries, and to have intact files. A
 * failure here is a hard stop, never a fallback to an unverified path.
 */
public final class RuntimeLoader {

    /** Reads the module's {@code assets/runtime.json}. Injected so tests need no real APK. */
    public interface ManifestSource {
        /** Returns the raw manifest JSON, or {@code null} when the module ships none. */
        String read(String packageName) throws IOException;
    }

    private static final String MANIFEST_ASSET = "runtime.json";

    private final RuntimePlatform.PackageInfo packageInfo;
    private final RuntimePlatform.LibraryLoader libraryLoader;
    private final RuntimeVerifier.HostLibraries hostLibraries;
    private final ManifestSource manifestSource;
    private final RuntimeStore store;

    public RuntimeLoader(
            RuntimeStore store,
            RuntimePlatform.PackageInfo packageInfo,
            RuntimePlatform.LibraryLoader libraryLoader,
            RuntimeVerifier.HostLibraries hostLibraries,
            ManifestSource manifestSource) {
        this.store = store;
        this.packageInfo = packageInfo;
        this.libraryLoader = libraryLoader;
        this.hostLibraries = hostLibraries;
        this.manifestSource = manifestSource;
    }

    public static String manifestAssetName() {
        return MANIFEST_ASSET;
    }

    /**
     * Verifies the installed module.
     *
     * <p>Returns a failure result rather than throwing, so callers can degrade to another engine
     * while still surfacing why the module was rejected.
     */
    public Outcome verify(String id, String expectedAbi) {
        RuntimeStore.Entry entry = store == null ? null : store.get(id);
        if (entry == null) return Outcome.failure("模块未安装：" + id);
        if (!entry.isValid()) return Outcome.failure("模块记录无效：" + id);
        if (!RuntimeCompatibility.isAccepted(entry.compatibilityId)) {
            return Outcome.failure("不支持的模块契约：" + entry.compatibilityId);
        }
        if (packageInfo == null) return Outcome.failure("平台信息不可用");

        File libDir = packageInfo.nativeLibraryDir(entry.packageName);
        if (libDir == null || !libDir.isDirectory()) {
            return Outcome.failure("找不到模块原生库目录：" + entry.packageName);
        }

        RuntimeManifest manifest;
        try {
            manifest = readManifest(entry.packageName);
        } catch (IOException e) {
            return Outcome.failure("无法读取模块清单：" + describe(e));
        }
        if (manifest == null) return Outcome.failure("模块缺少 " + MANIFEST_ASSET + " 清单");

        RuntimeCatalog.RuntimeModule module = toCatalogModule(entry);
        RuntimeVerifier.Result result =
                RuntimeVerifier.verify(module, manifest, libDir, expectedAbi, hostLibraries);
        if (!result.isOk()) return Outcome.failure(result.describe());

        File entryLibrary = new File(libDir, RuntimeManifest.nativeLibraryEntry(result.getEntryLibrary(), manifest.abi));
        if (!entryLibrary.isFile()) {
            return Outcome.failure("模块入口库不存在：" + result.getEntryLibrary());
        }
        return Outcome.success(entry, libDir, manifest, entryLibrary);
    }

    /**
     * Verifies and loads the module's libraries, in the order the manifest declares.
     *
     * <p>Loading order is part of the contract, not an implementation detail: the dynamic linker
     * resolves each {@code DT_NEEDED} against what is already loaded, so a bundle whose libraries
     * depend on each other (mpv is nine) fails if the order is wrong.
     */
    public Outcome load(String id, String expectedAbi) {
        Outcome outcome = verify(id, expectedAbi);
        if (!outcome.isOk()) return outcome;
        if (libraryLoader == null) return Outcome.failure("原生库加载器不可用");
        RuntimeManifest manifest = outcome.getManifest();
        for (String declared : manifest.librariesToLoad()) {
            String entry = RuntimeManifest.nativeLibraryEntry(declared, manifest.abi);
            File file = new File(outcome.getLibraryDir(), entry);
            if (!file.isFile()) {
                return Outcome.failure("模块库不存在：" + declared);
            }
            try {
                libraryLoader.load(file.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                return Outcome.failure("模块原生库加载失败（" + entry + "）：" + describe(e));
            } catch (RuntimeException e) {
                return Outcome.failure("模块原生库加载异常（" + entry + "）：" + describe(e));
            }
        }
        return outcome;
    }

    private RuntimeManifest readManifest(String packageName) throws IOException {
        if (manifestSource == null) return null;
        String raw = manifestSource.read(packageName);
        if (RuntimeText.isBlank(raw)) return null;
        return RuntimeManifest.parse(raw);
    }

    /**
     * Rebuilds the catalog view of an installed module.
     *
     * <p>{@link RuntimeVerifier} compares the catalog against the manifest, so the recorded entry
     * is replayed through the same shape. The artifact digest recorded at install time is carried
     * through so a swapped APK is caught by the identity comparison.
     */
    private static RuntimeCatalog.RuntimeModule toCatalogModule(RuntimeStore.Entry entry) {
        RuntimeCatalog.RuntimeArtifact artifact = new RuntimeCatalog.RuntimeArtifact();
        artifact.abi = entry.abi;
        artifact.sha256 = entry.sha256;
        artifact.url = "https://installed.invalid/" + entry.id;
        artifact.size = entry.size > 0 ? entry.size : 1;

        RuntimeCatalog.RuntimeModule module = new RuntimeCatalog.RuntimeModule();
        module.id = entry.id;
        module.packageName = entry.packageName;
        module.compatibilityId = entry.compatibilityId;
        module.versionCode = entry.versionCode;
        module.versionName = entry.versionName;
        module.artifacts = new ArrayList<>(List.of(artifact));
        return module;
    }

    private static String describe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return RuntimeText.isBlank(message) ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : message;
    }

    /** Result of a verify/load attempt. */
    public static final class Outcome {

        private final boolean ok;
        private final String problem;
        private final RuntimeStore.Entry entry;
        private final File libraryDir;
        private final RuntimeManifest manifest;
        private final File entryLibrary;

        private Outcome(boolean ok, String problem, RuntimeStore.Entry entry,
                        File libraryDir, RuntimeManifest manifest, File entryLibrary) {
            this.ok = ok;
            this.problem = problem == null ? "" : problem;
            this.entry = entry;
            this.libraryDir = libraryDir;
            this.manifest = manifest;
            this.entryLibrary = entryLibrary;
        }

        static Outcome success(RuntimeStore.Entry entry, File libraryDir,
                               RuntimeManifest manifest, File entryLibrary) {
            return new Outcome(true, "", entry, libraryDir, manifest, entryLibrary);
        }

        static Outcome failure(String problem) {
            return new Outcome(false, problem, null, null, null, null);
        }

        public boolean isOk() {
            return ok;
        }

        public String getProblem() {
            return problem;
        }

        public RuntimeStore.Entry getEntry() {
            return entry;
        }

        public File getLibraryDir() {
            return libraryDir;
        }

        public RuntimeManifest getManifest() {
            return manifest;
        }

        public File getEntryLibrary() {
            return entryLibrary;
        }
    }

    /** Convenience: reads a manifest from a module APK's {@code assets} via a plain file tree. */
    public static ManifestSource fileTreeSource(File moduleRoot) {
        return packageName -> {
            File file = new File(moduleRoot, "assets/" + MANIFEST_ASSET);
            if (!file.isFile()) return null;
            // Read with an explicit loop: InputStream.readAllBytes() is a Java 9 API that does not
            // exist on the device runtimes this app supports, and core-library desugaring does not
            // cover java.io. It compiles and passes on the JVM, then throws NoSuchMethodError on
            // device, so the loop is not a stylistic choice.
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        };
    }
}
