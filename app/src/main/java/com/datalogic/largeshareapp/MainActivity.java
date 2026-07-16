package com.datalogic.largeshareapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.SeedManager;
import com.datalogic.largeshareapp.manager.SeekManager;
import com.datalogic.largeshareapp.manager.ShareManager;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.ui.PeerFilterAdapter;
import com.datalogic.largeshareapp.ui.PeerProgressAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 456;

    private final AtomicBoolean mIsSharing = new AtomicBoolean(false);
    private ShareManager.DiscoveryMode mDiscoveryMode = ShareManager.DiscoveryMode.NSD;
    private ShareManager.DiscoveryRole mDiscoveryRole = ShareManager.DiscoveryRole.SEEKER;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // UI Widgets
    private RadioGroup rgDiscovery;
    private RadioGroup rgRole;
    private Button btnAction;
    // Seeker's own download progress (visible only in Seeker role)
    private CardView cvSeekerProgress;
    private ProgressBar pbSeeker;
    private TextView tvSeekerPercent;
    private TextView tvSeekerProgress;

    // Peer progress tracker
    private RecyclerView rvPeers;
    private TextView tvPeersSummary;
    private TextView tvPeersEmpty;
    private TextView tvCompletionInfo;
    private final PeerProgressAdapter peersAdapter = new PeerProgressAdapter();

    // Peer search / filter
    private CardView cvPeers;
    private Button btnPeerFilter;
    private TextView tvPeerFilterStatus;
    private TextView tvPeerFilterClear;
    private ArrayAdapter<String> peerSearchAdapter;
    private final Set<String> mPeerFilter = new HashSet<>();
    private Map<String, ShareManager.PeerProgress> mLastProgressMap = Collections.emptyMap();
    private final Map<String, ShareManager.PeerProgress> mPeerProgressMap = new HashMap<>();
    private View llFilePickerContainer;
    private Button btnSelectFile;
    private TextView tvSelectedFileInfo;
    private TextView tvMetadataDetails;
    // Collapsible file-details card
    private View llFileCardHeader;
    private TextView tvFileCardToggle;
    private View nsvMetadataDetails;
    private boolean mFileDetailsExpanded = false;

    // Seeder view: progress reported by other (leeching) peers.
    private final SeedManager.PeersTrackingListener mPeersTrackingListener = new SeedManager.PeersTrackingListener() {
        @Override
        public void onProgressUpdated(String deviceId, int completed, int total) {
            renderPeerTracker(deviceId, completed, total);
        }
    };

    // Seeker view: this device's own leeching progress.
    private final SeekManager.SeekEventListener mSeekEventListener = new SeekManager.SeekEventListener() {
        @Override
        public void onChunkBatchLeeched(int completed, int total) {
            renderSeekProgress(completed, total);
        }

        @Override
        public void onLeechingCompleted() {
            runOnUiThread(() -> {
                tvCompletionInfo.setText("Download completed. Staying in the share session for a while to help other peers.");
                tvCompletionInfo.setVisibility(View.VISIBLE);
            });
            mHandler.postDelayed(() -> {
                tvCompletionInfo.setText("Left the share session. Get out network");
                tvCompletionInfo.setVisibility(View.VISIBLE);
                btnAction.setText("Start sharing");
            }, ShareManager.STOP_SEED_DELAY_MS);
        }
    };

    private final ActivityResultLauncher<String> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                parseFileSelection(uri);
            }
        });

    private ShareManager shareManager;
    private File mFileShared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Map widgets
        rgDiscovery = findViewById(R.id.rg_discovery);
        rgRole = findViewById(R.id.rg_role);

        btnAction = findViewById(R.id.btn_distribute);

        // Seeker's own download progress card.
        cvSeekerProgress = findViewById(R.id.cv_seeker_progress);
        pbSeeker = findViewById(R.id.pb_seeker);
        tvSeekerPercent = findViewById(R.id.tv_seeker_percent);
        tvSeekerProgress = findViewById(R.id.tv_seeker_progress);

        // Peer progress tracker: a scannable list rather than a wall of text.
        tvPeersSummary = findViewById(R.id.tv_peers_summary);
        tvPeersEmpty = findViewById(R.id.tv_peers_empty);
        tvCompletionInfo = findViewById(R.id.tv_completion_info);
        rvPeers = findViewById(R.id.rv_peers);
        rvPeers.setLayoutManager(new LinearLayoutManager(this));
        rvPeers.setAdapter(peersAdapter);
        cvPeers = findViewById(R.id.cv_peers);
        btnPeerFilter = findViewById(R.id.btn_peer_filter);
        tvPeerFilterStatus = findViewById(R.id.tv_peer_filter_status);
        tvPeerFilterClear = findViewById(R.id.tv_peer_filter_clear);

        peerSearchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line);

        btnPeerFilter.setOnClickListener(v -> openPeerFilterDialog(null));
        tvPeerFilterClear.setOnClickListener(v -> clearPeerFilter());

        // Map File Picker widgets
        btnSelectFile = findViewById(R.id.btn_select_file);
        tvSelectedFileInfo = findViewById(R.id.tv_selected_file_info);
        tvMetadataDetails = findViewById(R.id.tv_metadata_details);
        llFilePickerContainer = findViewById(R.id.ll_file_picker_container);

        // Collapsible file-details card: tap the header to expand/collapse the metadata.
        llFileCardHeader = findViewById(R.id.ll_file_card_header);
        tvFileCardToggle = findViewById(R.id.tv_file_card_toggle);
        nsvMetadataDetails = findViewById(R.id.nsv_metadata_details);
        llFileCardHeader.setOnClickListener(v -> setFileDetailsExpanded(!mFileDetailsExpanded));
        setFileDetailsExpanded(false);

        initialize();
        checkAndRequestPermissions();
    }

    private void initialize() {
        rgDiscovery.setOnCheckedChangeListener((group, checkedId)  -> {
            if (checkedId == R.id.rb_nsd) {
                mDiscoveryMode = ShareManager.DiscoveryMode.NSD;
            } else if (checkedId == R.id.rb_wifidirect) {
                mDiscoveryMode = ShareManager.DiscoveryMode.WIFI_DIRECT;
            }
        });

        rgRole.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_root) {
                btnAction.setText("Share data");
                llFilePickerContainer.setVisibility(View.VISIBLE);
                mDiscoveryRole = ShareManager.DiscoveryRole.SEEDER;
                cvPeers.setVisibility(View.VISIBLE);
                cvSeekerProgress.setVisibility(View.GONE);
            } else {
                btnAction.setText("Seek Data");
                llFilePickerContainer.setVisibility(View.GONE);
                mDiscoveryRole = ShareManager.DiscoveryRole.SEEKER;
                cvPeers.setVisibility(View.GONE);
                cvSeekerProgress.setVisibility(View.VISIBLE);
            }
        });

        btnSelectFile.setOnClickListener(v -> {
            filePickerLauncher.launch("*/*");
        });

        btnAction.setOnClickListener(v -> {
            mIsSharing.set(!mIsSharing.get());
            String name = mIsSharing.get() ? "Stop sharing"
                            : (mDiscoveryRole == ShareManager.DiscoveryRole.SEEDER ? "Share data"
                                : "Seek Data");

            btnAction.setText(name);
            toggleFreezeUI(!mIsSharing.get());
            toggleSharing(mIsSharing.get());
        });

        rgDiscovery.check(R.id.rb_nsd);
        rgRole.check(R.id.rb_seeker);
    }

    private void toggleFreezeUI(boolean enabled) {
        for (int i = 0; i < rgDiscovery.getChildCount(); i++) {
            rgDiscovery.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < rgRole.getChildCount(); i++) {
            rgRole.getChildAt(i).setEnabled(enabled);
        }
        btnSelectFile.setEnabled(enabled);
    }

    private void parseFileSelection(Uri uri) {
        mFileShared = new File("/sdcard/file-shared.zip");
        toggleFreezeUI(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            InformationMetadata informationMetadata = SecureManager.createFileMetadata(
                    mFileShared,
                    SecureManager.CHUNK_SIZE_524KB);

            runOnUiThread(() -> {
                tvSelectedFileInfo.setText( uri.getPath());
                if (informationMetadata != null) {
                    showMetadataInfo(informationMetadata);
                }
                toggleFreezeUI(true);
            });
        });
    }

    private void toggleSharing(boolean isSharing) {
        if (!isSharing) {
            if (shareManager != null) {
                shareManager.stopSharing();
                shareManager = null;
                btnAction.setText(rgRole.getCheckedRadioButtonId() == R.id.rb_root ? "Share data" : "Seek Data");
            }
            Log.d(TAG, "Stopped sharing");
            return;
        }

        if (mDiscoveryMode == ShareManager.DiscoveryMode.UNKNOWN
                || mDiscoveryRole == ShareManager.DiscoveryRole.UNKNOWN) {
            Log.w(TAG, "Cannot start sharing: Discovery mode or role is not set.");
            return;
        }

        Log.d(TAG, "Starting sharing - Mode: " + mDiscoveryMode);
        shareManager = new ShareManager(MainActivity.this, mFileShared, mDiscoveryMode);
        shareManager.startSharing(mDiscoveryRole);
        if (mDiscoveryRole == ShareManager.DiscoveryRole.SEEDER) {
            shareManager.trackSharing();
            shareManager.addPeersTrackingListener(mPeersTrackingListener);
        }
        if (mDiscoveryRole == ShareManager.DiscoveryRole.SEEKER) {
            shareManager.addSeekEventListener(mSeekEventListener);
        }
    }

    private void renderSeekProgress(int completed, int total) {
        runOnUiThread(() -> {
            int percent = total > 0 ? (int) Math.round((completed * 100.0) / total) : 0;
            pbSeeker.setMax(100);
            pbSeeker.setProgress(percent);
            tvSeekerPercent.setText(String.format(Locale.getDefault(), "%d%%", percent));
            tvSeekerProgress.setText(String.format(Locale.getDefault(),
                    "Chunks: %d / %d downloaded", completed, total));
        });
    }

    private void renderPeerTracker(String deviceId, int completed, int total) {
        runOnUiThread(() -> {
            mPeerProgressMap.put(deviceId,
                    new ShareManager.PeerProgress("deviceId", completed, total, deviceId));
            renderPeerProgress(mPeerProgressMap);
        });
    }

    private void renderPeerProgress(Map<String, ShareManager.PeerProgress> progressMap) {
        mLastProgressMap = progressMap;
        refreshPeerSuggestions(progressMap);
        updateFilterStatus();

        Map<String, ShareManager.PeerProgress> display = applyPeerFilter(progressMap);

        if (display.isEmpty()) {
            rvPeers.setVisibility(View.GONE);
            tvPeersEmpty.setVisibility(View.VISIBLE);
            tvPeersEmpty.setText(progressMap.isEmpty()
                    ? "No peers connected yet."
                    : "No peers match the current filter.");
            tvPeersSummary.setText(mPeerFilter.isEmpty()
                    ? "0 peers"
                    : String.format(Locale.getDefault(), "0 of %d peers", progressMap.size()));
            peersAdapter.submit(display);
            return;
        }

        tvPeersEmpty.setVisibility(View.GONE);
        rvPeers.setVisibility(View.VISIBLE);

        int shown = display.size();
        int completedPeers = 0;
        double pctSum = 0.0;
        for (ShareManager.PeerProgress p : display.values()) {
            double pct = p.total > 0 ? ((double) p.completed / p.total) * 100.0 : 0.0;
            pctSum += pct;
            if (p.total > 0 && p.completed >= p.total) {
                completedPeers++;
            }
        }
        double avgPct = shown > 0 ? pctSum / shown : 0.0;
        if (mPeerFilter.isEmpty()) {
            tvPeersSummary.setText(String.format(Locale.getDefault(),
                    "%d peers • %d done • avg %.0f%%", shown, completedPeers, avgPct));
        } else {
            tvPeersSummary.setText(String.format(Locale.getDefault(),
                    "%d of %d peers • avg %.0f%%", shown, progressMap.size(), avgPct));
        }

        peersAdapter.submit(display);
    }

    /** Restricts the map to the ticked peer ids; an empty filter means "show all". */
    private Map<String, ShareManager.PeerProgress> applyPeerFilter(
            Map<String, ShareManager.PeerProgress> src) {
        if (mPeerFilter.isEmpty()) {
            return src;
        }
        Map<String, ShareManager.PeerProgress> out = new HashMap<>();
        for (Map.Entry<String, ShareManager.PeerProgress> entry : src.entrySet()) {
            if (mPeerFilter.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** Keeps the search bar's autocomplete suggestions in sync with known peers. */
    private void refreshPeerSuggestions(Map<String, ShareManager.PeerProgress> progressMap) {
        peerSearchAdapter.clear();
        peerSearchAdapter.addAll(progressMap.keySet());
        peerSearchAdapter.notifyDataSetChanged();
    }

    private void updateFilterStatus() {
        if (mPeerFilter.isEmpty()) {
            tvPeerFilterStatus.setText("Showing all peers");
            tvPeerFilterClear.setVisibility(View.GONE);
        } else {
            tvPeerFilterStatus.setText(String.format(Locale.getDefault(),
                    "Filter active: %d peer(s) selected", mPeerFilter.size()));
            tvPeerFilterClear.setVisibility(View.VISIBLE);
        }
    }

    private void clearPeerFilter() {
        mPeerFilter.clear();
        renderPeerProgress(mLastProgressMap);
    }

    private void openPeerFilterDialog(String initialQuery) {
        final Map<String, ShareManager.PeerProgress> peers = mLastProgressMap;
        if (peers.isEmpty()) {
            Toast.makeText(this, "No peers to filter yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<PeerFilterAdapter.Item> items = new ArrayList<>();
        for (Map.Entry<String, ShareManager.PeerProgress> entry : peers.entrySet()) {
            ShareManager.PeerProgress p = entry.getValue();
            int percent = p.total > 0 ? (int) Math.round((p.completed * 100.0) / p.total) : 0;
            items.add(new PeerFilterAdapter.Item(
                    entry.getKey(), "Peer " + PeerProgressAdapter.shortId(entry.getKey()), percent));
        }

        // Pre-tick the active filter, or everything when no filter is set yet.
        Set<String> initiallyChecked = mPeerFilter.isEmpty()
                ? new HashSet<>(peers.keySet())
                : new HashSet<>(mPeerFilter);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_peer_filter, null);
        AutoCompleteTextView search = dialogView.findViewById(R.id.actv_dialog_search);
        RecyclerView rv = dialogView.findViewById(R.id.rv_dialog_peers);
        TextView empty = dialogView.findViewById(R.id.tv_dialog_empty);

        PeerFilterAdapter adapter = new PeerFilterAdapter(items, initiallyChecked);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        // Autocomplete suggestions + live filtering of the tick list as the user types.
        search.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>(peers.keySet())));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                adapter.filter(s.toString());
                empty.setVisibility(adapter.isEmptyVisible() ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        if (initialQuery != null && !initialQuery.isEmpty()) {
            search.setText(initialQuery);
            adapter.filter(initialQuery);
            empty.setVisibility(adapter.isEmptyVisible() ? View.VISIBLE : View.GONE);
        }

        new AlertDialog.Builder(this)
                .setTitle("Select peers to display")
                .setView(dialogView)
                .setPositiveButton("Confirm", (d, which) ->
                        applyTickedSelection(adapter.getSelectedIds(), peers.size()))
                .setNeutralButton("Show all", (d, which) -> clearPeerFilter())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyTickedSelection(Set<String> selected, int totalKnown) {
        mPeerFilter.clear();
        // A full (or empty) selection means "no filter" — show everything.
        if (!selected.isEmpty() && selected.size() < totalKnown) {
            mPeerFilter.addAll(selected);
        }
        renderPeerProgress(mLastProgressMap);
    }

    private void showMetadataInfo(InformationMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PRE-SPLIT CHUNK DETAILS ===\n");
        sb.append(String.format(Locale.getDefault(), "[File]: \n%s\n", meta.getFileShared().getPath()));
        sb.append(String.format(Locale.getDefault(), "[Hash](SHA-256): \n%s\n", meta.getFileHash()));
        sb.append(String.format(Locale.getDefault(), "[Size]: %.2f MB\n", (double) meta.getFileSize() / (1024 * 1024)));
        sb.append(String.format(Locale.getDefault(), "[Chunks]: %d chunks\n", meta.getChunkHashes().length));
        sb.append(String.format(Locale.getDefault(), "[Chunk]: %d bytes\n", meta.getChunkSize()));
        sb.append("\n---------------------------------\n");
        String[] hashes = meta.getChunkHashes();
        sb.append("Expected SHA-256 Hashes Table:\n");
        for (int i = 0; i < hashes.length; i++) {
            sb.append(String.format(Locale.getDefault(), "Chunk %d: \n%s\n", i, hashes[i]));
        }
        tvMetadataDetails.setText(sb.toString());
        // Reveal the freshly compiled metadata.
        setFileDetailsExpanded(true);
    }

    /** Expands or collapses the metadata body of the selected-file card. */
    private void setFileDetailsExpanded(boolean expanded) {
        mFileDetailsExpanded = expanded;
        nsvMetadataDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
        tvFileCardToggle.setText(expanded ? "▾" : "▸");
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }

        if (!Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.d(TAG, "Warning: Permission denied: " + permissions[i] + ". Discovery might not work thoroughly.");
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shareManager != null) {
            shareManager.stopSharing();
            shareManager = null;
        }
    }
}