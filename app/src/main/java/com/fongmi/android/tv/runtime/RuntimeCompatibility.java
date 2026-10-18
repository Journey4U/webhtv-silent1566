package com.fongmi.android.tv.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The set of module ABI contracts this host build can actually talk to.
 *
 * <p>A module built against a different contract is rejected rather than loaded, because the
 * JNI surface and the host-provided libraries must match exactly. The set is intentionally a
 * literal list: adding a contract is a deliberate host change, never something a remote
 * catalog can talk the host into.
 */
public final class RuntimeCompatibility {

    /** Module id -> the compatibility contracts accepted for that id. */
    private static final Set<String> ACCEPTED = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(
            // Reserved for the first shipped module (P2 sherpa-onnx pilot).
            "sherpa-onnx-jni-v1",
            // Reserved for the MPV module (P1); must stay in sync with the host libc++ digest.
            "mpv-jni-ffmpeg-shared-v1",
            // The IJK module (P4). Its libraries depend only on system libraries, so there is no
            // host-library digest to keep in sync.
            "ijk-jni-v1"
    )));

    private RuntimeCompatibility() {
    }

    public static Set<String> accepted() {
        return ACCEPTED;
    }

    public static boolean isAccepted(String compatibilityId) {
        return RuntimeText.isNotBlank(compatibilityId) && ACCEPTED.contains(compatibilityId.trim());
    }
}
