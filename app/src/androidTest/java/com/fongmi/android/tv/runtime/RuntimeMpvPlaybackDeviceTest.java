package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import is.xyz.mpv.MPVLib;

/**
 * Real playback regression for the modularised MPV.
 *
 * <p>{@link RuntimeMpvFunctionalDeviceTest} proves the core initialises and answers property reads.
 * That still leaves the decode path untested: a bundle can have a working core and still fail to
 * demux or decode. This test loads an actual video file and waits for {@code FILE_LOADED}, which
 * only fires after the demuxer and decoder chain have accepted the media.
 *
 * <p>A video file is required. Some blocks run against an existing sample on the device; when none
 * is present those are skipped rather than failed, because the absence of a fixture is not a
 * defect in the module.
 *
 * <p><b>Run this class alone</b> — the mpv context is process-wide and cannot be re-created after
 * destroy:
 * <pre>
 * adb shell am instrument -w \
 *   -e class com.fongmi.android.tv.runtime.RuntimeMpvPlaybackDeviceTest \
 *   com.silent.android.webhtv.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeMpvPlaybackDeviceTest {

    private static final String MODULE_ID = "mpv";
    private static final String MODULE_PACKAGE = "com.silent.runtime.mpv";
    private static final String ABI = "arm64-v8a";

    /** Candidate media on the device; the first readable one is used. */
    private static final String[] MEDIA = {
            "/sdcard/v_m_current.mp4",
            "/sdcard/cold_m1.mp4",
    };

    private static final long LOAD_TIMEOUT_SECONDS = 30;

    private static final class MemoryStorage implements RuntimeStore.Storage {
        private String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }
    }

    private static boolean installed() {
        return RuntimeAndroid.packageInfo().nativeLibraryDir(MODULE_PACKAGE) != null;
    }

    private void requireModule() {
        assumeTrue("module " + MODULE_PACKAGE + " is not installed", installed());
    }

    /** The first media file that exists and is readable, or {@code null}. */
    private static File media() {
        for (String path : MEDIA) {
            File file = new File(path);
            if (file.isFile() && file.canRead() && file.length() > 0) return file;
        }
        return null;
    }

    private void requireMedia() {
        assumeTrue("no readable media fixture on the device", media() != null);
    }

    private RuntimeStore seededStore(RuntimeManifest manifest) {
        RuntimeCatalog.RuntimeModule module = new RuntimeCatalog.RuntimeModule();
        module.id = manifest.module;
        module.packageName = manifest.packageName;
        module.compatibilityId = manifest.compatibilityId;
        module.versionCode = manifest.versionCode;
        module.versionName = manifest.versionName;
        RuntimeCatalog.RuntimeArtifact artifact = new RuntimeCatalog.RuntimeArtifact();
        artifact.abi = manifest.abi;
        artifact.size = 1;
        artifact.sha256 = RuntimeIntegrity.sha256("seeded".getBytes(StandardCharsets.UTF_8));
        artifact.url = "https://example.invalid/" + manifest.module + ".apk";
        module.artifacts = List.of(artifact);

        RuntimeStore store = new RuntimeStore(new MemoryStorage());
        store.put(new RuntimeStore.Entry(module, artifact, 0L));
        return store;
    }

    /** Loads the module's libraries from the installed module. */
    private RuntimeMpv.Status loadModule() throws Exception {
        RuntimeManifest manifest = RuntimeManifest.parse(
                RuntimeAndroid.manifestSource().read(MODULE_PACKAGE));
        assertNotNull(manifest);
        return RuntimeMpv.prepare(
                new RuntimeLoader(seededStore(manifest), RuntimeAndroid.packageInfo(), path -> {
                }, RuntimeAndroid.hostLibraries(), RuntimeAndroid.manifestSource()),
                RuntimeAndroid.packageInfo(),
                name -> RuntimeAndroid.hostLibraries().resolve(name),
                System::load,
                ABI);
    }

    /**
     * The decisive regression: the module must be able to open and demux a real file.
     *
     * <p>{@code FILE_LOADED} is only emitted once the demuxer has accepted the media, so reaching
     * it proves the decode chain works, not just that the libraries are present.
     */
    @Test
    public void moduleLoadsARealMediaFile() throws Exception {
        requireModule();
        requireMedia();

        RuntimeMpv.Status status = loadModule();
        assertTrue("module libraries failed to load: " + status.getProblem(), status.isOk());

        App context = ApplicationProvider.getApplicationContext();
        assertTrue("mpv context creation failed", MPVLib.tryCreate(context));
        try {
            MPVLib.initializeCreatedContext();

            CountDownLatch loaded = new CountDownLatch(1);
            CountDownLatch ended = new CountDownLatch(1);
            AtomicInteger endReason = new AtomicInteger(-1);
            AtomicReference<String> endText = new AtomicReference<>("");

            MPVLib.addObserver(new MPVLib.EventObserver() {
                @Override
                public void eventProperty(String property) {
                }

                @Override
                public void eventProperty(String property, long value) {
                }

                @Override
                public void eventProperty(String property, boolean value) {
                }

                @Override
                public void eventProperty(String property, String value) {
                }

                @Override
                public void eventProperty(String property, double value) {
                }

                @Override
                public void event(int eventId) {
                    if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) loaded.countDown();
                    if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) ended.countDown();
                }

                @Override
                public void endFile(int reason, int error, String errorText) {
                    endReason.set(error);
                    endText.set(errorText == null ? "" : errorText);
                    ended.countDown();
                }
            });

            File file = media();
            // Use a null video/audio output so this test measures the demux+decode chain in
            // isolation. Every real vo backend needs an Android Surface, and attaching one is the
            // player's job (verified separately); without this the engine would fail with
            // "Missing surface pointer" and the failure would say nothing about the module.
            MPVLib.setOptionString("vo", "null");
            MPVLib.setOptionString("ao", "null");
            int rc = MPVLib.command(new String[]{"loadfile", file.getAbsolutePath(), "replace"});
            assertEquals("loadfile should be accepted", 0, rc);

            assertTrue("FILE_LOADED was not emitted within " + LOAD_TIMEOUT_SECONDS + "s; "
                            + "the module cannot demux this media",
                    loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // Track metadata only exists once the demuxer has parsed the file.
            Integer width = MPVLib.getPropertyInt("width");
            Integer height = MPVLib.getPropertyInt("height");
            assertNotNull("width should be readable after load", width);
            assertNotNull("height should be readable after load", height);
            assertTrue("decoded video dimensions should be positive, got " + width + "x" + height,
                    width > 0 && height > 0);

            String duration = MPVLib.getPropertyString("duration");
            assertNotNull("duration should be readable after load", duration);
            assertFalse("duration should not be empty", duration.trim().isEmpty());
        } finally {
            MPVLib.destroyCreatedContext();
        }
    }

    /**
     * The engine's own option surface must still apply, since the module supplies the libraries the
     * options act on. A rejected option would mean an ABI or feature mismatch in the bundle.
     */
    @Test
    public void moduleAcceptsTheEnginesCoreOptions() throws Exception {
        requireModule();

        RuntimeMpv.Status status = loadModule();
        assertTrue("module libraries failed to load: " + status.getProblem(), status.isOk());

        App context = ApplicationProvider.getApplicationContext();
        assertTrue("mpv context creation failed", MPVLib.tryCreate(context));
        try {
            MPVLib.initializeCreatedContext();

            // The same options the engine sets; see MpvPlayerEngine's builder.
            assertEquals(0, MPVLib.setOptionString("hwdec", "no"));
            assertEquals(0, MPVLib.setOptionString("vo", "gpu-next"));
            assertEquals(0, MPVLib.setOptionString("video-sync", "audio"));
            assertEquals(0, MPVLib.setOptionString("framedrop", "vo"));

            String hwdec = MPVLib.getPropertyString("hwdec");
            assertNotNull("hwdec should be readable", hwdec);
        } finally {
            MPVLib.destroyCreatedContext();
        }
    }

    /** The command surface used to drive playback must work. */
    @Test
    public void moduleAcceptsPlaybackCommands() throws Exception {
        requireModule();

        RuntimeMpv.Status status = loadModule();
        assertTrue("module libraries failed to load: " + status.getProblem(), status.isOk());

        App context = ApplicationProvider.getApplicationContext();
        assertTrue("mpv context creation failed", MPVLib.tryCreate(context));
        try {
            MPVLib.initializeCreatedContext();

            assertEquals(0, MPVLib.command(new String[]{"set", "pause", "yes"}));
            MPVLib.setPropertyBoolean("pause", true);
            assertNotNull(MPVLib.getPropertyString("pause"));
        } finally {
            MPVLib.destroyCreatedContext();
        }
    }
}
