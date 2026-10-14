package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Android6PictureInPictureCompatTest {

    private static String read(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static Path project(String relative) {
        Path moduleRelative = Path.of(relative.substring("app/".length()));
        return Files.exists(moduleRelative) ? moduleRelative : Path.of(relative);
    }

    @Test
    public void compatReturnsFalseBeforeApi24AndDelegatesOnApi24OrLater() throws Exception {
        String source = read(project("app/src/main/java/com/fongmi/android/tv/utils/PictureInPictureCompat.java"));
        assertTrue(source.contains("public static boolean isInPictureInPictureMode(Activity activity)"));
        assertTrue(source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.N"));
        assertTrue(source.contains("activity.isInPictureInPictureMode()"));
    }


    @Test
    public void playbackSourcesDoNotCallPlatformPictureInPictureModeDirectly() throws Exception {
        List<String> offenders = new ArrayList<>();
        Pattern direct = Pattern.compile("(?<!PictureInPictureCompat\\.)\\bisInPictureInPictureMode\\(");
        for (String relative : List.of(
                "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java",
                "app/src/main/java/com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java",
                "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
                "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
                "app/src/mobile/java/com/fongmi/android/tv/utils/PiP.java",
                "app/src/leanback/java/com/fongmi/android/tv/utils/PiP.java")) {
            String source = read(project(relative));
            Matcher matcher = direct.matcher(source);
            if (matcher.find()) offenders.add(relative);
        }
        assertTrue("Direct isInPictureInPictureMode calls remain: " + offenders, offenders.isEmpty());
    }
}