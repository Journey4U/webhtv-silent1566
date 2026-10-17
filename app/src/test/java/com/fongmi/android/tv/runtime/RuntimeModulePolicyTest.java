package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Module list presentation rules.
 *
 * <p>These decide what the user is offered, so they are pinned here rather than left implicit in
 * the adapter.
 */
public class RuntimeModulePolicyTest {

    private static final Gson GSON = new Gson();

    private static final String SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static RuntimeCatalog.RuntimeModule module(String id, String contract, int versionCode, String... abis) {
        StringBuilder artifacts = new StringBuilder();
        for (int i = 0; i < abis.length; i++) {
            if (i > 0) artifacts.append(',');
            artifacts.append("{\"abi\":\"").append(abis[i]).append("\",\"minSdk\":24,\"size\":23630316,")
                    .append("\"sha256\":\"").append(SHA).append("\",")
                    .append("\"url\":\"https://example.invalid/").append(id).append(".apk\"}");
        }
        String json = "{\"schema\":1,\"modules\":[{"
                + "\"id\":\"" + id + "\","
                + "\"packageName\":\"com.silent.runtime." + id + "\","
                + "\"compatibilityId\":\"" + contract + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"versionName\":\"1." + versionCode + ".0\","
                + "\"artifacts\":[" + artifacts + "]"
                + "}]}";
        return GSON.fromJson(json, RuntimeCatalog.class).find(id);
    }

    private static RuntimeStore.Entry installed(String id, int versionCode) {
        RuntimeCatalog.RuntimeModule module = module(id, "sherpa-onnx-jni-v1", versionCode, "arm64-v8a");
        return new RuntimeStore.Entry(module, module.findArtifact("arm64-v8a", 34), 0L);
    }

    @Test
    public void offersInstallWhenNothingIsInstalled() {
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 1, "arm64-v8a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(module, null, "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.INSTALL, state);
        assertTrue(RuntimeModulePolicy.canInstall(state));
        assertFalse(RuntimeModulePolicy.canUninstall(state));
    }

    @Test
    public void reportsInstalledForTheSameVersion() {
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 1, "arm64-v8a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(
                module, installed("sherpa", 1), "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.INSTALLED, state);
        assertFalse(RuntimeModulePolicy.canInstall(state));
        assertTrue(RuntimeModulePolicy.canUninstall(state));
    }

    @Test
    public void offersUpgradeWhenTheCatalogIsNewer() {
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 2, "arm64-v8a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(
                module, installed("sherpa", 1), "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.UPGRADE, state);
        assertTrue(RuntimeModulePolicy.canInstall(state));
        assertTrue(RuntimeModulePolicy.canUninstall(state));
    }

    @Test
    public void doesNotOfferDowngradeWhenInstalledIsNewer() {
        // The registry refuses a downgrade, so the UI must not present one as an action.
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 1, "arm64-v8a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(
                module, installed("sherpa", 3), "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.INSTALLED, state);
        assertFalse(RuntimeModulePolicy.canInstall(state));
    }

    @Test
    public void reportsUnsupportedAbiWhenNoArtifactMatches() {
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 1, "armeabi-v7a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(module, null, "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.UNSUPPORTED_ABI, state);
        assertFalse(RuntimeModulePolicy.canInstall(state));
    }

    @Test
    public void reportsUnsupportedContractForAnUnknownContract() {
        RuntimeCatalog.RuntimeModule module = module("sherpa", "unknown-contract-v9", 1, "arm64-v8a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(module, null, "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.UNSUPPORTED_CONTRACT, state);
        assertFalse(RuntimeModulePolicy.canInstall(state));
    }

    @Test
    public void keepsAnInstalledModuleVisibleEvenWithoutAMatchingArtifact() {
        // Uninstalling must stay possible; hiding the row would strand the user.
        RuntimeCatalog.RuntimeModule module = module("sherpa", "sherpa-onnx-jni-v1", 1, "armeabi-v7a");

        RuntimeModulePolicy.State state = RuntimeModulePolicy.state(
                module, installed("sherpa", 1), "arm64-v8a", 34);

        assertEquals(RuntimeModulePolicy.State.INSTALLED_NO_ARTIFACT, state);
        assertFalse(RuntimeModulePolicy.canInstall(state));
        assertTrue(RuntimeModulePolicy.canUninstall(state));
    }

    @Test
    public void rejectsAnInvalidModule() {
        assertEquals(RuntimeModulePolicy.State.UNSUPPORTED_CONTRACT,
                RuntimeModulePolicy.state(null, null, "arm64-v8a", 34));
        assertEquals(RuntimeModulePolicy.State.UNSUPPORTED_CONTRACT,
                RuntimeModulePolicy.state(
                        GSON.fromJson("{\"schema\":1,\"modules\":[{\"id\":\"x\"}]}",
                                RuntimeCatalog.class).find("x"),
                        null, "arm64-v8a", 34));
    }

    @Test
    public void skipsArtifactsBelowTheDeviceSdk() {
        RuntimeCatalog.RuntimeModule module = GSON.fromJson("{"
                + "\"schema\":1,\"modules\":[{\"id\":\"sherpa\",\"packageName\":\"p\","
                + "\"compatibilityId\":\"sherpa-onnx-jni-v1\",\"versionCode\":1,"
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":30,\"size\":10,"
                + "\"sha256\":\"" + SHA + "\",\"url\":\"https://example.invalid/a.apk\"}]}]}",
                RuntimeCatalog.class).find("sherpa");

        assertEquals(RuntimeModulePolicy.State.UNSUPPORTED_ABI,
                RuntimeModulePolicy.state(module, null, "arm64-v8a", 24));
        assertEquals(RuntimeModulePolicy.State.INSTALL,
                RuntimeModulePolicy.state(module, null, "arm64-v8a", 30));
    }

    @Test
    public void formatsSizesInBinaryUnits() {
        assertEquals("", RuntimeModulePolicy.displaySize(0));
        assertEquals("", RuntimeModulePolicy.displaySize(-1));
        assertEquals("512 B", RuntimeModulePolicy.displaySize(512));
        assertEquals("1.0 KB", RuntimeModulePolicy.displaySize(1024));
        assertEquals("23.6 MB", RuntimeModulePolicy.displaySize(24722720));
        assertEquals("1.0 GB", RuntimeModulePolicy.displaySize(1073741824L));
    }
}
