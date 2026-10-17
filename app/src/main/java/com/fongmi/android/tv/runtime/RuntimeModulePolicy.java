package com.fongmi.android.tv.runtime;

import java.util.Locale;

/**
 * Derives what the module list should show for one catalog entry.
 *
 * <p>Pure logic on purpose: the UI must not re-derive these rules, and this is the part worth
 * testing without a device.
 */
public final class RuntimeModulePolicy {

    private RuntimeModulePolicy() {
    }

    /** What the user can do with a module right now. */
    public enum State {
        /** Not installed; an artifact exists for this device's ABI. */
        INSTALL,
        /** Installed and current. */
        INSTALLED,
        /** A newer version is available in the catalog. */
        UPGRADE,
        /** Installed, but the catalog offers no artifact for this ABI or none at all. */
        INSTALLED_NO_ARTIFACT,
        /** Not installed and no artifact exists for this device's ABI. */
        UNSUPPORTED_ABI,
        /** Not installed, but this build cannot talk to the module's contract. */
        UNSUPPORTED_CONTRACT
    }

    public static State state(RuntimeCatalog.RuntimeModule module, RuntimeStore.Entry installed, String abi, int minSdk) {
        if (module == null || !module.isValid()) return State.UNSUPPORTED_CONTRACT;

        boolean compatible = RuntimeCompatibility.isAccepted(module.compatibilityId);
        RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi, minSdk);

        if (installed == null) {
            if (!compatible) return State.UNSUPPORTED_CONTRACT;
            return artifact == null ? State.UNSUPPORTED_ABI : State.INSTALL;
        }
        if (artifact == null) return State.INSTALLED_NO_ARTIFACT;
        if (installed.versionCode < module.versionCode) return State.UPGRADE;
        return State.INSTALLED;
    }

    /** Whether the row should offer an install/upgrade action. */
    public static boolean canInstall(State state) {
        return state == State.INSTALL || state == State.UPGRADE;
    }

    /** Whether the row should offer an uninstall action. */
    public static boolean canUninstall(State state) {
        return state == State.INSTALLED || state == State.UPGRADE || state == State.INSTALLED_NO_ARTIFACT;
    }

    /** Human-readable size. Uses binary units to match how downloads are reported. */
    public static String displaySize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        // Start below the first unit: the loop divides first, so the index must advance with the
        // division. Starting at 0 would label every value in [1 KB, 1 MB) as megabytes.
        int unit = -1;
        double value = bytes;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
