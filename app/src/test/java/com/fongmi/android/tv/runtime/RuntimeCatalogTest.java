package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import org.junit.Test;

/**
 * Contract-layer tests driven by the real upstream {@code catalog.json} shape.
 *
 * <p>These run on a plain JVM: the package under test must not touch Android APIs.
 */
public class RuntimeCatalogTest {

    private static final Gson GSON = new Gson();

    /** Trimmed excerpt of the upstream FongMi/Release "module" catalog, field-for-field. */
    private static final String UPSTREAM_CATALOG = "{"
            + "\"schema\":1,"
            + "\"modules\":["
            + "  {\"id\":\"mpv\","
            + "   \"packageName\":\"com.fongmi.runtime.mpv\","
            + "   \"compatibilityId\":\"mpv-jni-ffmpeg-shared-v1\","
            + "   \"versionCode\":3,"
            + "   \"versionName\":\"1.0.2\","
            + "   \"artifacts\":["
            + "     {\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":17793241,"
            + "      \"sha256\":\"d45d2da660dd7827bef6a8c2752c2c1b78fb371eae554cd2328bbc80d7a3d0b3\","
            + "      \"url\":\"https://github.com/FongMi/Release/releases/download/module/runtime-mpv-1.0.2-arm64-v8a.apk\"},"
            + "     {\"abi\":\"armeabi-v7a\",\"minSdk\":24,\"size\":14582756,"
            + "      \"sha256\":\"14c6cc3b5291a2679e31cc2d7432e2781b129ed41246e7639a7b18a5e4052393\","
            + "      \"url\":\"https://github.com/FongMi/Release/releases/download/module/runtime-mpv-1.0.2-armeabi-v7a.apk\"}"
            + "   ]}"
            + "]}";

    @Test
    public void parsesUpstreamCatalogShape() {
        RuntimeCatalog catalog = GSON.fromJson(UPSTREAM_CATALOG, RuntimeCatalog.class);

        assertNotNull(catalog);
        assertTrue(catalog.isSupported());
        assertEquals(1, catalog.modules().size());

        RuntimeCatalog.RuntimeModule mpv = catalog.find("mpv");
        assertNotNull(mpv);
        assertEquals("com.fongmi.runtime.mpv", mpv.packageName);
        assertEquals("mpv-jni-ffmpeg-shared-v1", mpv.compatibilityId);
        assertEquals(3, mpv.versionCode);
        assertEquals("1.0.2", mpv.displayVersion());
        assertEquals(2, mpv.artifacts().size());
        assertTrue(mpv.isValid());
    }

    @Test
    public void selectsArtifactByAbi() {
        RuntimeCatalog.RuntimeModule mpv = module(UPSTREAM_CATALOG, "mpv");

        RuntimeCatalog.RuntimeArtifact arm64 = mpv.findArtifact("arm64-v8a", 34);
        assertNotNull(arm64);
        assertEquals(17793241L, arm64.size);
        assertTrue(arm64.url.endsWith("runtime-mpv-1.0.2-arm64-v8a.apk"));

        RuntimeCatalog.RuntimeArtifact v7a = mpv.findArtifact("armeabi-v7a", 34);
        assertNotNull(v7a);
        assertEquals(14582756L, v7a.size);
    }

    @Test
    public void normalizesAbiAliasesWhenSelectingArtifact() {
        RuntimeCatalog.RuntimeModule mpv = module(UPSTREAM_CATALOG, "mpv");

        assertNotNull(mpv.findArtifact("arm64", 34));
        assertNotNull(mpv.findArtifact("aarch64", 34));
        assertNotNull(mpv.findArtifact("ARM64_V8A", 34));
        assertNotNull(mpv.findArtifact("armv7a", 34));
    }

    @Test
    public void rejectsUnknownAbi() {
        RuntimeCatalog.RuntimeModule mpv = module(UPSTREAM_CATALOG, "mpv");

        assertNull(mpv.findArtifact("x86_64", 34));
        assertNull(mpv.findArtifact("", 34));
        assertNull(mpv.findArtifact(null, 34));
    }

    @Test
    public void skipsArtifactWhenDeviceSdkIsBelowMinSdk() {
        RuntimeCatalog.RuntimeModule mpv = module(UPSTREAM_CATALOG, "mpv");

        assertNotNull(mpv.findArtifact("arm64-v8a", 24));
        assertNull(mpv.findArtifact("arm64-v8a", 23));
    }

    @Test
    public void unknownSchemaIsRejectedRatherThanGuessed() {
        RuntimeCatalog catalog = GSON.fromJson("{\"schema\":99,\"modules\":[]}", RuntimeCatalog.class);

        assertNotNull(catalog);
        assertFalse(catalog.isSupported());
    }

    @Test
    public void missingSchemaIsRejected() {
        RuntimeCatalog catalog = GSON.fromJson("{\"modules\":[]}", RuntimeCatalog.class);

        assertNotNull(catalog);
        assertFalse(catalog.isSupported());
    }

    @Test
    public void artifactWithoutSha256IsInvalid() {
        RuntimeCatalog.RuntimeModule module = moduleFrom("{"
                + "\"id\":\"mpv\",\"packageName\":\"p\",\"compatibilityId\":\"c\",\"versionCode\":1,"
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":10,"
                + "\"url\":\"https://example.invalid/a.apk\"}]}");

        assertFalse(module.isValid());
    }

    @Test
    public void artifactOverPlainHttpIsInvalid() {
        RuntimeCatalog.RuntimeModule module = moduleFrom("{"
                + "\"id\":\"mpv\",\"packageName\":\"p\",\"compatibilityId\":\"c\",\"versionCode\":1,"
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":10,"
                + "\"sha256\":\"" + A + "\","
                + "\"url\":\"http://example.invalid/a.apk\"}]}");

        assertFalse(module.isValid());
    }

    @Test
    public void moduleWithoutArtifactsIsInvalid() {
        RuntimeCatalog.RuntimeModule module = moduleFrom(
                "{\"id\":\"mpv\",\"packageName\":\"p\",\"compatibilityId\":\"c\",\"versionCode\":1}");

        assertFalse(module.isValid());
    }

    @Test
    public void moduleMissingPackageNameIsInvalid() {
        RuntimeCatalog.RuntimeModule module = moduleFrom(
                "{\"id\":\"mpv\",\"compatibilityId\":\"c\",\"versionCode\":1,\"artifacts\":[]}");

        assertFalse(module.isValid());
    }

    @Test
    public void unknownModuleIdResolvesToNull() {
        RuntimeCatalog catalog = GSON.fromJson(UPSTREAM_CATALOG, RuntimeCatalog.class);

        assertNull(catalog.find("sherpa"));
        assertNull(catalog.find(""));
        assertNull(catalog.find(null));
    }

    @Test
    public void malformedJsonSurfacesAsParseError() {
        try {
            GSON.fromJson("{\"schema\":1,\"modules\":\"not-a-list\"}", RuntimeCatalog.class);
            assertTrue("expected a parse failure for a malformed catalog", false);
        } catch (JsonSyntaxException | IllegalStateException expected) {
            // The host must reject a malformed catalog instead of loading a partial one.
        }
    }

    private static final String A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /** Wraps a bare module object into the catalog envelope the host actually receives. */
    private static RuntimeCatalog.RuntimeModule moduleFrom(String moduleJson) {
        RuntimeCatalog catalog = GSON.fromJson(
                "{\"schema\":1,\"modules\":[" + moduleJson + "]}", RuntimeCatalog.class);
        RuntimeCatalog.RuntimeModule module = catalog.find("mpv");
        assertNotNull("catalog entry missing", module);
        return module;
    }

    private static RuntimeCatalog.RuntimeModule module(String json, String id) {
        RuntimeCatalog catalog = GSON.fromJson(json, RuntimeCatalog.class);
        RuntimeCatalog.RuntimeModule module = catalog.find(id);
        assertNotNull("catalog entry missing: " + id, module);
        return module;
    }
}
