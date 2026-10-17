package com.fongmi.android.tv.runtime;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates "download -> verify -> install -> record" for one module artifact.
 *
 * <p>The ordering is the security property, not an implementation detail: the artifact digest is
 * checked <em>before</em> the APK is handed to the platform installer, and the registry is written
 * only after the platform confirms the install. Every failure path leaves the registry untouched.
 *
 * <p>All platform access goes through {@link RuntimePlatform} seams, so this class is exercised on
 * a plain JVM.
 */
public final class RuntimeInstaller {

    private final RuntimePlatform.Downloader downloader;
    private final RuntimePlatform.PackageInstaller installer;
    private final RuntimePlatform.PackageInfo packageInfo;
    private final RuntimeStore store;

    public RuntimeInstaller(
            RuntimePlatform.Downloader downloader,
            RuntimePlatform.PackageInstaller installer,
            RuntimePlatform.PackageInfo packageInfo,
            RuntimeStore store) {
        this.downloader = downloader;
        this.installer = installer;
        this.packageInfo = packageInfo;
        this.store = store;
    }

    /** Outcome of an install attempt. */
    public interface Callback {
        void onProgress(String message);

        void onInstalled(RuntimeStore.Entry entry);

        void onFailed(String message);
    }

    /**
     * Installs {@code module} for {@code abi}.
     *
     * <p>Refuses to proceed when the catalog entry is invalid, the ABI has no artifact, the
     * artifact digest does not match, or the platform install fails.
     */
    public void install(RuntimeCatalog.RuntimeModule module, String abi, File workDir, Callback callback) {
        if (module == null) {
            fail(callback, "缺少模块信息");
            return;
        }
        if (!module.isValid()) {
            fail(callback, "模块清单无效");
            return;
        }
        if (!RuntimeCompatibility.isAccepted(module.compatibilityId)) {
            fail(callback, "不支持的模块契约：" + module.compatibilityId);
            return;
        }

        RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi, 0);
        if (artifact == null) {
            fail(callback, "当前架构没有适配的模块包：" + RuntimeAbi.normalize(abi));
            return;
        }
        if (!artifact.isValid()) {
            fail(callback, "模块包信息无效（缺少摘要或非 HTTPS）");
            return;
        }
        if (workDir == null) {
            fail(callback, "缺少下载目录");
            return;
        }

        File apk = new File(workDir, fileName(artifact.url));
        progress(callback, "下载 " + module.displayVersion() + " ...");

        try {
            downloader.download(artifact.url, apk);
        } catch (IOException e) {
            deleteQuietly(apk);
            fail(callback, "下载失败：" + describe(e));
            return;
        }

        progress(callback, "校验完整性 ...");
        String actual;
        try {
            actual = RuntimeIntegrity.sha256(apk);
        } catch (IOException e) {
            deleteQuietly(apk);
            fail(callback, "无法读取下载文件：" + describe(e));
            return;
        }
        if (!RuntimeIntegrity.matches(artifact.sha256, actual)) {
            // Never hand an unverified APK to the installer: that is the whole point of the digest.
            deleteQuietly(apk);
            fail(callback, "模块包摘要不匹配，已拒绝安装");
            return;
        }
        if (artifact.size > 0 && apk.length() != artifact.size) {
            deleteQuietly(apk);
            fail(callback, "模块包大小不匹配，已拒绝安装");
            return;
        }

        progress(callback, "安装模块 ...");
        AtomicBoolean settled = new AtomicBoolean();
        installer.install(apk, new RuntimePlatform.PackageInstaller.Callback() {
            @Override
            public void onInstalled(String packageName) {
                if (!settled.compareAndSet(false, true)) return;
                deleteQuietly(apk);
                if (RuntimeText.isBlank(packageName)) {
                    fail(callback, "安装结果缺少包名");
                    return;
                }
                if (!packageName.equals(module.packageName)) {
                    // The platform installed something other than the catalog entry we authorized.
                    fail(callback, "安装的包名与清单不符：" + packageName);
                    return;
                }
                if (packageInfo == null || packageInfo.nativeLibraryDir(packageName) == null) {
                    fail(callback, "模块已安装但找不到原生库目录");
                    return;
                }
                RuntimeStore.Entry entry = new RuntimeStore.Entry(module, artifact, System.currentTimeMillis());
                store.put(entry);
                if (callback != null) callback.onInstalled(entry);
            }

            @Override
            public void onFailed(String message) {
                if (!settled.compareAndSet(false, true)) return;
                deleteQuietly(apk);
                fail(callback, RuntimeText.isBlank(message) ? "安装失败" : "安装失败：" + message);
            }
        });
    }

    /** Removes the module record. The APK itself is uninstalled by the platform, not here. */
    public void forget(String id) {
        store.remove(id);
    }

    private static String fileName(String url) {
        String value = url == null ? "" : url.trim();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            safe.append(ok ? c : '_');
        }
        return safe.isEmpty() ? "module.apk" : safe.toString();
    }

    private static void progress(Callback callback, String message) {
        if (callback != null) callback.onProgress(message);
    }

    private static void fail(Callback callback, String message) {
        if (callback != null) callback.onFailed(message);
    }

    private static String describe(Exception e) {
        String message = e == null ? null : e.getMessage();
        return RuntimeText.isBlank(message) ? "未知错误" : message;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
