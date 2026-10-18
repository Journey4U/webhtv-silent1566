package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * MPV module loading order and the host-library contract.
 *
 * <p>This is the highest-risk part of the design. {@code libmvcodec}/{@code libmpv}/{@code
 * libplayer} all declare {@code DT_NEEDED: libc++_shared.so}, and two copies of that soname break
 * dynamic linking for every native consumer — the failure that produced "MPV failed to load media"
 * for nearly every source. So the tests pin both the order and the refusal to proceed without the
 * host copy.
 */
public class RuntimeMpvTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String HOST_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /** Mirrors the real module: nine libraries, host-provided libc++_shared.so. */
    private static final List<String> LIBS = List.of(
            "libmvutil.so", "libmwresample.so", "libmwscale.so", "libmvcodec.so",
            "libmvformat.so", "libmvfilter.so", "libmvdevice.so", "libmpv.so", "libplayer.so");

    private final List<String> loadCalls = new ArrayList<>();

    private static final class MemoryStorage implements RuntimeStore.Storage {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }
    }

    private final RuntimeStore store = new RuntimeStore(new MemoryStorage());

    private static String sha(byte[] data) {
        return RuntimeIntegrity.sha256(data);
    }

    private static byte[] bytesOf(String name) {
        return ("payload-" + name).getBytes(StandardCharsets.UTF_8);
    }

    private String manifestJson(String librarySha) {
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < LIBS.size(); i++) {
            if (i > 0) files.append(',');
            byte[] payload = bytesOf(LIBS.get(i));
            files.append("{\"path\":\"lib/arm64-v8a/").append(LIBS.get(i))
                    .append("\",\"size\":").append(payload.length)
                    .append(",\"sha256\":\"").append(sha(payload)).append("\"}");
        }
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < LIBS.size(); i++) {
            if (i > 0) order.append(',');
            order.append("\"lib/arm64-v8a/").append(LIBS.get(i)).append("\"");
        }
        return "{"
                + "\"module\":\"mpv\","
                + "\"packageName\":\"com.silent.runtime.mpv\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"library\":\"lib/arm64-v8a/libplayer.so\","
                + "\"loadOrder\":[" + order + "],"
                + "\"hostLibraries\":{\"libc++_shared.so\":\"" + librarySha + "\"},"
                + "\"files\":[" + files + "]"
                + "}";
    }

    private String catalogJson() {
        return "{"
                + "\"schema\":1,\"modules\":[{"
                + "\"id\":\"mpv\","
                + "\"packageName\":\"com.silent.runtime.mpv\","
                + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
                + "\"versionCode\":1,"
                + "\"versionName\":\"1.0.0\","
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":45376556,"
                + "\"sha256\":\"" + HOST_SHA + "\","
                + "\"url\":\"https://example.invalid/runtime-mpv.apk\"}]"
                + "}]}";
    }

    private RuntimeCatalog.RuntimeModule module() {
        return GSON.fromJson(catalogJson(), RuntimeCatalog.class).find("mpv");
    }

    private void recordInstalled() {
        RuntimeCatalog.RuntimeModule module = module();
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 0L));
    }

    /** Materializes a module directory laid out like the real nativeLibraryDir. */
    private File libDir() throws IOException {
        File dir = folder.newFolder();
        for (String name : LIBS) {
            Files.write(new File(dir, name).toPath(), bytesOf(name));
        }
        return dir;
    }

    private File hostLibcxx() throws IOException {
        File file = folder.newFile("libc++_shared.so");
        Files.write(file.toPath(), "host-libcxx".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private RuntimePlatform.PackageInfo packageInfo(File libDir) {
        return new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                return libDir;
            }

            @Override
            public int versionCode(String packageName) {
                return 1;
            }

            @Override
            public File apkPath(String packageName) {
                return null;
            }

            @Override
            public String apkDigest(String packageName) {
                return null;
            }
        };
    }

    private RuntimeMpv.Status prepare(File libDir, File hostLibcxx, String manifest) {
        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo(libDir), path -> {
        }, name -> hostLibcxx, packageName -> manifest);
        return RuntimeMpv.prepare(
                loader,
                packageInfo(libDir),
                name -> "libc++_shared.so".equals(name) ? hostLibcxx : null,
                path -> loadCalls.add(new File(path).getName()),
                "arm64-v8a");
    }

    /** The declared digest must match the host's actual copy, so hash it into the manifest. */
    private String manifestFor(File hostLibcxx) throws IOException {
        return manifestJson(sha(Files.readAllBytes(hostLibcxx.toPath())));
    }

    @Test
    public void loadsTheHostLibraryBeforeTheModulesOwn() throws IOException {
        recordInstalled();
        File host = hostLibcxx();

        RuntimeMpv.Status status = prepare(libDir(), host, manifestFor(host));

        assertTrue(status.getProblem(), status.isOk());
        assertEquals(10, loadCalls.size());
        // The host copy must come first, under its soname, or the module's libraries would fail
        // to resolve libc++_shared.so.
        assertEquals("libc++_shared.so", loadCalls.get(0));
    }

    @Test
    public void loadsModuleLibrariesInTheDeclaredOrder() throws IOException {
        recordInstalled();
        File host = hostLibcxx();

        RuntimeMpv.Status status = prepare(libDir(), host, manifestFor(host));

        assertTrue(status.getProblem(), status.isOk());
        assertEquals(LIBS, loadCalls.subList(1, loadCalls.size()));
        // The entry library is last: it depends on everything before it.
        assertEquals("libplayer.so", loadCalls.get(loadCalls.size() - 1));
    }

    @Test
    public void reportsTheEntryLibraryPath() throws IOException {
        recordInstalled();
        File host = hostLibcxx();
        File dir = libDir();

        RuntimeMpv.Status status = prepare(dir, host, manifestFor(host));

        assertTrue(status.isOk());
        assertEquals(new File(dir, "libplayer.so").getAbsolutePath(), status.getEntryLibraryPath());
        // getLoaded() reports the module's own libraries; the host copy is loaded separately.
        assertEquals(LIBS.size(), status.getLoaded().size());
    }

    @Test
    public void refusesToLoadWhenTheHostLibraryIsMissing() throws IOException {
        recordInstalled();
        File host = hostLibcxx();

        // The host reports no libc++_shared.so at all.
        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo(libDir()), path -> {
        }, name -> host, packageName -> manifestFor(host));
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                loader, packageInfo(libDir()), name -> null,
                path -> loadCalls.add(new File(path).getName()), "arm64-v8a");

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("宿主库缺失"));
        assertTrue("nothing may be loaded without the host copy", loadCalls.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheHostDigestDoesNotMatch() throws IOException {
        recordInstalled();
        File host = hostLibcxx();
        // Manifest advertises a different digest than the host actually has.
        String manifest = manifestJson(HOST_SHA);

        RuntimeMpv.Status status = prepare(libDir(), host, manifest);

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("host library digest mismatch"));
        assertTrue("a rejected module must not be loaded", loadCalls.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheModuleBundlesItsOwnHostLibrary() throws IOException {
        recordInstalled();
        File host = hostLibcxx();
        // Append a second copy of libc++_shared.so to the declared files. The manifest stays
        // otherwise valid (loadOrder untouched), so this exercises the bundling check itself
        // rather than tripping an unrelated consistency rule.
        byte[] extra = "module-bundled-libcxx".getBytes(StandardCharsets.UTF_8);
        String manifest = manifestFor(host).replace(
                "\"files\":[",
                "\"files\":[{\"path\":\"lib/arm64-v8a/libc++_shared.so\",\"size\":" + extra.length
                        + ",\"sha256\":\"" + sha(extra) + "\"},");

        RuntimeMpv.Status status = prepare(libDir(), host, manifest);

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("must not bundle host library"));
        assertTrue(loadCalls.isEmpty());
    }

    @Test
    public void refusesToLoadWhenTheModuleIsNotInstalled() throws IOException {
        File host = hostLibcxx();

        // "Not installed" means the platform reports no native library directory. A real directory
        // with no registry record is a sideloaded module, which must load.
        RuntimeMpv.Status status = prepare(null, host, manifestFor(host));

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("未安装"));
        assertTrue(loadCalls.isEmpty());
    }

    /** A sideloaded module is usable even though the host never recorded an install. */
    @Test
    public void loadsASideloadedModuleThatHasNoRegistryRecord() throws IOException {
        File host = hostLibcxx();

        RuntimeMpv.Status status = prepare(libDir(), host, manifestFor(host));

        assertTrue(status.getProblem(), status.isOk());
        assertEquals(10, loadCalls.size());
        assertEquals("libc++_shared.so", loadCalls.get(0));
    }

    @Test
    public void refusesToLoadWhenADeclaredLibraryIsMissing() throws IOException {
        recordInstalled();
        File host = hostLibcxx();
        File dir = libDir();
        assertTrue(new File(dir, "libmvcodec.so").delete());

        RuntimeMpv.Status status = prepare(dir, host, manifestFor(host));

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("declared file is missing"));
        assertTrue(loadCalls.isEmpty());
    }

    @Test
    public void reportsALoadFailureWithTheOffendingLibrary() throws IOException {
        recordInstalled();
        File host = hostLibcxx();

        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo(libDir()), path -> {
        }, name -> host, packageName -> manifestFor(host));
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                loader, packageInfo(libDir()), name -> "libc++_shared.so".equals(name) ? host : null,
                path -> {
                    throw new UnsatisfiedLinkError("cannot load " + path);
                }, "arm64-v8a");

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("加载失败"));
    }

    @Test
    public void rejectsAnUnknownCompatibilityContract() throws IOException {
        RuntimeCatalog.RuntimeModule module = module();
        module.compatibilityId = "unknown-contract-v9";
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 0L));
        File host = hostLibcxx();

        RuntimeMpv.Status status = prepare(libDir(), host, manifestFor(host));

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("不支持的模块契约"));
        assertTrue(loadCalls.isEmpty());
    }

    @Test
    public void rejectsAnAbiMismatch() throws IOException {
        recordInstalled();
        File host = hostLibcxx();
        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo(libDir()), path -> {
        }, name -> host, packageName -> manifestFor(host));

        RuntimeMpv.Status status = RuntimeMpv.prepare(
                loader, packageInfo(libDir()), name -> host,
                path -> loadCalls.add(new File(path).getName()), "armeabi-v7a");

        assertFalse(status.isOk());
        assertTrue(status.getProblem(), status.getProblem().contains("abi mismatch"));
        assertTrue(loadCalls.isEmpty());
    }

    @Test
    public void rejectsNullInputsInsteadOfThrowing() {
        assertFalse(RuntimeMpv.prepare(null, null, null, null, "arm64-v8a").isOk());
        assertFalse(RuntimeMpv.prepare(null, null, name -> null, path -> {
        }, "arm64-v8a").isOk());
    }

    /** A module with no host libraries and no explicit order still loads its single entry. */
    @Test
    public void singleLibraryModuleNeedsNoExplicitOrder() throws IOException {
        RuntimeCatalog.RuntimeModule module = GSON.fromJson(catalogJson(), RuntimeCatalog.class).find("mpv");
        store.put(new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 0L));

        byte[] payload = bytesOf("libplayer.so");
        String manifest = "{"
                + "\"module\":\"mpv\",\"packageName\":\"com.silent.runtime.mpv\","
                + "\"versionCode\":1,\"versionName\":\"1.0.0\","
                + "\"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
                + "\"abi\":\"arm64-v8a\","
                + "\"library\":\"lib/arm64-v8a/libplayer.so\","
                + "\"files\":[{\"path\":\"lib/arm64-v8a/libplayer.so\",\"size\":" + payload.length
                + ",\"sha256\":\"" + sha(payload) + "\"}]}";

        File dir = folder.newFolder("single");
        Files.write(new File(dir, "libplayer.so").toPath(), payload);

        RuntimeLoader loader = new RuntimeLoader(
                store, packageInfo(dir), path -> {
        }, name -> null, packageName -> manifest);
        RuntimeMpv.Status status = RuntimeMpv.prepare(
                loader, packageInfo(dir), name -> null,
                path -> loadCalls.add(new File(path).getName()), "arm64-v8a");

        assertTrue(status.getProblem(), status.isOk());
        assertEquals(List.of("libplayer.so"), loadCalls);
    }

    /**
     * MPV availability is satisfied by either an installed module or the transitional bundled copy.
     *
     * <p>Pinning this matters for the asset removal: once the bundled probe returns false, an
     * absent module must make the engine unavailable. If this rule were wrong the kernel picker
     * would still offer MPV and playback would fail at load time.
     */
    @Test
    public void moduleInstalledMakesMpvAvailable() {
        assertTrue(RuntimeMpv.isAvailable(true, false));
    }

    @Test
    public void bundledCopyMakesMpvAvailableWithoutAModule() {
        assertTrue(RuntimeMpv.isAvailable(false, true));
    }

    @Test
    public void neitherModuleNorBundledCopyMeansUnavailable() {
        // The post-removal state for a user who has not installed the module.
        assertFalse(RuntimeMpv.isAvailable(false, false));
    }

    @Test
    public void availableWhenBothSourcesExist() {
        assertTrue(RuntimeMpv.isAvailable(true, true));
    }

    /**
     * Availability must be decided by the platform, not by the host's install registry.
     *
     * <p>A module installed by any route — the in-app flow, a sideloaded APK, a file manager — is
     * functional on the device. Gating on our own bookkeeping hid the engine from users who
     * installed it another way; this was a real device failure, not a hypothetical one.
     */
    @Test
    public void moduleInstalledIsDecidedByThePlatformNotTheRegistry() {
        RuntimePlatform.PackageInfo present = packageInfo(new File("/data/app/com.silent.runtime.mpv/lib/arm64"));
        RuntimePlatform.PackageInfo absent = packageInfo(null);

        // No registry is consulted at all: the probe answers from the package manager.
        assertTrue(RuntimeMpv.isModuleInstalled(present));
        assertFalse(RuntimeMpv.isModuleInstalled(absent));
        assertFalse(RuntimeMpv.isModuleInstalled(null));
    }

    /** A sideloaded module must make MPV available even though nothing was ever recorded. */
    @Test
    public void sideloadedModuleMakesMpvAvailable() {
        RuntimePlatform.PackageInfo sideloaded = packageInfo(new File("/data/app/com.silent.runtime.mpv/lib/arm64"));

        assertTrue(RuntimeMpv.isAvailable(RuntimeMpv.isModuleInstalled(sideloaded), false));
    }

    /** The platform probe must not throw when the package manager misbehaves. */
    @Test
    public void platformProbeFailureMeansNotInstalled() {
        RuntimePlatform.PackageInfo throwing = new RuntimePlatform.PackageInfo() {
            @Override
            public File nativeLibraryDir(String packageName) {
                throw new IllegalStateException("package manager unavailable");
            }

            @Override
            public int versionCode(String packageName) {
                return -1;
            }

            @Override
            public File apkPath(String packageName) {
                return null;
            }

            @Override
            public String apkDigest(String packageName) {
                return null;
            }
        };

        assertFalse(RuntimeMpv.isModuleInstalled(throwing));
    }
}
