package tv.danmaku.ijk.media.player;

import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.util.Arrays;

/* loaded from: classes3.dex */
public abstract class IjkLibLoader {
    private static final String TAG = "IjkLibLoader";
    private boolean isAvailable;
    private boolean loadAttempted;
    private final String[] nativeLibraries;

    /**
     * Optional resolver for libraries that live outside the host's own native library directory.
     *
     * <p>{@code System.loadLibrary} searches only the host's {@code nativeLibraryDir}, so a library
     * delivered as an installable module is invisible to it. When a resolver is installed and
     * returns a file for a bare name (e.g. {@code ijkffmpeg}), that absolute path is loaded
     * instead, which lets the module's copy win without touching the search path.
     *
     * <p>Set once during startup; see {@code RuntimeIjk}.
     */
    public interface Resolver {
        /** Returns the file to load for a bare library name, or {@code null} to fall back. */
        File resolve(String name);
    }

    private static volatile Resolver resolver;

    public static void setResolver(Resolver value) {
        resolver = value;
    }

    public static Resolver getResolver() {
        return resolver;
    }

    public IjkLibLoader(String... strArr) {
        this.nativeLibraries = strArr;
    }

    public synchronized boolean isAvailable() {
        if (this.loadAttempted) {
            return this.isAvailable;
        }
        this.loadAttempted = true;
        try {
            for (String str : this.nativeLibraries) {
                loadLibrary(str);
            }
            this.isAvailable = true;
        } catch (Throwable unused) {
            Log.w(TAG, "Failed to load " + Arrays.toString(this.nativeLibraries));
        }
        return this.isAvailable;
    }

    /**
     * Loads one library, preferring a resolver-provided absolute path.
     *
     * <p>Kept concrete so every subclass benefits: the bundled loader is generated-looking code
     * that only called {@code System.loadLibrary}, and a module-delivered library would otherwise
     * be unreachable from it.
     */
    protected void loadLibraryOrResolved(String str) {
        Resolver current = resolver;
        if (current != null && !TextUtils.isEmpty(str)) {
            File file = current.resolve(str);
            if (file != null && file.isFile()) {
                System.load(file.getAbsolutePath());
                return;
            }
        }
        System.loadLibrary(str);
    }

    public abstract void loadLibrary(String str);
}
