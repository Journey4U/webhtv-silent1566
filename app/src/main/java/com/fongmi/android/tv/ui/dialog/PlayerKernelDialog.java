package com.fongmi.android.tv.ui.dialog;

import android.content.res.Resources;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.Arrays;

public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    public interface ExternalListener {
        void onExternal();
    }

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        show(activity, selected, listener, null);
    }

    public static void show(FragmentActivity activity, int selected, Listener listener, ExternalListener external) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] kernel = kernels(activity.getResources());
        String[] items = withExternal(kernel, external, activity.getString(R.string.player_kernel_external));
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, items, PlayerSetting.availableKernelIndex(current), which -> notifySelected(kernel.length, current, which, listener, external));
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        show(fragment, selected, listener, null);
    }

    public static void show(Fragment fragment, int selected, Listener listener, ExternalListener external) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] kernel = kernels(fragment.getResources());
        String[] items = withExternal(kernel, external, fragment.getString(R.string.player_kernel_external));
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, items, PlayerSetting.availableKernelIndex(current), which -> notifySelected(kernel.length, current, which, listener, external));
    }

    /**
     * 内核标签，按 EXO → IJK → MPV → 系统 的优先级顺序排列，**只含本机可用的内核**。
     *
     * <p>模块化之后 MPV 与 IJK 可能不存在；列出一个选不动的内核只会让用户选中后收到失败提示。
     * 因为菜单被过滤，下标与 {@link PlayerSetting#kernelAt} 的位次不再对应——
     * 从菜单下标反推内核必须用 {@link PlayerSetting#availableKernelAt}。
     */
    public static String[] kernels(Resources resources) {
        String[] labels = PlayerSetting.orderKernels(resources.getStringArray(R.array.select_player_kernel));
        int[] available = PlayerSetting.availableKernels();
        String[] filtered = new String[available.length];
        for (int i = 0; i < available.length; i++) {
            int kernel = available[i];
            filtered[i] = kernel < labels.length ? labels[kernel] : "";
        }
        return filtered;
    }

    private static String[] withExternal(String[] kernel, ExternalListener external, String label) {
        if (external == null) return kernel;
        String[] items = Arrays.copyOf(kernel, kernel.length + 1);
        items[kernel.length] = label;
        return items;
    }

    private static void notifySelected(int count, int current, int selected, Listener listener, ExternalListener external) {
        if (external != null && selected >= count) {
            external.onExternal();
            return;
        }
        // The menu lists only available kernels, so its indices are not KERNEL_ORDER ranks.
        int target = PlayerSetting.availableKernelAt(selected);
        if (target != current && listener != null) listener.onSelected(target);
    }
}
