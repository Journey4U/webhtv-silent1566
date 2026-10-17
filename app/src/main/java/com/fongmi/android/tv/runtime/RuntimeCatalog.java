package com.fongmi.android.tv.runtime;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The module catalog served by the separate plugin repository.
 *
 * <p>Field names mirror the upstream {@code catalog.json} contract so a catalog can be
 * consumed without a translation layer.
 */
public final class RuntimeCatalog {

    /** Highest catalog schema this build understands. Unknown schemas are rejected. */
    public static final int SUPPORTED_SCHEMA = 1;

    @SerializedName("schema")
    public int schema;

    @SerializedName("modules")
    public List<RuntimeModule> modules;

    public List<RuntimeModule> modules() {
        return modules == null ? Collections.emptyList() : modules;
    }

    public boolean isSupported() {
        return schema > 0 && schema <= SUPPORTED_SCHEMA;
    }

    public RuntimeModule find(String id) {
        if (RuntimeText.isBlank(id)) return null;
        for (RuntimeModule module : modules()) {
            if (module != null && id.equals(module.id)) return module;
        }
        return null;
    }

    public static final class RuntimeModule {

        @SerializedName("id")
        public String id;

        @SerializedName("packageName")
        public String packageName;

        @SerializedName("compatibilityId")
        public String compatibilityId;

        @SerializedName("versionCode")
        public int versionCode;

        @SerializedName("versionName")
        public String versionName;

        @SerializedName("artifacts")
        public List<RuntimeArtifact> artifacts;

        public List<RuntimeArtifact> artifacts() {
            return artifacts == null ? Collections.emptyList() : artifacts;
        }

        public boolean isValid() {
            if (RuntimeText.isBlank(id) || RuntimeText.isBlank(packageName)) return false;
            if (RuntimeText.isBlank(compatibilityId) || versionCode <= 0) return false;
            for (RuntimeArtifact artifact : artifacts()) {
                if (artifact == null || !artifact.isValid()) return false;
            }
            return !artifacts().isEmpty();
        }

        /** Picks the artifact for {@code abi}, requiring {@code minSdk <= deviceSdk}. */
        public RuntimeArtifact findArtifact(String abi, int deviceSdk) {
            String wanted = RuntimeAbi.normalize(abi);
            if (wanted.isEmpty()) return null;
            for (RuntimeArtifact artifact : artifacts()) {
                if (artifact == null) continue;
                if (!wanted.equals(RuntimeAbi.normalize(artifact.abi))) continue;
                if (artifact.minSdk > 0 && deviceSdk > 0 && deviceSdk < artifact.minSdk) continue;
                return artifact;
            }
            return null;
        }

        public String displayVersion() {
            return RuntimeText.isBlank(versionName) ? String.valueOf(versionCode) : versionName;
        }
    }

    public static final class RuntimeArtifact {

        @SerializedName("abi")
        public String abi;

        @SerializedName("minSdk")
        public int minSdk;

        @SerializedName("size")
        public long size;

        @SerializedName("sha256")
        public String sha256;

        @SerializedName("url")
        public String url;

        public boolean isValid() {
            return !RuntimeAbi.normalize(abi).isEmpty()
                    && size > 0
                    && RuntimeIntegrity.isSha256(sha256)
                    && isHttps(url);
        }

        /** Only HTTPS is accepted; a plaintext artifact URL is a hard failure. */
        public static boolean isHttps(String url) {
            return !RuntimeText.isBlank(url)
                    && url.trim().toLowerCase(Locale.ROOT).startsWith("https://");
        }
    }
}
