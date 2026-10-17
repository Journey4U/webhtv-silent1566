package com.fongmi.android.tv.runtime;

import java.util.Locale;

/**
 * ABI normalization shared by the catalog and the module manifest.
 *
 * <p>Mirrors {@code LabEnv.normalizeAbi} so both package systems agree on what
 * {@code arm64-v8a} means; the packaged flavor stays authoritative over
 * {@code Build.SUPPORTED_ABIS} because a device can report both ABIs while the
 * APK only ships one.
 */
public final class RuntimeAbi {

    public static final String ARM64 = "arm64-v8a";
    public static final String ARMV7 = "armeabi-v7a";

    private RuntimeAbi() {
    }

    public static String normalize(String abi) {
        if (RuntimeText.isBlank(abi)) return "";
        String value = abi.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (ARM64.equals(value) || "arm64".equals(value) || "aarch64".equals(value)) return ARM64;
        if (ARMV7.equals(value)
                || "armeabi".equals(value)
                || "armv7".equals(value)
                || "armv7-a".equals(value)
                || "armv7a".equals(value)) {
            return ARMV7;
        }
        return value;
    }

    public static boolean isSupported(String abi) {
        String value = normalize(abi);
        return ARM64.equals(value) || ARMV7.equals(value);
    }

    /**
     * Resolves the ABI a module must match.
     *
     * <p>The packaged flavor wins when it is a supported ABI; an explicit but unsupported
     * flavor yields an empty string instead of silently pretending to be arm64.
     */
    public static String resolve(String packagedAbi, String[] supportedAbis) {
        String packaged = normalize(packagedAbi);
        if (isSupported(packaged)) return packaged;
        if (!RuntimeText.isBlank(packagedAbi)) return "";
        if (supportedAbis != null) {
            for (String abi : supportedAbis) {
                String normalized = normalize(abi);
                if (isSupported(normalized)) return normalized;
            }
        }
        return "";
    }
}
