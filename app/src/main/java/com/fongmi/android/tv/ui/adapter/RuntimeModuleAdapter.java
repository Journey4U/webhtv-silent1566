package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterRuntimeModuleBinding;
import com.fongmi.android.tv.runtime.RuntimeModulePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders the runtime module list.
 *
 * <p>Text is resolved by the caller so this adapter stays free of module-id knowledge; the decision
 * of what to offer is {@link RuntimeModulePolicy}'s job.
 */
public class RuntimeModuleAdapter extends RecyclerView.Adapter<RuntimeModuleAdapter.ViewHolder> {

    /** One row: a catalog entry plus its resolved presentation. */
    public static final class Item {

        public final String id;
        public final CharSequence name;
        public final CharSequence info;
        public final CharSequence version;
        public final CharSequence size;
        public final RuntimeModulePolicy.State state;

        public Item(String id, CharSequence name, CharSequence info, CharSequence version,
                    CharSequence size, RuntimeModulePolicy.State state) {
            this.id = id;
            this.name = name;
            this.info = info;
            this.version = version;
            this.size = size;
            this.state = state;
        }

        /** Secondary line: version plus download size. */
        public CharSequence meta() {
            String left = version == null ? "" : version.toString();
            String right = size == null ? "" : size.toString();
            if (left.isEmpty()) return right;
            if (right.isEmpty()) return left;
            return left + " · " + right;
        }
    }

    public interface Listener {

        void onAction(Item item);

        void onRemove(Item item);
    }

    private final List<Item> items = new ArrayList<>();
    private final Listener listener;

    public RuntimeModuleAdapter(Listener listener) {
        this.listener = listener;
    }

    public void replaceAll(List<Item> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterRuntimeModuleBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        holder.binding.name.setText(item.name);
        holder.binding.info.setText(item.info);
        holder.binding.meta.setText(item.meta());
        holder.binding.status.setText(statusText(holder.itemView, item.state));

        boolean actionable = RuntimeModulePolicy.canInstall(item.state);
        holder.binding.action.setVisibility(actionable ? View.VISIBLE : View.GONE);
        if (actionable) {
            holder.binding.action.setText(item.state == RuntimeModulePolicy.State.UPGRADE
                    ? holder.itemView.getContext().getString(R.string.runtime_modules_upgrade, item.version)
                    : holder.itemView.getContext().getString(R.string.runtime_modules_install));
        }

        // Uninstall stays available for any installed module, including one whose catalog entry no
        // longer offers a matching artifact — otherwise the row would strand the user.
        holder.binding.remove.setVisibility(
                RuntimeModulePolicy.canUninstall(item.state) ? View.VISIBLE : View.GONE);

        // Listeners are bound per-bind (not in the constructor) so a recycled holder always acts on
        // the item currently displayed in it.
        holder.binding.action.setOnClickListener(view -> dispatchAction(holder));
        holder.binding.remove.setOnClickListener(view -> dispatchRemove(holder));
    }

    private void dispatchAction(ViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || listener == null) return;
        listener.onAction(items.get(position));
    }

    private void dispatchRemove(ViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || listener == null) return;
        listener.onRemove(items.get(position));
    }

    private static CharSequence statusText(View itemView, RuntimeModulePolicy.State state) {
        int res = switch (state) {
            case INSTALL, UPGRADE -> R.string.runtime_modules_not_installed;
            case INSTALLED, INSTALLED_NO_ARTIFACT -> R.string.runtime_modules_installed;
            case UNSUPPORTED_ABI -> R.string.runtime_modules_unsupported_abi;
            case UNSUPPORTED_CONTRACT -> R.string.runtime_modules_requires_newer_app;
        };
        return itemView.getContext().getString(res);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterRuntimeModuleBinding binding;

        public ViewHolder(@NonNull AdapterRuntimeModuleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
