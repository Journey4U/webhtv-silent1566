package com.fongmi.android.tv.api.loader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Android 6 contract for Chaquopy 17: its native runtime is built for API 24, so Python must not
 * be initialized eagerly on API 23. Keeping non-Python loaders available prevents adding a VOD
 * config from crashing merely because cleanup walks the loader facade.
 */
public class Android6PythonDisableTest {

    @Test
    public void pythonLoaderIsNotConstructedEagerlyOnApi23() throws Exception {
        String source = read(findMainJavaPath().resolve(Path.of(
                "com", "fongmi", "android", "tv", "api", "loader", "BaseLoader.java")));

        assertTrue("BaseLoader must gate PyLoader construction on Chaquopy's API 24 floor",
                source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N"));
        assertFalse("BaseLoader must not construct PyLoader in its constructor",
                source.contains("pyLoader = new PyLoader();"));
        assertTrue("Python requests on API 23 must degrade to SpiderNull",
                source.contains("return new SpiderNull();"));
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
