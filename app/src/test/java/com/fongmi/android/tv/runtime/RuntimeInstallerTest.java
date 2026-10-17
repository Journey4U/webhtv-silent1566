package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Install ordering is the security property under test: the artifact digest must be checked
 * before the APK reaches the platform installer, and the registry must stay untouched on every
 * failure path.
 */
public class RuntimeInstallerTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] APK_BYTES = "module-apk-payload".getBytes(StandardCharsets.UTF_8);
    private static final String APK_SHA = RuntimeIntegrity.sha256(APK_BYTES);

    /** Records the order of observable side effects so ordering can be asserted. */
    private final List<String> events = new ArrayList<>();

    private static final class MemoryStorage implements RuntimeStore.Storage {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value;
        }
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final RuntimeStore store = new RuntimeStore(storage);

    private static final class Result implements RuntimeInstaller.Callback {
        String installed;
        String failed;
        final List<String> progress = new ArrayList<>();

        @Override
        public void onProgress(String message) {
            progress.add(message);
        }

        @Override
        public void onInstalled(RuntimeStore.Entry entry) {
            installed = entry == null ? "" : entry.id;
        }

        @Override
        public void onFailed(String message) {
            failed = message;
        }
    }

    private String catalogJson(String url, long size, String sha) {
        return "{"
                + "\"schema\":1,\"modules\":[{"
                + "\"id\":\"sherpa\","
                + "\"packageName\":\"com.silent.runtime.sherpa\","
                + "\"compatibilityId\":\"sherpa-onnx-jni-v1\","
                + "\"versionCode\":3,"
                + "\"versionName\":\"1.3.0\","
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":" + size + ","
                + "\"sha256\":\"" + sha + "\","
                + "\"url\":\"" + url + "\"}]"
                + "}]}";
    }

    private RuntimeCatalog.RuntimeModule module(String url, long size, String sha) {
        return GSON.fromJson(catalogJson(url, size, sha), RuntimeCatalog.class).find("sherpa");
    }

    private RuntimeCatalog.RuntimeModule validModule() {
        return module("https://example.invalid/sherpa.apk", APK_BYTES.length, APK_SHA);
    }

    private RuntimePlatform.Downloader downloaderWriting(byte[] payload) {
        return (url, target) -> {
            events.add("download");
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), payload);
        };
    }

    private RuntimePlatform.Downloader failingDownloader() {
        return (url, target) -> {
            events.add("download");
            throw new IOException("network down");
        };
    }

    private RuntimePlatform.PackageInstaller installerReporting(String packageName) {
        return (apk, callback) -> {
            events.add("install");
            callback.onInstalled(packageName);
        };
    }

    private RuntimePlatform.PackageInfo packageInfo(File libDir) {
        return new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                return libDir;
            }

            @Override
            public int versionCode(String packageName) {
                return 3;
            }

            @Override
            public File apkPath(String packageName) {
                return null;
            }

            @Override
            public String apkDigest(String packageName) {
                return null;
            }
        };
    }

    private RuntimeInstaller installer(RuntimePlatform.Downloader downloader,
                                       RuntimePlatform.PackageInstaller packageInstaller,
                                       RuntimePlatform.PackageInfo info) {
        return new RuntimeInstaller(downloader, packageInstaller, info, store);
    }

    private File workDir() throws IOException {
        return folder.newFolder("work");
    }

    @Test
    public void installsVerifiedArtifactAndRecordsIt() throws IOException {
        File libDir = folder.newFolder("libs");
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNull(result.failed);
        assertEquals("sherpa", result.installed);
        assertEquals(List.of("download", "install"), events);
        assertTrue(store.isInstalled("sherpa"));
        assertEquals(APK_SHA, store.get("sherpa").sha256);
    }

    @Test
    public void verifiesDigestBeforeHandingTheApkToTheInstaller() throws IOException {
        File libDir = folder.newFolder("libs");
        // The downloader writes different bytes than the catalog promises.
        RuntimeInstaller installer = installer(
                downloaderWriting("tampered-payload".getBytes(StandardCharsets.UTF_8)),
                installerReporting("com.silent.runtime.sherpa"),
                packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("摘要不匹配"));
        // The installer must never have been reached.
        assertFalse(events.toString(), events.contains("install"));
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void rejectsSizeMismatch() throws IOException {
        File libDir = folder.newFolder("libs");
        RuntimeCatalog.RuntimeModule module = module(
                "https://example.invalid/sherpa.apk", APK_BYTES.length + 100, APK_SHA);
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(libDir));

        Result result = new Result();
        installer.install(module, "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("大小不匹配"));
        assertFalse(events.contains("install"));
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void removesTheDownloadedApkAfterASuccessfulInstall() throws IOException {
        File libDir = folder.newFolder("libs");
        File work = workDir();
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(libDir));

        installer.install(validModule(), "arm64-v8a", work, new Result());

        File[] leftovers = work.listFiles();
        assertTrue("download should be cleaned up", leftovers == null || leftovers.length == 0);
    }

    @Test
    public void removesTheDownloadedApkAfterAFailedInstall() throws IOException {
        File libDir = folder.newFolder("libs");
        File work = workDir();
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES),
                (apk, callback) -> callback.onFailed("user cancelled"),
                packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", work, result);

        assertNotNull(result.failed);
        File[] leftovers = work.listFiles();
        assertTrue("failed install should not leave the apk behind", leftovers == null || leftovers.length == 0);
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void rejectsPackageNameThatDoesNotMatchTheCatalog() throws IOException {
        File libDir = folder.newFolder("libs");
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.evil.runtime.sherpa"), packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("包名与清单不符"));
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void rejectsInstallWhenNativeLibraryDirIsMissing() throws IOException {
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(null));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("找不到原生库目录"));
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void reportsDownloadFailureWithoutInstalling() throws IOException {
        File libDir = folder.newFolder("libs");
        RuntimeInstaller installer = installer(failingDownloader(), installerReporting("com.silent.runtime.sherpa"), packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("下载失败"));
        assertFalse(events.contains("install"));
        assertFalse(store.isInstalled("sherpa"));
    }

    @Test
    public void rejectsUnsupportedCompatibilityContractBeforeDownloading() throws IOException {
        RuntimeCatalog.RuntimeModule module = module(
                "https://example.invalid/sherpa.apk", APK_BYTES.length, APK_SHA);
        module.compatibilityId = "unknown-contract-v9";
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(folder.newFolder("libs")));

        Result result = new Result();
        installer.install(module, "arm64-v8a", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("不支持的模块契约"));
        assertFalse(events.contains("download"));
    }

    @Test
    public void rejectsAbiWithoutArtifact() throws IOException {
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(folder.newFolder("libs")));

        Result result = new Result();
        installer.install(validModule(), "x86_64", workDir(), result);

        assertNotNull(result.failed);
        assertTrue(result.failed, result.failed.contains("没有适配的模块包"));
        assertFalse(events.contains("download"));
    }

    @Test
    public void rejectsInvalidModuleAndNullInputs() throws IOException {
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES), installerReporting("com.silent.runtime.sherpa"), packageInfo(folder.newFolder("libs")));

        Result nullModule = new Result();
        installer.install(null, "arm64-v8a", workDir(), nullModule);
        assertNotNull(nullModule.failed);

        Result noWorkDir = new Result();
        installer.install(validModule(), "arm64-v8a", null, noWorkDir);
        assertNotNull(noWorkDir.failed);
        assertTrue(noWorkDir.failed, noWorkDir.failed.contains("下载目录"));
    }

    @Test
    public void ignoresRepeatedInstallerCallbacks() throws IOException {
        File libDir = folder.newFolder("libs");
        RuntimeInstaller installer = installer(
                downloaderWriting(APK_BYTES),
                (apk, callback) -> {
                    callback.onInstalled("com.silent.runtime.sherpa");
                    // A misbehaving platform must not be able to overwrite a settled result.
                    callback.onFailed("late failure");
                },
                packageInfo(libDir));

        Result result = new Result();
        installer.install(validModule(), "arm64-v8a", workDir(), result);

        assertNull(result.failed);
        assertEquals("sherpa", result.installed);
    }

    @Test
    public void forgetRemovesTheRecordOnly() {
        store.put(new RuntimeStore.Entry(validModule(), validModule().findArtifact("arm64-v8a", 34), 1L));

        new RuntimeInstaller(null, null, null, store).forget("sherpa");

        assertFalse(store.isInstalled("sherpa"));
    }
}
