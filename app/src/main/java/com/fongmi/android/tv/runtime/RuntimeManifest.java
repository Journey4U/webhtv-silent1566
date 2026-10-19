package com.fongmi.android.tv.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
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

    private static final Gson GSON = new Gson();

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
     * Optional ordered list of manifest-relative libraries to load, in this exact order.
     *
     * <p>Modules that ship several interdependent libraries (the mpv bundle is nine) must control
     * the load order: the dynamic linker resolves each {@code DT_NEEDED} against what is already
     * loaded, so loading a dependent before its dependency fails. When absent the host loads
     * {@link #library} alone.
     *
     * <p>Must not list any host-provided library: those are loaded by the host from its own
     * directory, and a second copy collides on the soname.
     */
    @SerializedName("loadOrder")
    public List<String> loadOrder;

    public List<String> loadOrder() {
        return loadOrder == null ? Collections.emptyList() : loadOrder;
    }

    /**
     * Libraries the host must load, in order, before the module's own.
     *
     * <p>Returns the declared order when present; otherwise falls back to the single entry library
     * so single-library modules need no extra declaration.
     */
    public List<String> librariesToLoad() {
        List<String> declared = loadOrder();
        return declared.isEmpty() ? Collections.singletonList(library) : declared;
    }

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
        for (String entry : loadOrder()) {
            if (RuntimeText.isBlank(entry) || entry.startsWith("/") || entry.contains("..")) return false;
            // Every library the host loads must be declared (and therefore digest-verified).
            if (findFile(entry) == null) return false;
        }
        for (RuntimeFile file : files()) {
            if (file == null || !file.isValid()) return false;
        }
        for (Map.Entry<String, String> entry : hostLibraries().entrySet()) {
            if (RuntimeText.isBlank(entry.getKey())) return false;
            if (!RuntimeIntegrity.isSha256(entry.getValue())) return false;
        }
        return true;
    }

    /**
     * Parses a module manifest.
     *
     * <p>Shared by the loader and by device tests so both agree on what a valid manifest is.
     *
     * @throws IOException when the payload is blank or not valid JSON
     */
    public static RuntimeManifest parse(String json) throws IOException {
        if (RuntimeText.isBlank(json)) throw new IOException("模块清单为空");
        try {
            RuntimeManifest manifest = GSON.fromJson(json, RuntimeManifest.class);
            if (manifest == null) throw new IOException("模块清单无法解析");
            return manifest;
        } catch (JsonSyntaxException e) {
            throw new IOException("模块清单格式错误", e);
        }
    }

    /** Finds the declared file entry for a manifest-relative path. */
    public RuntimeFile findFile(String path) {
        if (RuntimeText.isBlank(path)) return null;
        for (RuntimeFile file : files()) {
            if (file != null && path.equals(file.path)) return file;
        }
        return null;
    }

    /**
     * Whether a manifest path names a native library rather than some other payload.
     *
     * <p>Native libraries are the only payload the host loads from
     * {@code nativeLibraryDir}; everything else (the Python runtime's {@code assets/chaquopy/**} tree,
     * for instance) lives inside the module APK and is read through a different mechanism. The
     * distinction decides which verifier runs, so it is stated once here rather than inferred at each
     * call site.
     *
     * <p>A bare file name counts as a native library: some producers omit the {@code lib/<abi>/}
     * prefix, and {@link #nativeLibraryEntry} accepts those.
     */
    public static boolean isNativeLibraryPath(String declaredPath) {
        if (RuntimeText.isBlank(declaredPath)) return false;
        String normalized = declaredPath.trim();
        return normalized.startsWith("lib/") || !normalized.contains("/");
    }

    /**
     * Resolves a manifest path against an installed module's {@code nativeLibraryDir}.
     *
     * <p>Manifest paths are written relative to the APK root ({@code lib/<abi>/x.so}), while
     * {@code ApplicationInfo.nativeLibraryDir} already points at {@code .../lib/<abi>/}. Joining
     * them verbatim would look for {@code .../lib/<abi>/lib/<abi>/x.so} and always miss, so the
     * redundant prefix is removed here.
     *
     * <p>Path traversal is rejected upstream by {@link RuntimeFile#isValid()}.
     */
    public static String nativeLibraryEntry(String declaredPath, String abi) {
        if (RuntimeText.isBlank(declaredPath)) return "";
        String normalized = declaredPath.trim();
        String prefix = "lib/" + RuntimeAbi.normalize(abi) + "/";
        if (normalized.startsWith(prefix)) return normalized.substring(prefix.length());
        // Some producers write bare file names; accept those unchanged.
        return normalized;
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
