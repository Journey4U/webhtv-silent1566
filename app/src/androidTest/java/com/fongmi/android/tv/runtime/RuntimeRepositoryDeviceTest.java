package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/**
 * End-to-end verification against the real module repository.
 *
 * <p>Exercises the production path with no fakes: fetch the published catalog over the network,
 * download the artifact for this device's ABI, verify its digest, and confirm the module record
 * would be accepted. This is what proves the published repository is actually consumable — a
 * hand-checked digest on a workstation does not cover the device's network path or the host's
 * parsing.
 *
 * <p>Requires network access. When the catalog cannot be reached the test fails rather than skips:
 * a silent skip here would look like a pass while the repository is unreachable from devices.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeRepositoryDeviceTest {

    private static final String CATALOG_URL = RuntimeRepository.DEFAULT_CATALOG_URL;
    private static final String MODULE_ID = "sherpa";

    private static String abi() {
        return RuntimeAbi.resolve(
                com.fongmi.android.tv.BuildConfig.FLAVOR_abi,
                android.os.Build.SUPPORTED_ABIS);
    }

    @Test
    public void deviceCanReachThePublishedCatalog() throws Exception {
        RuntimeCatalog catalog = new RuntimeRepository(RuntimeRepository.httpFetcher()).load(CATALOG_URL);

        assertNotNull(catalog);
        assertTrue("catalog schema should be supported", catalog.isSupported());
        assertFalse("catalog should list at least one module", catalog.modules().isEmpty());

        RuntimeCatalog.RuntimeModule module = catalog.find(MODULE_ID);
        assertNotNull("catalog should expose the " + MODULE_ID + " module", module);
        assertTrue("catalog entry should be valid: " + module.id, module.isValid());
        assertTrue("host should accept the contract " + module.compatibilityId,
                RuntimeCompatibility.isAccepted(module.compatibilityId));
    }

    @Test
    public void catalogOffersAnArtifactForThisDeviceAbi() throws Exception {
        RuntimeCatalog catalog = new RuntimeRepository(RuntimeRepository.httpFetcher()).load(CATALOG_URL);
        RuntimeCatalog.RuntimeModule module = catalog.find(MODULE_ID);
        assertNotNull(module);

        String abi = abi();
        RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi, android.os.Build.VERSION.SDK_INT);

        assertNotNull("no artifact for this device abi (" + abi + "); catalog abis: "
                + module.artifacts().stream().map(a -> a.abi).toList(), artifact);
        assertTrue("artifact should be valid", artifact.isValid());
        assertTrue("artifact should be non-empty", artifact.size > 0);
        assertTrue("artifact must be HTTPS", artifact.url.startsWith("https://"));
    }

    /**
     * The decisive check: download the published APK through the real downloader and confirm the
     * digest the catalog advertises matches the bytes a device actually receives. A mirror or a
     * truncated upload would be caught here and nowhere else.
     */
    @Test
    public void downloadedArtifactMatchesTheCatalogDigest() throws Exception {
        RuntimeCatalog catalog = new RuntimeRepository(RuntimeRepository.httpFetcher()).load(CATALOG_URL);
        RuntimeCatalog.RuntimeModule module = catalog.find(MODULE_ID);
        assertNotNull(module);
        RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi(), android.os.Build.VERSION.SDK_INT);
        assertNotNull(artifact);

        File dir = RuntimeAndroid.downloadDir();
        assertNotNull("download dir should be available", dir);
        File target = new File(dir, "repo-verify-" + artifact.abi + ".apk");
        if (target.exists() && !target.delete()) {
            fail("could not clear a stale download at " + target);
        }

        try {
            RuntimeAndroid.downloader().download(artifact.url, target);

            assertEquals("downloaded size should match the catalog", artifact.size, target.length());
            String actual = RuntimeIntegrity.sha256(target);
            assertTrue("downloaded digest should match the catalog\nexpected: "
                            + artifact.sha256 + "\nactual:   " + actual,
                    RuntimeIntegrity.matches(artifact.sha256, actual));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    /** The installed module APK must actually declare the package the catalog names. */
    @Test
    public void publishedArtifactDeclaresTheCatalogPackageAndManifest() throws Exception {
        RuntimeCatalog catalog = new RuntimeRepository(RuntimeRepository.httpFetcher()).load(CATALOG_URL);
        RuntimeCatalog.RuntimeModule module = catalog.find(MODULE_ID);
        assertNotNull(module);
        RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi(), android.os.Build.VERSION.SDK_INT);
        assertNotNull(artifact);

        File dir = RuntimeAndroid.downloadDir();
        File target = new File(dir, "repo-manifest-" + artifact.abi + ".apk");
        if (target.exists() && !target.delete()) {
            fail("could not clear a stale download at " + target);
        }

        try {
            RuntimeAndroid.downloader().download(artifact.url, target);

            // Read runtime.json straight out of the downloaded APK.
            String raw = readEntry(target, "assets/" + RuntimeLoader.manifestAssetName());
            assertNotNull("module APK should carry a runtime.json", raw);

            RuntimeManifest manifest = RuntimeManifest.parse(raw);
            assertTrue("manifest should be valid", manifest.isValid());
            assertEquals("manifest module id", module.id, manifest.module);
            assertEquals("manifest package name must match the catalog", module.packageName, manifest.packageName);
            assertEquals("manifest contract must match the catalog", module.compatibilityId, manifest.compatibilityId);
            assertEquals("manifest version must match the catalog", module.versionCode, manifest.versionCode);
            assertEquals("manifest abi must match the artifact", artifact.abi, manifest.abi);

            // The declared library must be present in the APK with the declared digest.
            RuntimeManifest.RuntimeFile file = manifest.findFile(manifest.library);
            assertNotNull("manifest should declare its entry library: " + manifest.library, file);
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(target);
            try {
                java.util.zip.ZipEntry entry = zip.getEntry(manifest.library);
                assertNotNull("declared library should exist in the APK: " + manifest.library, entry);
                assertEquals("declared library must be stored uncompressed, or the platform will not "
                        + "expose it via nativeLibraryDir", java.util.zip.ZipEntry.STORED, entry.getMethod());
                byte[] bytes = readAll(zip.getInputStream(entry));
                assertEquals("declared size should match the actual entry", file.size, bytes.length);
                assertTrue("declared digest should match the actual entry",
                        RuntimeIntegrity.matches(file.sha256, RuntimeIntegrity.sha256(bytes)));
            } finally {
                zip.close();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static String readEntry(File apk, String name) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            return new String(readAll(zip.getInputStream(entry)), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a stream fully.
     *
     * <p>Deliberately not {@code InputStream.readAllBytes()}: that is a Java 9 API absent from the
     * device runtimes this app supports, and core-library desugaring does not cover {@code java.io}.
     * Using it compiles and passes on the JVM, then throws {@code NoSuchMethodError} on device.
     */
    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try (java.io.InputStream stream = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
