package com.fongmi.android.tv.runtime;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code assets/runtime.json} contract carried inside every installed module APK.
 *
 * <p>This is the authoritative description of what a module provides. The host validates it
 * before loading anything, so a tampered or mismatched module fails closed.
 */
public final class RuntimeManifest {

    @SerializedName("module")
    public String module;

    @SerializedName("packageName")
    public String packageName;

    @SerializedName("versionCode")
    public int versionCode;

    @SerializedName("versionName")
    public String versionName;

    @SerializedName("compatibilityId")
    public String compatibilityId;

    @SerializedName("abi")
    public String abi;

    @SerializedName("sha256")
    public String sha256;

    @SerializedName("size")
    public long size;

    /** Relative path of the entry library that the host loads first. */
    @SerializedName("library")
    public String library;

    /**
     * Libraries the module expects the <em>host</em> to provide, mapped to the digest the host
     * must match. A module must never ship its own copy: two copies of {@code libc++_shared.so}
     * collide on the same soname and break dynamic linking for every native consumer.
     */
    @SerializedName("hostLibraries")
    public Map<String, String> hostLibraries;

    @SerializedName("files")
    public List<RuntimeFile> files;

    public List<RuntimeFile> files() {
        return files == null ? Collections.emptyList() : files;
    }

    public Map<String, String> hostLibraries() {
        return hostLibraries == null ? Collections.emptyMap() : hostLibraries;
    }

    public boolean isValid() {
        if (RuntimeText.isBlank(module) || RuntimeText.isBlank(packageName)) return false;
        if (RuntimeText.isBlank(compatibilityId) || versionCode <= 0) return false;
        if (!RuntimeAbi.isSupported(abi)) return false;
        if (RuntimeText.isBlank(library) || library.startsWith("/") || library.contains("..")) return false;
        if (files().isEmpty()) return false;
        for (RuntimeFile file : files()) {
            if (file == null || !file.isValid()) return false;
        }
        for (Map.Entry<String, String> entry : hostLibraries().entrySet()) {
            if (RuntimeText.isBlank(entry.getKey())) return false;
            if (!RuntimeIntegrity.isSha256(entry.getValue())) return false;
        }
        return true;
    }

    /** Finds the declared file entry for a manifest-relative path. */
    public RuntimeFile findFile(String path) {
        if (RuntimeText.isBlank(path)) return null;
        for (RuntimeFile file : files()) {
            if (file != null && path.equals(file.path)) return file;
        }
        return null;
    }

    public static final class RuntimeFile {

        @SerializedName("path")
        public String path;

        @SerializedName("size")
        public long size;

        @SerializedName("sha256")
        public String sha256;

        public boolean isValid() {
            if (RuntimeText.isBlank(path) || path.startsWith("/") || path.contains("..")) return false;
            if (size <= 0) return false;
            return RuntimeIntegrity.isSha256(sha256);
        }
    }
}
