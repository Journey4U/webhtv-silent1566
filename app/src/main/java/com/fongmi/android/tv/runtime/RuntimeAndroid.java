package com.fongmi.android.tv.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android-backed implementations of the {@link RuntimePlatform} seams.
 *
 * <p>This is the only class in the package that touches the platform; everything security-relevant
 * lives in the seam-free classes so it can be exercised on a plain JVM.
 */
public final class RuntimeAndroid {

    private static final String PREF_KEY = "runtime_modules";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final String SESSION_NAME = "runtime-module";

    private RuntimeAndroid() {
    }

    public static RuntimeStore.Storage storage() {
        return new RuntimeStore.Storage() {
            @Override
            public String read() {
                return Prefers.getString(PREF_KEY, "");
            }

            @Override
            public void write(String value) {
                Prefers.put(PREF_KEY, value == null ? "" : value);
            }
        };
    }

    public static RuntimePlatform.Downloader downloader() {
        return (url, target) -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "WebHTV-Runtime");
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("下载失败 HTTP " + code);
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    out.getFD().sync();
                }
            } finally {
                connection.disconnect();
            }
        };
    }

    /**
     * Installs a module APK through a {@link PackageInstaller} session.
     *
     * <p>A session is used instead of {@code ACTION_VIEW} because it reports a definite result:
     * {@code ACTION_VIEW} never tells the caller whether the user confirmed, so the registry could
     * record a module that was never installed.
     *
     * <p>The receiver is registered dynamically and torn down on the first terminal status, so no
     * manifest entry or exported component is needed. Requires device verification (S0-c).
     */
    public static RuntimePlatform.PackageInstaller installer() {
        return (apk, callback) -> {
            Context context = App.get();
            if (context == null) {
                callback.onFailed("应用上下文不可用");
                return;
            }
            String expectedPackage = archivePackageName(context, apk);
            if (expectedPackage == null) {
                callback.onFailed("无法读取模块包信息");
                return;
            }

            AtomicBoolean settled = new AtomicBoolean();
            BroadcastReceiver[] holder = new BroadcastReceiver[1];

            Runnable unregister = () -> {
                BroadcastReceiver receiver = holder[0];
                if (receiver == null) return;
                holder[0] = null;
                try {
                    context.unregisterReceiver(receiver);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered; nothing to undo.
                }
            };

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) return;
                    int status = intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = confirmIntent(intent);
                        if (confirm == null) {
                            if (settled.compareAndSet(false, true)) {
                                unregister.run();
                                callback.onFailed("安装器未提供确认界面");
                            }
                            return;
                        }
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            receiverContext.startActivity(confirm);
                        } catch (Exception e) {
                            if (settled.compareAndSet(false, true)) {
                                unregister.run();
                                callback.onFailed("无法打开安装确认界面");
                            }
                        }
                        return;
                    }
                    if (!settled.compareAndSet(false, true)) return;
                    unregister.run();
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        callback.onInstalled(expectedPackage);
                    } else {
                        callback.onFailed(RuntimeText.isBlank(message) ? "安装失败" : message);
                    }
                }
            };
            holder[0] = receiver;

            IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                        context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (OutputStream out = session.openWrite(SESSION_NAME, 0, apk.length());
                         InputStream in = new java.io.FileInputStream(apk)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        session.fsync(out);
                    }
                    session.commit(statusIntent(context, sessionId).getIntentSender());
                }
            } catch (Exception e) {
                if (settled.compareAndSet(false, true)) {
                    unregister.run();
                    String detail = e.getMessage();
                    callback.onFailed(RuntimeText.isBlank(detail) ? "无法创建安装会话" : detail);
                }
            }
        };
    }

    private static final String ACTION_INSTALL_STATUS = "com.fongmi.android.tv.runtime.INSTALL_STATUS";

    private static android.app.PendingIntent statusIntent(Context context, int sessionId) {
        Intent intent = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The platform fills in EXTRA_STATUS/EXTRA_INTENT, so the intent must be mutable.
            flags |= android.app.PendingIntent.FLAG_MUTABLE;
        }
        return android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags);
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent status) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return status.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return status.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    /** Reads the package name an APK declares, without installing it. */
    private static String archivePackageName(Context context, File apk) {
        PackageInfo info = context.getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info == null ? null : info.packageName;
    }

    public static RuntimePlatform.PackageInfo packageInfo() {
        return new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                ApplicationInfo info = applicationInfo(packageName);
                if (info == null) return null;
                String dir = info.nativeLibraryDir;
                return dir == null ? null : new File(dir);
            }

            @Override
            public int versionCode(String packageName) {
                PackageInfo info = packageInfo(packageName);
                if (info == null) return -1;
                return (int) info.getLongVersionCode();
            }

            @Override
            public File apkPath(String packageName) {
                ApplicationInfo info = applicationInfo(packageName);
                if (info == null) return null;
                String path = info.publicSourceDir == null ? info.sourceDir : info.publicSourceDir;
                return path == null ? null : new File(path);
            }

            @Override
            public String apkDigest(String packageName) {
                File apk = apkPath(packageName);
                if (apk == null || !apk.isFile()) return null;
                try {
                    return RuntimeIntegrity.sha256(apk);
                } catch (IOException e) {
                    return null;
                }
            }
        };
    }

    public static RuntimePlatform.LibraryLoader libraryLoader() {
        return System::load;
    }

    /**
     * Reads a module's {@code assets/runtime.json}.
     *
     * <p>Uses the module's own APK as the asset container so the manifest always travels with the
     * code it describes.
     */
    public static RuntimeLoader.ManifestSource manifestSource() {
        return packageName -> {
            Context context = App.get();
            if (context == null) return null;
            try {
                Context module = context.createPackageContext(packageName, 0);
                try (InputStream in = module.getAssets().open(RuntimeLoader.manifestAssetName())) {
                    byte[] buffer = new byte[8192];
                    StringBuilder text = new StringBuilder();
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        text.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return text.toString();
                }
            } catch (PackageManager.NameNotFoundException | IOException e) {
                return null;
            }
        };
    }

    /**
     * Opens a module's non-library payloads, such as the Python runtime's {@code assets/chaquopy/**}.
     *
     * <p>Reads through the module's own context, the same way {@link #manifestSource()} reads the
     * manifest, so a payload always travels with the APK it was verified against. Returns
     * {@code null} when the path is absent, which the verifier reports as a problem rather than
     * treating as success.
     */
    public static RuntimeVerifier.PayloadReader payloadReader() {
        return (packageName, path) -> {
            Context context = App.get();
            if (context == null || RuntimeText.isBlank(packageName) || RuntimeText.isBlank(path)) {
                return null;
            }
            try {
                Context module = context.createPackageContext(packageName, 0);
                return module.getAssets().open(assetPath(path));
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        };
    }

    /**
     * Converts a manifest path to an {@code AssetManager} path.
     *
     * <p>Manifest paths are written relative to the APK root ({@code assets/chaquopy/build.json}), but
     * {@code AssetManager.open} takes a path relative to the <em>assets</em> directory
     * ({@code chaquopy/build.json}). Passing the manifest path through unchanged fails with
     * {@code FileNotFoundException} for every entry — measured on device, all twenty payload files
     * reported "unreadable" until this strip was added. The sibling {@link #manifestSource()} reads
     * {@code runtime.json} with no prefix for the same reason.
     *
     * <p>Returns {@code null} for a path that is not under {@code assets/}: a package's AssetManager
     * exposes nothing else, so pretending it could be read would misreport the file as merely missing.
     */
    private static String assetPath(String manifestPath) {
        String normalized = manifestPath.trim();
        return normalized.startsWith("assets/") ? normalized.substring("assets/".length()) : null;
    }

    /** Resolves a host-provided library from the app's own native library directory. */
    public static RuntimeVerifier.HostLibraries hostLibraries() {
        return name -> {
            Context context = App.get();
            if (context == null) return null;
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.nativeLibraryDir == null) return null;
            File candidate = new File(info.nativeLibraryDir, name);
            if (candidate.isFile()) return candidate;
            // Fall back to the extracted mpv bundle, which is where libc++_shared.so also lives.
            File extracted = new File(context.getDir("mpv-libs", Context.MODE_PRIVATE),
                    RuntimeAbi.resolve(BuildConfig.FLAVOR_abi, Build.SUPPORTED_ABIS));
            File bundled = new File(extracted, name);
            return bundled.isFile() ? bundled : null;
        };
    }

    public static RuntimeStore store() {
        return new RuntimeStore(storage());
    }

    public static RuntimeInstaller installer(RuntimeStore store) {
        return new RuntimeInstaller(downloader(), installer(), packageInfo(), store);
    }

    public static RuntimeLoader loader(RuntimeStore store) {
        return new RuntimeLoader(store, packageInfo(), libraryLoader(), hostLibraries(),
                manifestSource(), payloadReader());
    }

    /** Directory used for module downloads before they are handed to the installer. */
    public static File downloadDir() {
        Context context = App.get();
        if (context == null) return null;
        File dir = new File(context.getCacheDir(), "runtime");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private static ApplicationInfo applicationInfo(String packageName) {
        Context context = App.get();
        if (context == null || packageName == null) return null;
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static PackageInfo packageInfo(String packageName) {
        Context context = App.get();
        if (context == null || packageName == null) return null;
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
