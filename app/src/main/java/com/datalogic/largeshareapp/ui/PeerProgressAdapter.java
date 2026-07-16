package com.datalogic.largeshareapp.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.datalogic.largeshareapp.R;
import com.datalogic.largeshareapp.manager.ShareManager.PeerProgress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders one row per peer in the "Network Peers Progress Tracker" list: a short
 * peer label, a horizontal progress bar, a completed/total counter and a failure
 * badge. Designed so many peers can be scanned at a glance.
 */
public class PeerProgressAdapter extends RecyclerView.Adapter<PeerProgressAdapter.PeerViewHolder> {

    /** Immutable row model derived from a {@link PeerProgress} snapshot. */
    private static final class Row {
        final String deviceId;
        final int completed;
        final int total;
        final int percent;

        Row(String deviceId, PeerProgress p) {
            this.deviceId = deviceId;
            this.completed = p.completed;
            this.total = p.total;
            this.percent = p.total > 0 ? (int) Math.round((p.completed * 100.0) / p.total) : 0;
        }
    }

    private final List<Row> mRows = new ArrayList<>();

    /** Replaces the current rows with a fresh snapshot, sorted for stable ordering. */
    public void submit(Map<String, PeerProgress> progressMap) {
        mRows.clear();
        for (Map.Entry<String, PeerProgress> entry : progressMap.entrySet()) {
            mRows.add(new Row(entry.getKey(), entry.getValue()));
        }
        // Stable, predictable order so rows don't jump around as reports arrive.
        Collections.sort(mRows, (a, b) -> a.deviceId.compareTo(b.deviceId));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer_progress, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        holder.bind(mRows.get(position));
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    static final class PeerViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvLabel;
        private final TextView tvPercent;
        private final ProgressBar progressBar;
        private final TextView tvCounter;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLabel = itemView.findViewById(R.id.tv_peer_label);
            tvPercent = itemView.findViewById(R.id.tv_peer_percent);
            progressBar = itemView.findViewById(R.id.pb_peer);
            tvCounter = itemView.findViewById(R.id.tv_peer_counter);
        }

        void bind(Row row) {
            tvLabel.setText(String.format(Locale.getDefault(), "Peer %s", PeerProgressAdapter.shortId(row.deviceId)));
            tvPercent.setText(String.format(Locale.getDefault(), "%d%%", row.percent));
            progressBar.setMax(100);
            progressBar.setProgress(row.percent);
            tvCounter.setText(String.format(Locale.getDefault(), "%d / %d chunks", row.completed, row.total));

            // Green once complete, blue while in progress: a quick visual cue.
            int color = row.percent >= 100 ? Color.parseColor("#2E7D32") : Color.parseColor("#1976D2");
            tvPercent.setTextColor(color);
        }
    }

    /** Shortens a long ANDROID_ID to the last 6 chars for compact display. */
    public static String shortId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return "unknown";
        }
        return deviceId.length() <= 6 ? deviceId : deviceId.substring(deviceId.length() - 6);
    }
}
