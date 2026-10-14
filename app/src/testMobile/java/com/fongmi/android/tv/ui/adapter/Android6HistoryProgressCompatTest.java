package com.fongmi.android.tv.ui.adapter;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class Android6HistoryProgressCompatTest {

    @Test
    public void historyAdaptersUseApi23SafeProgressOverload() throws Exception {
        assertGuarded("app/src/mobile/java/com/fongmi/android/tv/ui/adapter/HistoryAdapter.java");
        assertGuarded("app/src/leanback/java/com/fongmi/android/tv/ui/adapter/HistoryAdapter.java");
    }

    private static void assertGuarded(String path) throws Exception {
        String source = read(project(path));
        assertTrue(source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.N"));
        assertTrue(source.contains("setProgress(value)"));
        assertTrue(source.contains("setProgress(value, animate)") || source.contains("setProgress(value, true)"));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Path project(String relative) {
        Path moduleRelative = Path.of(relative.substring("app/".length()));
        return Files.exists(moduleRelative) ? moduleRelative : Path.of(relative);
    }
}
