package com.fongmi.android.tv.runtime;

import java.io.File;
import java.io.IOException;

/**
 * Narrow seams for the parts of module installation that need the platform.
 *
 * <p>Keeping these as interfaces lets {@link RuntimeInstaller} and {@link RuntimeLoader} own the
 * security-relevant ordering (verify before install, verify before load) while staying testable
 * on a plain JVM. The Android-backed implementations live in {@link RuntimeAndroid}.
 */
public final class RuntimePlatform {

    private RuntimePlatform() {
    }

    /** Downloads a catalog artifact to a local file. */
    public interface Downloader {
        /**
         * Fetches {@code url} into {@code target}.
         *
         * @throws IOException when the transfer fails or is incomplete
         */
        void download(String url, File target) throws IOException;
    }

    /** Installs a downloaded module APK and reports the resulting package name. */
    public interface PackageInstaller {
        /**
         * Installs {@code apk}.
         *
         * <p>Implementations may be asynchronous; the callback must fire exactly once.
         */
        void install(File apk, Callback callback);

        interface Callback {
            void onInstalled(String packageName);

            void onFailed(String message);
        }
    }

    /** Reports the absolute path of an installed package's native library directory. */
    public interface PackageInfo {
        /** Returns the native library directory, or {@code null} when the package is not installed. */
        File nativeLibraryDir(String packageName);

        /** Returns the installed version code, or {@code -1} when the package is not installed. */
        int versionCode(String packageName);

        /** Returns the installed APK's path, or {@code null} when the package is not installed. */
        File apkPath(String packageName);

        /** Returns the digest of the installed APK, or {@code null} when it cannot be read. */
        String apkDigest(String packageName);
    }

    /** Loads a native library from an absolute path. */
    public interface LibraryLoader {
        void load(String absolutePath) throws UnsatisfiedLinkError;
    }
}
