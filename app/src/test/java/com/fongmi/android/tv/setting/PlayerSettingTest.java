package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerSettingTest {

    @Test
    public void nativeVideoOutput_includesNativePlayers() {
        assertFalse(PlayerSetting.useNativeVideoOutput(PlayerSetting.EXO));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.IJK));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.SYSTEM));
        assertTrue(PlayerSetting.useNativeVideoOutput(PlayerSetting.MPV));
    }

    @Test
    public void nativeVideoOutput_forcesSurfaceRender() {
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.IJK));
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.SYSTEM));
        assertEquals(0, PlayerSetting.getRender(PlayerSetting.MPV));
    }

    @Test
    public void nextPlayer_cyclesInKernelPriorityOrder() {
        assertEquals(PlayerSetting.IJK, PlayerSetting.nextPlayer(PlayerSetting.EXO));
        assertEquals(PlayerSetting.MPV, PlayerSetting.nextPlayer(PlayerSetting.IJK));
        assertEquals(PlayerSetting.SYSTEM, PlayerSetting.nextPlayer(PlayerSetting.MPV));
        assertEquals(PlayerSetting.EXO, PlayerSetting.nextPlayer(PlayerSetting.SYSTEM));
    }

    @Test
    public void kernelOrder_rankAndPositionRoundTrip() {
        assertArrayEquals(new int[]{PlayerSetting.EXO, PlayerSetting.IJK, PlayerSetting.MPV, PlayerSetting.SYSTEM}, PlayerSetting.KERNEL_ORDER);
        assertEquals(0, PlayerSetting.kernelRank(PlayerSetting.EXO));
        assertEquals(1, PlayerSetting.kernelRank(PlayerSetting.IJK));
        assertEquals(2, PlayerSetting.kernelRank(PlayerSetting.MPV));
        assertEquals(3, PlayerSetting.kernelRank(PlayerSetting.SYSTEM));
        for (int rank = 0; rank < PlayerSetting.kernelCount(); rank++) {
            assertEquals(rank, PlayerSetting.kernelRank(PlayerSetting.kernelAt(rank)));
        }
    }

    @Test
    public void kernelAt_clampsOutOfRangeRowsToExo() {
        assertEquals(PlayerSetting.EXO, PlayerSetting.kernelAt(-1));
        assertEquals(PlayerSetting.EXO, PlayerSetting.kernelAt(PlayerSetting.kernelCount()));
    }

    @Test
    public void orderKernels_reordersConstantIndexedLabels() {
        String[] labels = {"EXO", "IJK", "系统", "MPV"};
        assertArrayEquals(new String[]{"EXO", "IJK", "MPV", "系统"}, PlayerSetting.orderKernels(labels));
    }

    @Test
    public void firstUntriedPlayer_followsPriorityOrderSkippingTriedKernels() {
        boolean[] tried = new boolean[PlayerSetting.MPV + 1];
        tried[PlayerSetting.MPV] = true;
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(tried));
        tried[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.IJK, PlayerSetting.firstUntriedPlayer(tried));
        tried[PlayerSetting.IJK] = true;
        assertEquals(PlayerSetting.SYSTEM, PlayerSetting.firstUntriedPlayer(tried));
        tried[PlayerSetting.SYSTEM] = true;
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(tried));
    }

    @Test
    public void kernelIndexSize_coversEveryKernelConstant() {
        int size = PlayerSetting.kernelIndexSize();
        for (int kernel : PlayerSetting.KERNEL_ORDER) {
            assertTrue("内核常量 " + kernel + " 必须能作为下标写进长度 " + size + " 的数组", kernel < size);
        }
    }

    @Test
    public void firstUntriedPlayer_treatsUntrackableKernelsAsTriedSoFallbackTerminates() {
        // 标记表短于内核常量时，越界内核记不进去；若当成未试过返回，回退会拿到同一个内核不停循环。
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(new boolean[0]));
        boolean[] onlyExoTrackable = new boolean[PlayerSetting.EXO + 1];
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(onlyExoTrackable));
        onlyExoTrackable[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.NONE, PlayerSetting.firstUntriedPlayer(onlyExoTrackable));
    }

    @Test
    public void firstUntriedPlayer_startsFromExoWhateverKernelFailed() {
        boolean[] fromSystem = new boolean[PlayerSetting.MPV + 1];
        fromSystem[PlayerSetting.SYSTEM] = true;
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(fromSystem));

        boolean[] fromIjk = new boolean[PlayerSetting.MPV + 1];
        fromIjk[PlayerSetting.IJK] = true;
        assertEquals(PlayerSetting.EXO, PlayerSetting.firstUntriedPlayer(fromIjk));

        boolean[] fromExo = new boolean[PlayerSetting.MPV + 1];
        fromExo[PlayerSetting.EXO] = true;
        assertEquals(PlayerSetting.IJK, PlayerSetting.firstUntriedPlayer(fromExo));
    }

    /**
     * 内核菜单只列可用内核，所以菜单下标与 KERNEL_ORDER 的位次不再一致。
     *
     * <p>纯 JVM 下拿不到 PackageManager，MPV 与 IJK 自然不可用；这正是“零模块”的形状，
     * 也正好能钉住“按下标取的是可用列表而不是位次表”这个容易回归的点。
     */
    @Test
    public void availableKernels_listsOnlyUsableKernelsInPriorityOrder() {
        PlayerSetting.resetMpvAvailability();
        PlayerSetting.resetIjkAvailability();

        int[] available = PlayerSetting.availableKernels();

        assertArrayEquals(new int[]{PlayerSetting.EXO, PlayerSetting.SYSTEM}, available);
    }

    @Test
    public void availableKernelAt_mapsMenuIndexThroughTheAvailableList() {
        PlayerSetting.resetMpvAvailability();
        PlayerSetting.resetIjkAvailability();

        // Menu index 1 is SYSTEM here, not IJK: the menu skipped the unavailable kernels. Using
        // kernelAt(1) instead would hand the caller IJK, an engine that cannot start.
        assertEquals(PlayerSetting.EXO, PlayerSetting.availableKernelAt(0));
        assertEquals(PlayerSetting.SYSTEM, PlayerSetting.availableKernelAt(1));
        assertEquals(PlayerSetting.EXO, PlayerSetting.availableKernelAt(-1));
        assertEquals(PlayerSetting.EXO, PlayerSetting.availableKernelAt(2));
    }

    @Test
    public void availableKernelIndex_fallsBackWhenTheSelectedKernelIsGone() {
        PlayerSetting.resetMpvAvailability();
        PlayerSetting.resetIjkAvailability();

        assertEquals(0, PlayerSetting.availableKernelIndex(PlayerSetting.EXO));
        assertEquals(1, PlayerSetting.availableKernelIndex(PlayerSetting.SYSTEM));
        // The user was on MPV and then uninstalled the module; the menu must not select out of
        // range.
        assertEquals(0, PlayerSetting.availableKernelIndex(PlayerSetting.MPV));
        assertEquals(0, PlayerSetting.availableKernelIndex(PlayerSetting.IJK));
    }

    /** 可用列表与位次表必须能互相对照，否则菜单与回退链会各说各话。 */
    @Test
    public void availableKernels_isASubsetOfKernelOrder() {
        PlayerSetting.resetMpvAvailability();
        PlayerSetting.resetIjkAvailability();

        int[] available = PlayerSetting.availableKernels();
        int previousRank = -1;
        for (int kernel : available) {
            assertTrue("every listed kernel must be a real kernel", PlayerSetting.isPlayer(kernel));
            int rank = PlayerSetting.kernelRank(kernel);
            assertTrue("available kernels must keep priority order", rank > previousRank);
            previousRank = rank;
        }
    }

}
