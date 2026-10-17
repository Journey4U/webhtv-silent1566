package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/**
 * Registry behaviour: persistence, and the install/update/downgrade decision.
 */
public class RuntimeStoreTest {

    private static final Gson GSON = new Gson();

    private static final String SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final class MemoryStorage implements RuntimeStore.Storage {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value;
        }
    }

    private static RuntimeCatalog.RuntimeModule module(int versionCode, String compatibilityId) {
        String json = "{"
                + "\"schema\":1,\"modules\":[{"
                + "\"id\":\"sherpa\","
                + "\"packageName\":\"com.silent.runtime.sherpa\","
                + "\"compatibilityId\":\"" + compatibilityId + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"versionName\":\"1." + versionCode + ".0\","
                + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":100,"
                + "\"sha256\":\"" + SHA + "\","
                + "\"url\":\"https://example.invalid/module.apk\"}]"
                + "}]}";
        return GSON.fromJson(json, RuntimeCatalog.class).find("sherpa");
    }

    private static RuntimeStore.Entry entry(int versionCode, String compatibilityId) {
        RuntimeCatalog.RuntimeModule m = module(versionCode, compatibilityId);
        return new RuntimeStore.Entry(m, m.findArtifact("arm64-v8a", 34), 1000L);
    }

    @Test
    public void startsEmpty() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());

        assertNull(store.get("sherpa"));
        assertFalse(store.isInstalled("sherpa"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    public void recordsAndReadsBackAnEntry() {
        MemoryStorage storage = new MemoryStorage();
        RuntimeStore store = new RuntimeStore(storage);
        store.put(entry(3, "sherpa-onnx-jni-v1"));

        RuntimeStore reloaded = new RuntimeStore(storage);
        RuntimeStore.Entry read = reloaded.get("sherpa");
        assertNotNull(read);
        assertEquals("com.silent.runtime.sherpa", read.packageName);
        assertEquals("sherpa-onnx-jni-v1", read.compatibilityId);
        assertEquals(3, read.versionCode);
        assertEquals("arm64-v8a", read.abi);
        assertEquals(SHA, read.sha256);
        assertTrue(read.isValid());
    }

    @Test
    public void removeDropsOnlyTheNamedModule() {
        MemoryStorage storage = new MemoryStorage();
        RuntimeStore store = new RuntimeStore(storage);
        store.put(entry(3, "sherpa-onnx-jni-v1"));

        store.remove("sherpa");

        assertFalse(new RuntimeStore(storage).isInstalled("sherpa"));
    }

    @Test
    public void removeOfUnknownModuleIsANoop() {
        MemoryStorage storage = new MemoryStorage();
        RuntimeStore store = new RuntimeStore(storage);
        store.put(entry(3, "sherpa-onnx-jni-v1"));
        String before = storage.value;

        store.remove("absent");

        assertEquals(before, storage.value);
    }

    @Test
    public void corruptedRegistryDoesNotResurrectAModule() {
        MemoryStorage storage = new MemoryStorage();
        storage.value = "{not json";

        RuntimeStore store = new RuntimeStore(storage);

        assertFalse(store.isInstalled("sherpa"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    public void ignoresEntryWithoutId() {
        MemoryStorage storage = new MemoryStorage();
        RuntimeStore store = new RuntimeStore(storage);

        store.put(new RuntimeStore.Entry());
        store.put(null);

        assertTrue(store.all().isEmpty());
    }

    @Test
    public void decidesInstallWhenNothingIsInstalled() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());

        assertEquals(RuntimeStore.Decision.INSTALL, store.decide(module(3, "sherpa-onnx-jni-v1")));
        assertTrue(store.decide(module(3, "sherpa-onnx-jni-v1")).shouldInstall());
    }

    @Test
    public void decidesCurrentWhenVersionAndContractMatch() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(entry(3, "sherpa-onnx-jni-v1"));

        RuntimeStore.Decision decision = store.decide(module(3, "sherpa-onnx-jni-v1"));

        assertEquals(RuntimeStore.Decision.CURRENT, decision);
        assertFalse(decision.shouldInstall());
    }

    @Test
    public void decidesUpdateForANewerVersion() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(entry(3, "sherpa-onnx-jni-v1"));

        assertEquals(RuntimeStore.Decision.UPDATE, store.decide(module(4, "sherpa-onnx-jni-v1")));
    }

    @Test
    public void refusesDowngrade() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(entry(4, "sherpa-onnx-jni-v1"));

        RuntimeStore.Decision decision = store.decide(module(3, "sherpa-onnx-jni-v1"));

        assertEquals(RuntimeStore.Decision.DOWNGRADE, decision);
        assertFalse(decision.shouldInstall());
    }

    @Test
    public void reinstallsWhenTheContractChangesAtTheSameVersion() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(entry(3, "sherpa-onnx-jni-v1"));

        RuntimeStore.Decision decision = store.decide(module(3, "sherpa-onnx-jni-v2"));

        assertEquals(RuntimeStore.Decision.REINSTALL, decision);
        assertTrue(decision.shouldInstall());
    }

    @Test
    public void rejectsInvalidCatalogEntryInsteadOfGuessing() {
        RuntimeStore store = new RuntimeStore(new MemoryStorage());

        assertEquals(RuntimeStore.Decision.INVALID, store.decide(null));
        assertEquals(RuntimeStore.Decision.INVALID, store.decide(
                GSON.fromJson("{\"schema\":1,\"modules\":[{\"id\":\"x\"}]}", RuntimeCatalog.class).find("x")));
    }

    @Test
    public void reinstallsWhenTheStoredEntryIsUnusable() {
        MemoryStorage storage = new MemoryStorage();
        RuntimeStore store = new RuntimeStore(storage);
        RuntimeStore.Entry broken = entry(3, "sherpa-onnx-jni-v1");
        broken.packageName = "";
        store.put(broken);

        assertEquals(RuntimeStore.Decision.REINSTALL, store.decide(module(3, "sherpa-onnx-jni-v1")));
    }
}
