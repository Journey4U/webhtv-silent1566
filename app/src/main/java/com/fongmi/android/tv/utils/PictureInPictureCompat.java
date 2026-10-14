package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.os.Build;

public final class PictureInPictureCompat {

    public static boolean isInPictureInPictureMode(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return activity.isInPictureInPictureMode();
    }

    private PictureInPictureCompat() {
    }
}