package com.fongmi.android.tv.subtitle.source;

import android.os.Build;

/** Selects the runtime used by subscription subtitle sources. */
public final class SubtitleScriptRuntimeFactory {

    private final SubtitleScriptRuntime pythonRuntime;

    public SubtitleScriptRuntimeFactory() {
        this(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? new PythonSubtitleScriptRuntime() : null);
    }

    SubtitleScriptRuntimeFactory(SubtitleScriptRuntime pythonRuntime) {
        this.pythonRuntime = pythonRuntime;
    }

    public SubtitleScriptRuntime create(String api) {
        String value = api == null ? "" : api.trim().toLowerCase();
        boolean python = "py".equals(value) || "python".equals(value);
        if (python && pythonRuntime == null) {
            throw new UnsupportedOperationException("Python subtitle sources require Android 7 or newer");
        }
        if (python) return pythonRuntime;
        throw new IllegalArgumentException("Unsupported subtitle runtime: " + api);
    }
}