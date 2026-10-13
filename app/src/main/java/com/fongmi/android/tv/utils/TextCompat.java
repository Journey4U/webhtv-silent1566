package com.fongmi.android.tv.utils;

import android.os.Build;
import android.text.Html;

public final class TextCompat {

    private TextCompat() {
    }

    /**
     * Converts HTML to plain text on Android 6 as well as Android 7+. The flagged overload was
     * introduced in API 24; the legacy overload preserves the same source behavior on API 23.
     */
    public static CharSequence fromHtml(String source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY);
        }
        return Html.fromHtml(source);
    }
}
