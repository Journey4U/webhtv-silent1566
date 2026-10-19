package com.fongmi.android.tv.subtitle.source;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.runtime.RuntimePython;
import com.fongmi.chaquo.Loader;
import com.fongmi.chaquo.Spider;

import java.util.concurrent.ConcurrentHashMap;

public final class PythonSubtitleScriptRuntime implements SubtitleScriptRuntime {

    /**
     * Created on first use rather than in the constructor.
     *
     * <p>{@code new Loader()} calls {@code Python.start}, which needs the runtime's payload, and this
     * runtime is constructed while the subtitle provider registry is being set up. Doing it eagerly
     * would make a build without the runtime fail during registry construction rather than reporting
     * the Python subtitle feature as unavailable.
     */
    private volatile Loader loader;
    private final ConcurrentHashMap<String, Spider> spiders;

    public PythonSubtitleScriptRuntime() {
        this.spiders = new ConcurrentHashMap<>();
    }

    /**
     * Uses an already-created bridge. Kept for tests, which supply a stand-in so no Python starts.
     *
     * <p>The bridge is treated as pre-resolved, so no availability check runs: the caller has
     * already decided the runtime is usable.
     */
    PythonSubtitleScriptRuntime(Loader loader) {
        this.loader = loader;
        this.spiders = new ConcurrentHashMap<>();
    }

    /**
     * Whether Python subtitle scripts can be used at all on this build.
     *
     * <p>False when neither an installed module nor a bundled runtime is present, in which case
     * callers should report the feature as unavailable instead of attempting to start Python.
     */
    public static boolean isAvailable() {
        return RuntimePython.isRuntimeAvailable(App.get());
    }

    /**
     * The bridge, created on first use.
     *
     * @throws IllegalStateException when no runtime is available, so the caller reports the feature
     *     rather than a null-pointer failure
     */
    private Loader loader() {
        Loader current = loader;
        if (current != null) return current;
        synchronized (this) {
            current = loader;
            if (current == null) {
                if (!isAvailable()) throw new IllegalStateException("Python 模块未安装");
                current = new Loader();
                loader = current;
            }
            return current;
        }
    }

    @Override
    public String init(String sourceKey, String scriptPath, String config) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey is empty");
        }
        if (scriptPath == null || scriptPath.isBlank()) {
            throw new IllegalArgumentException("scriptPath is empty");
        }
        Spider previous = spiders.remove(sourceKey);
        destroy(previous);
        Spider spider = loader().spider(scriptPath);
        spiders.put(sourceKey, spider);
        try {
            return spider.subtitleInit(config);
        } catch (Throwable error) {
            spiders.remove(sourceKey, spider);
            destroy(spider);
            throw error;
        }
    }

    @Override
    public String search(String sourceKey, String request) {
        return require(sourceKey).subtitleSearch(request);
    }

    @Override
    public String resolve(String sourceKey, String request) {
        return require(sourceKey).subtitleResolve(request);
    }

    @Override
    public void destroy(String sourceKey) {
        destroy(spiders.remove(sourceKey));
    }

    public void destroyAll() {
        for (String sourceKey : spiders.keySet()) destroy(sourceKey);
    }

    private Spider require(String sourceKey) {
        Spider spider = spiders.get(sourceKey);
        if (spider == null) throw new IllegalStateException("Subtitle source is not initialized: " + sourceKey);
        return spider;
    }

    private static void destroy(Spider spider) {
        if (spider == null) return;
        try {
            spider.destroy();
        } catch (Throwable ignored) {
        }
    }
}
