package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.runtime.RuntimePython;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private static final String PY_TAG = "python-spider";

    private final ConcurrentHashMap<String, Spider> spiders;
    /**
     * Created on first use rather than in the constructor.
     *
     * <p>{@code new Loader()} calls {@code Python.start}, which needs the runtime's payload. The
     * constructor is reached from {@code BaseLoader}'s static initialiser, so doing this eagerly would
     * make a build without the runtime fail while loading configuration rather than merely reporting
     * the Python feature as unavailable. Creating it lazily keeps that failure inside the one call
     * that actually needs Python.
     */
    private volatile Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    /**
     * Whether Python sources can be used at all on this build.
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

    public void clear() {
        try {
            SpiderCleanup.destroy("py-loader", spiders);
        } finally {
            spiders.clear();
            recent = null;
        }
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public java.util.Set<String> activeKeys() {
        return java.util.Set.copyOf(spiders.keySet());
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader().spider(api);
                spider.siteKey = key;
                spider.init(App.get(), normalizeExt(ext));
                return spider;
            } catch (Throwable e) {
                // A failed bridge init is otherwise hard to attribute: SpiderNull then
                // returns "" for every method, so home/category/search just render empty.
                // Record it under the python-spider tag (already routed to the NETWORK
                // category) and name the callable that failed, because the stack alone
                // does not tell whether it was init(), getDependence() or the import.
                SpiderDebug.log(PY_TAG, "init failed key=%s api=%s error=%s:%s", key, api, e.getClass().getName(), e.getMessage());
                SpiderDebug.log(PY_TAG, e);
                // SpiderDebug.log() is gated on DebugLogStore.acceptsTag(), which is false
                // whenever 调试日志 is off (the default).  Relying on it alone would make a
                // failed init invisible in Logcat even though this catch is the only place
                // that explains an all-empty site.  Keep the unconditional channel too, as
                // the sibling JsLoader does for its own init failure.
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    private String normalizeExt(String ext) {
        String value = TextUtils.isEmpty(ext) ? "" : ext.trim();
        // Many live Python spiders treat ext as an option object and call .get().
        // TV-style configs commonly express an empty extension as [], which would
        // otherwise deserialize to a list and crash those spiders during init.
        return "[]".equals(value) ? "{}" : ext;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
