package com.datalogic.largeshareapp.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.datalogic.largeshareapp.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tickable, searchable list of peers used by the "Select peers" dialog. The user
 * types in a search box to narrow the list (there can be many peers), ticks the
 * peers to display, and confirms; {@link #getSelectedIds()} returns the ticked set.
 */
public class PeerFilterAdapter extends RecyclerView.Adapter<PeerFilterAdapter.ViewHolder> {

    /** One selectable peer entry. */
    public static final class Item {
        public final String id;
        public final String label;
        public final int percent;

        public Item(String id, String label, int percent) {
            this.id = id;
            this.label = label;
            this.percent = percent;
        }
    }

    private final List<Item> mAll = new ArrayList<>();
    private final List<Item> mVisible = new ArrayList<>();
    private final Set<String> mChecked = new HashSet<>();

    public PeerFilterAdapter(List<Item> items, Set<String> initiallyChecked) {
        mAll.addAll(items);
        mVisible.addAll(items);
        if (initiallyChecked != null) {
            mChecked.addAll(initiallyChecked);
        }
    }

    /** Narrows the visible rows to those whose id/label contains {@code query}. */
    public void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        mVisible.clear();
        if (q.isEmpty()) {
            mVisible.addAll(mAll);
        } else {
            for (Item item : mAll) {
                if (item.id.toLowerCase(Locale.getDefault()).contains(q)
                        || item.label.toLowerCase(Locale.getDefault()).contains(q)) {
                    mVisible.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** @return a snapshot of the currently ticked peer ids. */
    public Set<String> getSelectedIds() {
        return new HashSet<>(mChecked);
    }

    public boolean isEmptyVisible() {
        return mVisible.isEmpty();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer_filter, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = mVisible.get(position);
        holder.label.setText(item.label);
        holder.percent.setText(String.format(Locale.getDefault(), "%d%%", item.percent));
        holder.checkBox.setChecked(mChecked.contains(item.id));

        holder.itemView.setOnClickListener(v -> {
            boolean nowChecked = !mChecked.contains(item.id);
            if (nowChecked) {
                mChecked.add(item.id);
            } else {
                mChecked.remove(item.id);
            }
            holder.checkBox.setChecked(nowChecked);
        });
    }

    @Override
    public int getItemCount() {
        return mVisible.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;
        final TextView label;
        final TextView percent;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.cb_peer_filter);
            label = itemView.findViewById(R.id.tv_peer_filter_label);
            percent = itemView.findViewById(R.id.tv_peer_filter_percent);
        }
    }
}
