package com.fongmi.android.tv.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches and parses the module catalog.
 *
 * <p>The catalog URL is user-overridable so the module source is never a single point of failure,
 * mirroring how the lab configuration already supports a local or remote source.
 */
public final class RuntimeRepository {

    /** Default catalog location. Overridable at runtime. */
    public static final String DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/Silent1566/webhtv-plugins/main/catalog.json";

    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_CATALOG_BYTES = 1024 * 1024;

    private final Fetcher fetcher;

    public RuntimeRepository(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Fetches raw bytes for a URL. Injected so parsing stays testable without a network. */
    public interface Fetcher {
        byte[] fetch(String url) throws IOException;
    }

    /** Parses a catalog payload, rejecting anything this build cannot honour. */
    public static RuntimeCatalog parse(String json) throws IOException {
        if (RuntimeText.isBlank(json)) throw new IOException("模块清单为空");
        RuntimeCatalog catalog;
        try {
            catalog = GSON.fromJson(json, RuntimeCatalog.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("模块清单格式错误", e);
        }
        if (catalog == null) throw new IOException("模块清单无法解析");
        if (!catalog.isSupported()) {
            throw new IOException("不支持的模块清单版本：" + catalog.schema);
        }
        return catalog;
    }

    public RuntimeCatalog load(String url) throws IOException {
        if (RuntimeText.isBlank(url)) throw new IOException("缺少模块清单地址");
        if (!url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IOException("模块清单地址必须使用 HTTPS");
        }
        if (fetcher == null) throw new IOException("网络不可用");
        byte[] payload = fetcher.fetch(url);
        if (payload == null || payload.length == 0) throw new IOException("模块清单为空");
        if (payload.length > MAX_CATALOG_BYTES) throw new IOException("模块清单过大");
        return parse(new String(payload, StandardCharsets.UTF_8));
    }

    public RuntimeCatalog loadDefault() throws IOException {
        return load(DEFAULT_CATALOG_URL);
    }

    /** Reads a catalog from a local file, for offline or mirrored distribution. */
    public static RuntimeCatalog loadFile(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("模块清单文件不存在");
        return parse(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /** HTTP fetcher used on device. */
    public static Fetcher httpFetcher() {
        return url -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "WebHTV-Runtime");
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("获取模块清单失败 HTTP " + code);
                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384];
                    int read;
                    int total = 0;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_CATALOG_BYTES) throw new IOException("模块清单过大");
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        };
    }
}
