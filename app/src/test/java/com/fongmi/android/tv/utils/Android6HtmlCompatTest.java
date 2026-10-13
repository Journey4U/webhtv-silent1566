package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.text.Html;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Android 6 contract: Html.fromHtml(source, flags) was added in API 24. Text conversion must
 * dispatch to the API-23-compatible overload rather than letting runtime linkage fail.
 */
public class Android6HtmlCompatTest {

    @Test
    public void htmlConversionUsesApi23CompatibleOverload() throws Exception {
        String source = read(findMainJavaPath().resolve(Path.of(
                "com", "fongmi", "android", "tv", "utils", "TextCompat.java")));

        assertTrue("TextCompat must gate the two-argument Html.fromHtml overload on API 24",
                source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N"));
        assertTrue("TextCompat must call Html.fromHtml(source) on API 23",
                source.contains("Html.fromHtml(source)"));

        Html.fromHtml("ready", Html.FROM_HTML_MODE_LEGACY);
    }

    @Test
    public void htmlTextConversionsUseTextCompat() throws Exception {
        List<Path> paths = List.of(
                findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "bean", "Vod.java")),
                findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "utils", "Util.java")),
                findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "player", "karaoke", "KaraokeTrackRepository.java")));

        for (Path path : paths) {
            String source = read(path);
            assertTrue(path + " must not call the API-24 Html.fromHtml overload directly",
                    !source.contains("Html.fromHtml("));
            assertTrue(path + " must route HTML conversion through TextCompat",
                    source.contains("TextCompat.fromHtml("));
        }
    }

    private static Path findMainJavaPath() {
        Path moduleRelative = Path.of("src", "main", "java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "main", "java");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
