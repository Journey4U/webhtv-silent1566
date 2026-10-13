package com.fongmi.android.tv.subtitle.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Android 6 contract: Python subtitle sources must be disabled without loading Chaquopy. */
public class Android6PythonSubtitleDisableTest {

    @Test
    public void pythonSubtitleRuntimeIsNotConstructedEagerlyOnApi23() throws Exception {
        String source = read(findMainJavaPath().resolve(Path.of(
                "com", "fongmi", "android", "tv", "subtitle", "source", "SubtitleScriptRuntimeFactory.java")));

        assertTrue("Subtitle runtime factory must gate Python on Chaquopy's API 24 floor",
                source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N"));
        assertFalse("Subtitle runtime factory must not construct PythonSubtitleScriptRuntime eagerly",
                source.contains("this(new PythonSubtitleScriptRuntime());"));
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
