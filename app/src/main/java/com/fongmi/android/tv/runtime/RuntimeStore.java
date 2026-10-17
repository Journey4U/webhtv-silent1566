package com.fongmi.android.tv.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of installed runtime modules.
 *
 * <p>Storage is injected so the registry stays unit-testable on a plain JVM; the Android-backed
 * implementation lives in {@link RuntimeAndroid}.
 */
public final class RuntimeStore {

    /** Minimal persistence seam. The Android implementation wraps SharedPreferences. */
    public interface Storage {
        String read();

        void write(String value);
    }

    private static final Gson GSON = new Gson();

    private final Storage storage;

    public RuntimeStore(Storage storage) {
        this.storage = storage;
    }

    public Entry get(String id) {
        if (RuntimeText.isBlank(id)) return null;
        return entries().get(id);
    }

    public boolean isInstalled(String id) {
        return get(id) != null;
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries().values()));
    }

    /**
     * Records a successfully installed module.
     *
     * <p>Only call this once the module is actually installed and verified; the registry is what
     * the host trusts when deciding whether a module may be loaded.
     */
    public void put(Entry entry) {
        if (entry == null || RuntimeText.isBlank(entry.id)) return;
        Map<String, Entry> current = entries();
        current.put(entry.id, entry);
        persist(current);
    }

    public void remove(String id) {
        if (RuntimeText.isBlank(id)) return;
        Map<String, Entry> current = entries();
        if (current.remove(id) == null) return;
        persist(current);
    }

    public void clear() {
        storage.write("");
    }

    /**
     * Decides whether the catalog entry should replace what is installed.
     *
     * <p>A downgrade is refused: module APKs are signed, but an older build may be incompatible
     * with a host that has already moved on, and silently reverting is worse than staying put.
     */
    public Decision decide(RuntimeCatalog.RuntimeModule module) {
        if (module == null || !module.isValid()) return Decision.INVALID;
        Entry installed = get(module.id);
        if (installed == null) return Decision.INSTALL;
        if (!installed.isValid()) return Decision.REINSTALL;
        if (installed.versionCode < module.versionCode) return Decision.UPDATE;
        if (installed.versionCode > module.versionCode) return Decision.DOWNGRADE;
        if (!module.compatibilityId.equals(installed.compatibilityId)) return Decision.REINSTALL;
        return Decision.CURRENT;
    }

    public enum Decision {
        INSTALL,
        UPDATE,
        REINSTALL,
        CURRENT,
        DOWNGRADE,
        INVALID;

        /** Whether the caller should proceed with a download and install. */
        public boolean shouldInstall() {
            return this == INSTALL || this == UPDATE || this == REINSTALL;
        }
    }

    private Map<String, Entry> entries() {
        String raw = storage.read();
        if (RuntimeText.isBlank(raw)) return new LinkedHashMap<>();
        try {
            Payload payload = GSON.fromJson(raw, Payload.class);
            if (payload == null || payload.entries == null) return new LinkedHashMap<>();
            Map<String, Entry> result = new LinkedHashMap<>();
            for (Entry entry : payload.entries) {
                if (entry == null || RuntimeText.isBlank(entry.id)) continue;
                result.put(entry.id, entry);
            }
            return result;
        } catch (JsonSyntaxException e) {
            // A corrupted registry must not be treated as "nothing installed" silently; the
            // caller re-installs, which is the safe direction.
            return new LinkedHashMap<>();
        }
    }

    private void persist(Map<String, Entry> entries) {
        Payload payload = new Payload();
        payload.entries = new ArrayList<>(entries.values());
        storage.write(GSON.toJson(payload));
    }

    private static final class Payload {
        List<Entry> entries;
    }

    /** One installed module. */
    public static final class Entry {

        public String id;
        public String packageName;
        public int versionCode;
        public String versionName;
        public String compatibilityId;
        public String abi;
        public String sha256;
        public long size;
        public String library;
        public long installedAtMs;

        public Entry() {
        }

        public Entry(RuntimeCatalog.RuntimeModule module, RuntimeCatalog.RuntimeArtifact artifact, long installedAtMs) {
            this.id = module.id;
            this.packageName = module.packageName;
            this.versionCode = module.versionCode;
            this.versionName = module.displayVersion();
            this.compatibilityId = module.compatibilityId;
            this.abi = RuntimeAbi.normalize(artifact == null ? "" : artifact.abi);
            this.sha256 = artifact == null ? "" : RuntimeIntegrity.normalize(artifact.sha256);
            this.size = artifact == null ? 0 : artifact.size;
            this.installedAtMs = installedAtMs;
        }

        public boolean isValid() {
            return RuntimeText.isNotBlank(id)
                    && RuntimeText.isNotBlank(packageName)
                    && RuntimeText.isNotBlank(compatibilityId)
                    && versionCode > 0;
        }

        public String displayVersion() {
            return RuntimeText.isBlank(versionName) ? String.valueOf(versionCode) : versionName;
        }
    }
}
