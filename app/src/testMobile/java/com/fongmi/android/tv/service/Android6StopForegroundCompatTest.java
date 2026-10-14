package com.fongmi.android.tv.service;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class Android6StopForegroundCompatTest {

    private static String read(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static Path project(String relative) {
        Path moduleRelative = Path.of(relative.substring("app/".length()));
        return Files.exists(moduleRelative) ? moduleRelative : Path.of(relative);
    }

    @Test
    public void playbackServiceStopsForegroundOnApi23WithoutStopForegroundInt() throws Exception {
        String source = read(project("app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java"));
        assertTrue(source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.N"));
        assertTrue(source.contains("stopForeground(true)"));
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"));
    }
}