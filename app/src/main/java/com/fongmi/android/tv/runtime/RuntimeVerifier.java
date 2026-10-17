package com.fongmi.android.tv.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates an installed module against its catalog entry before anything is loaded.
 *
 * <p>Every check fails closed. A module that cannot be proven to match the catalog, to agree
 * with the host's shared libraries, and to have intact files is never handed to
 * {@code System.load}.
 *
 * <p>The host library lookup is injected so the verification logic stays unit-testable without
 * Android or a real module APK.
 */
public final class RuntimeVerifier {

    /** Resolves a host-provided library name (e.g. {@code libc++_shared.so}) to its file. */
    public interface HostLibraries {
        File resolve(String name);
    }

    private RuntimeVerifier() {
    }

    public static Result verify(
            RuntimeCatalog.RuntimeModule module,
            RuntimeManifest manifest,
            File moduleRoot,
            String expectedAbi,
            HostLibraries hostLibraries) {

        if (module == null) return Result.failure("catalog entry is missing");
        if (manifest == null) return Result.failure("module manifest is missing");
        if (!module.isValid()) return Result.failure("catalog entry is invalid");
        if (!manifest.isValid()) return Result.failure("module manifest is invalid");

        String mismatch = compareIdentity(module, manifest, expectedAbi);
        if (mismatch != null) return Result.failure(mismatch);

        if (!RuntimeCompatibility.isAccepted(manifest.compatibilityId)) {
            return Result.failure("unsupported compatibility contract: " + manifest.compatibilityId);
        }

        if (moduleRoot == null || !moduleRoot.isDirectory()) {
            return Result.failure("module directory is unavailable");
        }

        List<String> problems = new ArrayList<>();
        verifyHostLibraries(manifest, hostLibraries, problems);
        verifyFiles(manifest, moduleRoot, problems);
        if (!problems.isEmpty()) return Result.failure(problems);

        return Result.success(manifest.library);
    }

    /**
     * The catalog and the installed manifest must describe the same module. A mismatch means the
     * catalog moved on (or the APK was swapped) and loading it would run code the host did not
     * authorize.
     */
    private static String compareIdentity(
            RuntimeCatalog.RuntimeModule module,
            RuntimeManifest manifest,
            String expectedAbi) {

        if (!equalsText(module.id, manifest.module)) {
            return "module id mismatch: catalog=" + module.id + " manifest=" + manifest.module;
        }
        if (!equalsText(module.packageName, manifest.packageName)) {
            return "package name mismatch: catalog=" + module.packageName + " manifest=" + manifest.packageName;
        }
        if (!equalsText(module.compatibilityId, manifest.compatibilityId)) {
            return "compatibility contract mismatch: catalog=" + module.compatibilityId
                    + " manifest=" + manifest.compatibilityId;
        }
        if (module.versionCode != manifest.versionCode) {
            return "version mismatch: catalog=" + module.versionCode + " manifest=" + manifest.versionCode;
        }
        String wanted = RuntimeAbi.normalize(expectedAbi);
        if (!wanted.isEmpty() && !wanted.equals(RuntimeAbi.normalize(manifest.abi))) {
            return "abi mismatch: expected=" + wanted + " manifest=" + RuntimeAbi.normalize(manifest.abi);
        }
        return null;
    }

    /**
     * The module must not ship its own copy of a host library, and the host copy must be the exact
     * revision the module was built against. Two copies of {@code libc++_shared.so} collide on the
     * same soname and break dynamic linking for every native consumer in the process.
     */
    private static void verifyHostLibraries(RuntimeManifest manifest, HostLibraries hostLibraries, List<String> problems) {
        for (Map.Entry<String, String> entry : manifest.hostLibraries().entrySet()) {
            String name = entry.getKey();
            String expected = entry.getValue();

            if (manifest.findFile(name) != null || declaresFileEndingWith(manifest, name)) {
                problems.add("module must not bundle host library: " + name);
                continue;
            }
            if (hostLibraries == null) {
                problems.add("host library is required but unavailable: " + name);
                continue;
            }
            File file = hostLibraries.resolve(name);
            if (file == null || !file.isFile()) {
                problems.add("host library is missing: " + name);
                continue;
            }
            try {
                String actual = RuntimeIntegrity.sha256(file);
                if (!RuntimeIntegrity.matches(expected, actual)) {
                    problems.add("host library digest mismatch: " + name);
                }
            } catch (IOException e) {
                problems.add("host library is unreadable: " + name);
            }
        }
    }

    private static boolean declaresFileEndingWith(RuntimeManifest manifest, String name) {
        for (RuntimeManifest.RuntimeFile file : manifest.files()) {
            if (file == null || RuntimeText.isBlank(file.path)) continue;
            if (file.path.equals(name) || file.path.endsWith("/" + name)) return true;
        }
        return false;
    }

    private static void verifyFiles(RuntimeManifest manifest, File moduleRoot, List<String> problems) {
        for (RuntimeManifest.RuntimeFile file : manifest.files()) {
            // moduleRoot is the installed module's nativeLibraryDir, which already ends at
            // lib/<abi>/, so the manifest's own lib/<abi>/ prefix must be stripped first.
            String entry = RuntimeManifest.nativeLibraryEntry(file.path, manifest.abi);
            File target = new File(moduleRoot, entry);
            if (!target.isFile()) {
                problems.add("declared file is missing: " + file.path);
                continue;
            }
            if (file.size > 0 && target.length() != file.size) {
                problems.add("declared file size mismatch: " + file.path);
                continue;
            }
            try {
                String actual = RuntimeIntegrity.sha256(target);
                if (!RuntimeIntegrity.matches(file.sha256, actual)) {
                    problems.add("declared file digest mismatch: " + file.path);
                }
            } catch (IOException e) {
                problems.add("declared file is unreadable: " + file.path);
            }
        }
    }

    private static boolean equalsText(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return !a.isEmpty() && a.equals(b);
    }

    /** Outcome of a verification pass. */
    public static final class Result {

        private final boolean ok;
        private final String entryLibrary;
        private final List<String> problems;

        private Result(boolean ok, String entryLibrary, List<String> problems) {
            this.ok = ok;
            this.entryLibrary = entryLibrary == null ? "" : entryLibrary;
            this.problems = problems;
        }

        static Result success(String entryLibrary) {
            return new Result(true, entryLibrary, java.util.Collections.emptyList());
        }

        static Result failure(String problem) {
            return new Result(false, "", java.util.Collections.singletonList(problem));
        }

        static Result failure(List<String> problems) {
            return new Result(false, "", new ArrayList<>(problems));
        }

        public boolean isOk() {
            return ok;
        }

        /** Manifest-relative path of the entry library; empty when verification failed. */
        public String getEntryLibrary() {
            return entryLibrary;
        }

        public List<String> getProblems() {
            return java.util.Collections.unmodifiableList(problems);
        }

        public String describe() {
            return problems.isEmpty() ? "ok" : String.join("; ", problems);
        }
    }
}
