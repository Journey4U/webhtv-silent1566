package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogRuntimeModulesBinding;
import com.fongmi.android.tv.runtime.RuntimeAndroid;
import com.fongmi.android.tv.runtime.RuntimeCatalog;
import com.fongmi.android.tv.runtime.RuntimeInstaller;
import com.fongmi.android.tv.runtime.RuntimeModulePolicy;
import com.fongmi.android.tv.runtime.RuntimeRepository;
import com.fongmi.android.tv.runtime.RuntimeStore;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.ui.adapter.RuntimeModuleAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;

import android.util.Log;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the runtime modules the host can add, and installs or removes them.
 *
 * <p>Installation goes through {@link RuntimeInstaller}, so the artifact digest is verified before
 * the APK reaches the platform installer. Removal only forgets the module record; the platform
 * owns the uninstall.
 */
public class RuntimeModulesDialog extends BaseAlertDialog implements RuntimeModuleAdapter.Listener {

    private static final String TAG_LOG = "RuntimeModule";

    private DialogRuntimeModulesBinding binding;
    private RuntimeModuleAdapter adapter;
    private RuntimeCatalog catalog;
    private boolean loading;
    /** Non-null while the last catalog fetch failed; shown until a fetch succeeds. */
    private String catalogFailure;

    public static void show(Fragment fragment) {
        new RuntimeModulesDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new RuntimeModulesDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogRuntimeModulesBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        MaterialAlertDialogBuilder builder = Util.isLeanback()
                ? builder()
                : new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog);
        return builder.setTitle(R.string.runtime_modules_title).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new RuntimeModuleAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, Util.isLeanback() ? 12 : 8));
        binding.recycler.setAdapter(adapter);
        render();
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(view -> refresh());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!Util.isLeanback()) configureWindow();
        if (catalog == null && !loading) refresh();
    }

    /** Re-fetches the catalog and re-renders. */
    private void refresh() {
        if (loading) return;
        loading = true;
        binding.refresh.setEnabled(false);
        binding.empty.setVisibility(View.GONE);
        binding.hint.setText(R.string.runtime_modules_refreshing);
        catalogFailure = null;
        Task.execute(() -> {
            try {
                RuntimeCatalog fetched = new RuntimeRepository(RuntimeRepository.httpFetcher()).loadDefault();
                App.post(() -> {
                    catalog = fetched;
                    catalogFailure = null;
                    loading = false;
                    binding.refresh.setEnabled(true);
                    render();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> {
                    loading = false;
                    binding.refresh.setEnabled(true);
                    catalogFailure = getString(R.string.runtime_modules_catalog_failed, message);
                    binding.hint.setText(catalogFailure);
                    render();
                });
            }
        });
    }

    private void render() {
        RuntimeStore store = store();        List<RuntimeModuleAdapter.Item> items = new ArrayList<>();

        if (catalog != null) {
            String abi = abi();
            for (RuntimeCatalog.RuntimeModule module : catalog.modules()) {
                if (module == null || module.id == null) continue;
                RuntimeMvp view = describe(module);
                RuntimeModulePolicy.State state = RuntimeModulePolicy.state(
                        module, store.get(module.id), abi, android.os.Build.VERSION.SDK_INT);
                RuntimeCatalog.RuntimeArtifact artifact = module.findArtifact(abi, android.os.Build.VERSION.SDK_INT);
                RuntimeStore.Entry installed = store.get(module.id);

                CharSequence version = installed != null && !RuntimeModulePolicy.canInstall(state)
                        ? installed.displayVersion()
                        : module.displayVersion();
                CharSequence size = artifact == null ? "" : RuntimeModulePolicy.displaySize(artifact.size);

                items.add(new RuntimeModuleAdapter.Item(
                        module.id, view.name, view.info, version, size, state));
            }
        }

        adapter.replaceAll(items);
        // Keep a fetch failure visible: render() runs after the error is reported, so it must not
        // clobber the message back to the generic hint.
        if (catalogFailure == null) binding.hint.setText(R.string.runtime_modules_hint);
        binding.empty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(adapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onAction(RuntimeModuleAdapter.Item item) {
        RuntimeCatalog.RuntimeModule module = catalog == null ? null : catalog.find(item.id);
        // Log via android.util.Log, not SpiderDebug: SpiderDebug filters by configured diagnostic
        // categories and drops the message silently when they are off, which made this path
        // impossible to diagnose from a device.
        Log.i(TAG_LOG, "install requested id=" + item.id + " catalog=" + (catalog != null)
                + " module=" + (module != null));
        if (module == null) return;
        if (!hasArtifactForThisDevice(module)) {
            Log.w(TAG_LOG, "no artifact for abi=" + abi() + " id=" + item.id);
            Notify.show(getString(R.string.runtime_modules_unsupported_abi));
            return;
        }
        showProgress();
        RuntimeInstaller installer = RuntimeAndroid.installer(store());
        String abi = abi();
        File workDir = RuntimeAndroid.downloadDir();
        // Install synchronously downloads tens of megabytes and then drives the platform installer.
        // Running that on the click handler's thread would block the UI for the whole download —
        // no progress, no response, and nothing in the log. Dispatch it off the main thread.
        Task.execute(() -> installer.install(module, abi, workDir, new RuntimeInstaller.Callback() {
            @Override
            public void onProgress(String message) {
                Log.i(TAG_LOG, "progress id=" + item.id + " msg=" + message);
                App.post(() -> progressText.setText(message));
            }

            @Override
            public void onInstalled(RuntimeStore.Entry entry) {
                Log.i(TAG_LOG, "installed id=" + entry.id + " version=" + entry.displayVersion());
                App.post(() -> {
                    dismissProgress();
                    // The kernel picker caches whether MPV is available; without this a user who
                    // just installed the module would still not see it once the bundled assets are
                    // gone.
                    PlayerSetting.resetMpvAvailability();
                    Notify.show(getString(R.string.runtime_modules_installed_ok, item.name));
                    render();
                });
            }

            @Override
            public void onFailed(String message) {
                Log.w(TAG_LOG, "install failed id=" + item.id + " msg=" + message);
                App.post(() -> {
                    dismissProgress();
                    Notify.show(getString(R.string.runtime_modules_install_failed, message));
                });
            }
        }));
    }

    @Override
    public void onRemove(RuntimeModuleAdapter.Item item) {
        new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog)
                .setTitle(R.string.runtime_modules_uninstall)
                .setMessage(getString(R.string.runtime_modules_uninstall_confirm, item.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.runtime_modules_uninstall, (dialog, which) -> uninstall(item))
                .show();
    }

    private void uninstall(RuntimeModuleAdapter.Item item) {
        RuntimeCatalog.RuntimeModule module = catalog == null ? null : catalog.find(item.id);
        String packageName = module == null ? null : module.packageName;
        // Forget first so the host stops offering the capability even if the user cancels the
        // platform's confirmation screen.
        store().remove(item.id);
        PlayerSetting.resetMpvAvailability();
        render();
        if (packageName == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireActivity().startActivity(intent);
        } catch (Exception e) {
            Notify.show(getString(R.string.runtime_modules_uninstall_failed, packageName));
        }
        Notify.show(getString(R.string.runtime_modules_uninstalled_ok, item.name));
    }

    private boolean hasArtifactForThisDevice(RuntimeCatalog.RuntimeModule module) {
        return module.findArtifact(abi(), android.os.Build.VERSION.SDK_INT) != null;
    }

    private RuntimeStore store() {
        return RuntimeAndroid.store();
    }

    private static String abi() {
        return com.fongmi.android.tv.runtime.RuntimeAbi.resolve(
                com.fongmi.android.tv.BuildConfig.FLAVOR_abi, android.os.Build.SUPPORTED_ABIS);
    }

    /** Local presentation for a module id; the catalog carries no user-facing text. */
    private RuntimeMvp describe(RuntimeCatalog.RuntimeModule module) {
        if ("sherpa".equals(module.id)) {
            return new RuntimeMvp(
                    getString(R.string.runtime_module_sherpa_name),
                    getString(R.string.runtime_module_sherpa_info));
        }
        return new RuntimeMvp(module.id, "");
    }

    private record RuntimeMvp(CharSequence name, CharSequence info) {
    }

    private AlertDialog progressDialog;
    private TextView progressText;

    private void showProgress() {
        dismissProgress();
        progressText = new TextView(requireContext());
        progressText.setPadding(48, 32, 48, 32);
        progressDialog = new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog)
                .setTitle(R.string.runtime_modules_title)
                .setView(progressText)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        progressDialog = null;
    }

    @Override
    public void onDestroyView() {
        dismissProgress();
        super.onDestroyView();
    }

    private void configureWindow() {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int width = Math.min(Math.round(ResUtil.getScreenWidth(requireContext()) * (land ? 0.5f : 0.84f)), ResUtil.dp2px(560));
        params.width = Math.max(width, ResUtil.dp2px(320));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
