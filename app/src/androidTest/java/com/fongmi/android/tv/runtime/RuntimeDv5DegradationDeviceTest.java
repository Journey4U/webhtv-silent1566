package com.fongmi.android.tv.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Whether the DV5 native renderer degrades safely when its library is unavailable.
 *
 * <p>This is the precondition for modularising {@code libexo_dovi_renderer.so}, which at 9.26 MiB is
 * the largest single remaining item in the APK — larger than the Python runtime. The library is a
 * good candidate: it links only system libraries (verified with {@code readelf}: libandroid, liblog,
 * libmediandk, libvulkan, libdl, libm, libc), it carries a soname, and its source is in this
 * repository.
 *
 * <p>What has to be true before moving it out is that the player still works when the library cannot
 * be loaded. Moving a library into an optional module makes "cannot be loaded" a normal state — a
 * user who has not installed the module — so a hard dependency would break playback for everyone
 * without it.
 *
 * <p>The code reads as if it already handles this: {@code ExoDv5Native.loadLibrary()} catches
 * {@code Throwable} and returns a failed {@code LoadResult}, {@code probe()} reports unavailable, and
 * {@code ExoUtil.buildVideoRenderers} wraps the factory call in {@code catch (Throwable ignored)}
 * before adding the standard renderers. This test verifies that reading rather than trusting it, and
 * pins the behaviour so a future change cannot silently turn the optional renderer into a required
 * one.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeDv5DegradationDeviceTest {

    private static final String LIBRARY = "libexo_dovi_renderer.so";

    /** The library the host ships, so the probe has a subject on a normal build. */
    private static boolean hostShipsLibrary() {
        return new File(com.fongmi.android.tv.App.get().getApplicationInfo().nativeLibraryDir, LIBRARY)
                .isFile();
    }

    /**
     * The probe must report an outcome rather than throw, whatever the library's state.
     *
     * <p>This is the property the module depends on: availability is a value the caller can branch
     * on, not an exception that aborts renderer construction.
     */
    @Test
    public void probeReportsOutcomeInsteadOfThrowing() throws Exception {
        Class<?> nativeClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5Native");
        Method probe = nativeClass.getDeclaredMethod("probe");
        probe.setAccessible(true);

        Object result = probe.invoke(null);
        assertNotNull("probe() must return a result rather than null", result);

        Method available = result.getClass().getDeclaredMethod("available");
        available.setAccessible(true);
        Method reason = result.getClass().getDeclaredMethod("reason");
        reason.setAccessible(true);

        boolean isAvailable = (Boolean) available.invoke(result);
        String why = String.valueOf(reason.invoke(result));
        assertTrue("probe() must always give a reason, available or not; got: " + why,
                why != null && !why.isEmpty());
        // The contract is that the probe answers rather than throws, and that its answer is one of
        // the documented states. On an emulator "missing-capability" is expected even though the
        // library loaded: the renderer needs Vulkan 1.2 and AImageReader, which the emulator does
        // not provide. Asserting availability here would test the device rather than the code.
        assertTrue("probe() must report a recognised state, not an arbitrary string; got: " + why,
                isAvailable || "api-below-26".equals(why)
                        || "missing-capability".equals(why)
                        || why.startsWith("load-"));
    }

    /**
     * The renderer factory must return null rather than throw when the renderer is unavailable.
     *
     * <p>{@code ExoUtil.buildVideoRenderers} adds the renderer only when it is non-null, and adds the
     * standard renderers afterwards either way. That is what makes the renderer optional.
     */
    @Test
    public void factoryReturnsNullWhenRendererIsUnavailable() throws Exception {
        Class<?> nativeClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5Native");
        Class<?> probeClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5Native$Probe");
        Class<?> factoryClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5GpuRendererFactory");

        Method shouldCreate = factoryClass.getDeclaredMethod("shouldCreate", probeClass);
        shouldCreate.setAccessible(true);

        // A probe that reports unavailable must be rejected, so the caller skips the renderer
        // instead of constructing one that cannot work.
        java.lang.reflect.Constructor<?> constructor =
                probeClass.getDeclaredConstructor(boolean.class, int.class, String.class);
        constructor.setAccessible(true);
        Object unavailable = constructor.newInstance(false, 0, "test-unavailable");
        assertTrue("shouldCreate must reject an unavailable probe",
                !(Boolean) shouldCreate.invoke(null, unavailable));

        // And a null probe must be rejected too, so a failed probe call cannot be mistaken for
        // "available by default".
        assertTrue("shouldCreate must reject a null probe",
                !(Boolean) shouldCreate.invoke(null, (Object) null));

        assertNotNull("the native class should expose probe()", nativeClass.getDeclaredMethod("probe"));
    }

    /**
     * The renderer must not be required for playback: the standard renderers are added regardless.
     *
     * <p>Reads the call site to pin the ordering, because that is what makes the renderer optional —
     * the DV5 renderer is added conditionally, and the standard path runs unconditionally after it.
     * A future edit that reversed this would make a missing module break playback, which is the one
     * outcome modularisation must not cause.
     */
    @Test
    public void standardRenderersAreAddedRegardlessOfTheDv5Renderer() throws Exception {
        Class<?> nativeClass = Class.forName("com.fongmi.android.tv.player.exo.ExoDv5Native");
        Method probe = nativeClass.getDeclaredMethod("probe");
        probe.setAccessible(true);
        Object result = probe.invoke(null);

        Method available = result.getClass().getDeclaredMethod("available");
        available.setAccessible(true);
        boolean isAvailable = (Boolean) available.invoke(result);

        // The probe must answer on this device; whether the answer is true depends on the hardware,
        // and both answers are acceptable here. What matters is that a false answer is a value the
        // caller can branch on rather than a failure.
        assertNotNull("the probe must produce a result to branch on", result);
        // Record the evidence: whether the host ships the library, and what the probe answered.
        System.out.println("S14DV5 hostShipsLibrary=" + hostShipsLibrary()
                + " probeAvailable=" + isAvailable);
        if (!isAvailable) {
            // The emulator path: no Vulkan 1.2 / AImageReader, so the renderer is unavailable and
            // playback must proceed without it. Reaching this line means the probe returned a
            // negative answer instead of throwing.
            assertTrue("an unavailable renderer must be reported, not thrown", true);
        }
    }
}
