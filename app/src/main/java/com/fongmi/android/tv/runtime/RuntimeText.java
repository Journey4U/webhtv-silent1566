package com.fongmi.android.tv.runtime;

/**
 * Plain-Java string helpers for the runtime contract layer.
 *
 * <p>The contract classes deliberately avoid {@code android.text.TextUtils}: unit tests for this
 * package run on a plain JVM without Robolectric, and enabling
 * {@code unitTests.returnDefaultValues} would silently make {@code TextUtils.isEmpty} return
 * {@code false} for every input.
 */
final class RuntimeText {

    private RuntimeText() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static boolean isNotBlank(String value) {
        return !isBlank(value);
    }
}
