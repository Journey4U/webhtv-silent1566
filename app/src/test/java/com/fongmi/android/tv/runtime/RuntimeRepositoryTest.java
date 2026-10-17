package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Catalog fetching and parsing. A malformed or unsupported catalog must be rejected outright
 * rather than partially honoured.
 */
public class RuntimeRepositoryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String VALID = "{"
            + "\"schema\":1,\"modules\":[{"
            + "\"id\":\"sherpa\","
            + "\"packageName\":\"com.silent.runtime.sherpa\","
            + "\"compatibilityId\":\"sherpa-onnx-jni-v1\","
            + "\"versionCode\":3,"
            + "\"versionName\":\"1.3.0\","
            + "\"artifacts\":[{\"abi\":\"arm64-v8a\",\"minSdk\":24,\"size\":100,"
            + "\"sha256\":\"" + SHA + "\","
            + "\"url\":\"https://example.invalid/sherpa.apk\"}]"
            + "}]}";

    private static RuntimeRepository repositoryReturning(String payload) {
        return new RuntimeRepository(url -> payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void loadsAndParsesACatalogOverHttps() throws IOException {
        RuntimeCatalog catalog = repositoryReturning(VALID).load("https://example.invalid/catalog.json");

        assertNotNull(catalog);
        assertTrue(catalog.isSupported());
        assertNotNull(catalog.find("sherpa"));
    }

    @Test
    public void usesTheDefaultUrlWhenAsked() throws IOException {
        StringBuilder seen = new StringBuilder();
        RuntimeRepository repository = new RuntimeRepository(url -> {
            seen.append(url);
            return VALID.getBytes(StandardCharsets.UTF_8);
        });

        repository.loadDefault();

        assertEquals(RuntimeRepository.DEFAULT_CATALOG_URL, seen.toString());
    }

    @Test
    public void refusesPlainHttpCatalogUrl() {
        // A catalog fetched over plaintext could be swapped, pointing the host at a hostile module.
        try {
            repositoryReturning(VALID).load("http://example.invalid/catalog.json");
            fail("expected plaintext catalog URL to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void refusesBlankUrl() {
        try {
            repositoryReturning(VALID).load("");
            fail("expected a blank URL to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("清单地址"));
        }
    }

    @Test
    public void refusesEmptyPayload() {
        try {
            repositoryReturning("").load("https://example.invalid/catalog.json");
            fail("expected an empty payload to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("清单为空"));
        }
    }

    @Test
    public void refusesNullPayload() {
        try {
            repositoryReturning(null).load("https://example.invalid/catalog.json");
            fail("expected a null payload to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("清单为空"));
        }
    }

    @Test
    public void surfacesFetcherFailure() {
        RuntimeRepository repository = new RuntimeRepository(url -> {
            throw new IOException("network down");
        });

        try {
            repository.load("https://example.invalid/catalog.json");
            fail("expected the fetch failure to propagate");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("network down"));
        }
    }

    @Test
    public void rejectsUnsupportedSchema() {
        try {
            RuntimeRepository.parse("{\"schema\":99,\"modules\":[]}");
            fail("expected an unsupported schema to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不支持的模块清单版本"));
        }
    }

    @Test
    public void rejectsMissingSchema() {
        try {
            RuntimeRepository.parse("{\"modules\":[]}");
            fail("expected a missing schema to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不支持的模块清单版本"));
        }
    }

    @Test
    public void rejectsMalformedJson() {
        try {
            RuntimeRepository.parse("{not json");
            fail("expected malformed JSON to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("格式错误"));
        }
    }

    @Test
    public void rejectsBlankPayload() {
        try {
            RuntimeRepository.parse("");
            fail("expected a blank payload to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("清单为空"));
        }
    }

    @Test
    public void loadsACatalogFromALocalFile() throws IOException {
        File file = folder.newFile("catalog.json");
        Files.write(file.toPath(), VALID.getBytes(StandardCharsets.UTF_8));

        RuntimeCatalog catalog = RuntimeRepository.loadFile(file);

        assertNotNull(catalog.find("sherpa"));
    }

    @Test
    public void rejectsAMissingLocalCatalogFile() {
        try {
            RuntimeRepository.loadFile(new File(folder.getRoot(), "absent.json"));
            fail("expected a missing file to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("文件不存在"));
        }
    }

    @Test
    public void refusesAnOversizedCatalog() {
        StringBuilder huge = new StringBuilder("{\"schema\":1,\"modules\":[");
        while (huge.length() < 1024 * 1024 + 1024) huge.append(' ');
        huge.append("]}");

        try {
            repositoryReturning(huge.toString()).load("https://example.invalid/catalog.json");
            fail("expected an oversized catalog to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("过大"));
        }
    }

    @Test
    public void rejectsCatalogWithoutModules() throws IOException {
        RuntimeCatalog catalog = RuntimeRepository.parse("{\"schema\":1}");

        assertTrue(catalog.modules().isEmpty());
        assertFalse(catalog.isSupported() && catalog.find("sherpa") != null);
    }
}
