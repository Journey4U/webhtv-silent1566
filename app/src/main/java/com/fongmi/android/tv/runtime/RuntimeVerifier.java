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

    /**
     * Opens a declared payload that is not a native library.
     *
     * <p>A module's native libraries are files under {@code nativeLibraryDir}, so they can be checked
     * with {@link File}. Other payloads — the Python runtime's {@code assets/chaquopy/**} tree, for
     * instance — live inside the module APK and are reachable only through an {@code AssetManager} or
     * a zip reader. This seam lets the verification logic stay free of Android APIs and unit-testable
     * on a plain JVM.
     *
     * <p>Implementations must return {@code null} when the path does not exist, so a missing payload
     * is reported as a verification problem rather than as an exception.
     */
    public interface PayloadReader {
        java.io.InputStream open(String path) throws IOException;
    }

    private RuntimeVerifier() {
    }

    public static Result verify(
            RuntimeCatalog.RuntimeModule module,
            RuntimeManifest manifest,
            File moduleRoot,
            String expectedAbi,
            HostLibraries hostLibraries) {
        return verify(module, manifest, moduleRoot, expectedAbi, hostLibraries, null);
    }

    /**
     * Verifies a module that may declare payloads beyond its native libraries.
     *
     * @param payloads opens non-library payloads (e.g. {@code assets/**}); when {@code null}, a
     *     manifest declaring such a payload is rejected rather than accepted unverified
     */
    public static Result verify(
            RuntimeCatalog.RuntimeModule module,
            RuntimeManifest manifest,
            File moduleRoot,
            String expectedAbi,
            HostLibraries hostLibraries,
            PayloadReader payloads) {

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
        verifyFiles(manifest, moduleRoot, payloads, problems);
        if (!problems.isEmpty()) return Result.failure(problems);

        return Result.success(manifest.library);
    }

    /**
     * Verifies an installed module that has no catalog entry to compare against.
     *
     * <p>Used when a module was installed by some other route — sideload, a file manager, another
     * app — so the host never recorded a catalog artifact digest. The catalog comparison cannot run
     * (there is nothing to compare against), but everything that protects the host still does:
     *
     * <ul>
     *   <li>the manifest must be structurally valid, with an ABI this build supports and no path
     *       traversal;
     *   <li>its compatibility contract must be one this host build recognises;
     *   <li>every declared file must match the digest the manifest declares for it;
     *   <li>any host-provided library must match the declared digest.
     * </ul>
     *
     * <p>The manifest travels inside the module APK, so these checks catch corruption and
     * mismatched builds. They do not establish provenance: the platform's APK signature
     * verification at install time is what does that.
     */
    public static Result verifyInstalled(
            RuntimeManifest manifest,
            File moduleRoot,
            String expectedAbi,
            HostLibraries hostLibraries) {
        return verifyInstalled(manifest, moduleRoot, expectedAbi, hostLibraries, null);
    }

    /**
     * Verifies an installed module that may declare payloads beyond its native libraries.
     *
     * @param payloads opens non-library payloads (e.g. {@code assets/**}); when {@code null}, a
     *     manifest declaring such a payload is rejected rather than accepted unverified
     */
    public static Result verifyInstalled(
            RuntimeManifest manifest,
            File moduleRoot,
            String expectedAbi,
            HostLibraries hostLibraries,
            PayloadReader payloads) {

        if (manifest == null) return Result.failure("module manifest is missing");
        if (!manifest.isValid()) return Result.failure("module manifest is invalid");

        String wanted = RuntimeAbi.normalize(expectedAbi);
        if (!wanted.isEmpty() && !wanted.equals(RuntimeAbi.normalize(manifest.abi))) {
            return Result.failure("abi mismatch: expected=" + wanted
                    + " manifest=" + RuntimeAbi.normalize(manifest.abi));
        }
        if (!RuntimeCompatibility.isAccepted(manifest.compatibilityId)) {
            return Result.failure("unsupported compatibility contract: " + manifest.compatibilityId);
        }
        if (moduleRoot == null || !moduleRoot.isDirectory()) {
            return Result.failure("module directory is unavailable");
        }

        List<String> problems = new ArrayList<>();
        verifyHostLibraries(manifest, hostLibraries, problems);
        verifyFiles(manifest, moduleRoot, payloads, problems);
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

    private static void verifyFiles(
            RuntimeManifest manifest, File moduleRoot, PayloadReader payloads, List<String> problems) {
        for (RuntimeManifest.RuntimeFile file : manifest.files()) {
            if (RuntimeManifest.isNativeLibraryPath(file.path)) {
                verifyNativeLibrary(file, manifest, moduleRoot, problems);
            } else {
                verifyPayload(file, payloads, problems);
            }
        }
    }

    /**
     * Checks a declared native library against the module's {@code nativeLibraryDir}.
     *
     * <p>{@code moduleRoot} is the installed module's {@code nativeLibraryDir}, which already ends at
     * {@code lib/<abi>/}, so the manifest's own {@code lib/<abi>/} prefix must be stripped first.
     */
    private static void verifyNativeLibrary(
            RuntimeManifest.RuntimeFile file, RuntimeManifest manifest, File moduleRoot,
            List<String> problems) {
        String entry = RuntimeManifest.nativeLibraryEntry(file.path, manifest.abi);
        File target = new File(moduleRoot, entry);
        if (!target.isFile()) {
            problems.add("declared file is missing: " + file.path);
            return;
        }
        if (file.size > 0 && target.length() != file.size) {
            problems.add("declared file size mismatch: " + file.path);
            return;
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

    /**
     * Checks a declared payload that is not a native library, such as {@code assets/chaquopy/**}.
     *
     * <p>Such payloads live inside the module APK, so they are read through the injected
     * {@link PayloadReader} rather than from the file system. A manifest that declares one while no
     * reader is available is rejected: accepting it unverified would let an unread payload reach the
     * loader, which is the opposite of what this class is for.
     */
    private static void verifyPayload(
            RuntimeManifest.RuntimeFile file, PayloadReader payloads, List<String> problems) {
        if (payloads == null) {
            problems.add("declared payload cannot be verified without a reader: " + file.path);
            return;
        }
        try (java.io.InputStream in = payloads.open(file.path)) {
            if (in == null) {
                problems.add("declared payload is missing: " + file.path);
                return;
            }
            String actual = RuntimeIntegrity.sha256(in);
            if (!RuntimeIntegrity.matches(file.sha256, actual)) {
                problems.add("declared payload digest mismatch: " + file.path);
            }
        } catch (IOException e) {
            problems.add("declared payload is unreadable: " + file.path);
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
