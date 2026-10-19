package com.fongmi.android.tv.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
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
            "ijk-jni-v1",
            // The DV5 GPU renderer module. Verified with readelf that its library depends only on
            // system libraries (libandroid/liblog/libmediandk/libvulkan/libdl/libm/libc) and carries
            // a soname, so like IJK it needs no host-library digest.
            "exo-dv5-renderer-v1"
    )));

    /**
     * Module id -> the package name the host will load that module from.
     *
     * <p>Deliberately a literal host-side table, like {@link #ACCEPTED}: knowing which package
     * provides a module is a host decision, not something a remote catalog may choose. It also lets
     * the host load a module that was installed by some other route (sideload, file manager) and
     * therefore has no entry in the host's install registry.
     */
    private static final Map<String, String> PACKAGES = Map.of(
            "sherpa", "com.silent.runtime.sherpa",
            "mpv", "com.silent.runtime.mpv",
            "ijk", "com.silent.runtime.ijk",
            "dv5", "com.silent.runtime.dv5"
    );

    private RuntimeCompatibility() {
    }

    /** Returns the package name for a module id, or {@code null} when the id is unknown. */
    public static String packageName(String id) {
        return RuntimeText.isBlank(id) ? null : PACKAGES.get(id.trim());
    }

    public static Set<String> accepted() {
        return ACCEPTED;
    }

    public static boolean isAccepted(String compatibilityId) {
        return RuntimeText.isNotBlank(compatibilityId) && ACCEPTED.contains(compatibilityId.trim());
    }
}
