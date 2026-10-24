package com.datalogic.largeshareapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
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

import com.datalogic.largeshareapp.service.SessionConfig;
import com.datalogic.largeshareapp.service.SessionSnapshot;
import com.datalogic.largeshareapp.service.SessionSnapshot.PeerProgress;
import com.datalogic.largeshareapp.service.ShareApi;
import com.datalogic.largeshareapp.service.ShareService;
import com.datalogic.largeshareapp.ui.PeerFilterAdapter;
import com.datalogic.largeshareapp.ui.PeerProgressAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thin client of {@link ShareService}: it binds to the service, sends commands through
 * {@link ShareApi} and renders every {@link SessionSnapshot} it receives. Sessions keep running
 * in the service when this activity is closed or recreated.
 */
public class MainActivity extends AppCompatActivity implements ShareApi.Listener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 456;

    // UI Widgets
    private RadioGroup rgDiscovery;
    private RadioGroup rgRole;
    private Button btnAction;
    private TextView tvStatus;
    private EditText etFleetKey;
    private Button btnShowFleetKey;
    private boolean mFleetKeyVisible;
    private EditText etStaticPeers;
    // Where peers can reach this device
    private TextView tvDeviceIp;
    private TextView tvDeviceMeta;
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

    // Peer search / filter (pure UI state)
    private CardView cvPeers;
    private Button btnPeerFilter;
    private TextView tvPeerFilterStatus;
    private TextView tvPeerFilterClear;
    private final Set<String> mPeerFilter = new HashSet<>();
    private Map<String, PeerProgress> mLastProgressMap = Collections.emptyMap();
    private View llFilePickerContainer;
    private Button btnSelectFile;
    private TextView tvSelectedFileInfo;
    private TextView tvMetadataDetails;
    // Collapsible file-details card
    private View llFileCardHeader;
    private TextView tvFileCardToggle;
    private View nsvMetadataDetails;
    private boolean mFileDetailsExpanded = false;

    // Service connection
    private ShareApi mApi;
    private SessionSnapshot mSnapshot;
    private Intent mPendingLaunch;
    private String mRenderedFileHash;
    private String mLastMessage;
    private int mGroupCapacity;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mApi = ((ShareService.LocalBinder) binder).api();
            if (etFleetKey.getText().length() == 0) {
                etFleetKey.setText(mApi.getFleetKey());
            }
            mApi.addListener(MainActivity.this);
            applyPendingLaunch();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mApi = null;
        }
    };

    private final ActivityResultLauncher<String> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && mApi != null) {
                mApi.prepareFile(uri);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Map widgets
        rgDiscovery = findViewById(R.id.rg_discovery);
        rgRole = findViewById(R.id.rg_role);
        tvStatus = findViewById(R.id.tv_status);
        etFleetKey = findViewById(R.id.et_fleet_key);
        btnShowFleetKey = findViewById(R.id.btn_show_fleet_key);
        btnShowFleetKey.setOnClickListener(v -> setFleetKeyVisible(!mFleetKeyVisible));
        etStaticPeers = findViewById(R.id.et_static_peers);
        tvDeviceIp = findViewById(R.id.tv_device_ip);
        tvDeviceMeta = findViewById(R.id.tv_device_meta);

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
        mPendingLaunch = getIntent();
        // Bound for the whole life of the activity (not only while visible), so a session requested
        // by a launch intent starts even when the screen is off, e.g. on an unattended device.
        bindService(new Intent(this, ShareService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        // The key is the fleet's only credential: never leave it on screen in the background.
        setFleetKeyVisible(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // The session keeps running in the service; we only stop listening.
        if (mApi != null) {
            mApi.removeListener(this);
            mApi = null;
        }
        unbindService(mConnection);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mPendingLaunch = intent;
        applyPendingLaunch();
    }

    /**
     * Lets a session be configured and started from the command line (used by automated device
     * tests): adb shell am start -n .../.MainActivity --es role root|seeker --es mode nsd|wifidirect
     * --es file /sdcard/x.bin --es key secret --es peers 10.0.0.5:8888 --ei capacity 3
     * --ez autostart true
     * <p>
     * {@code --ez stop true} ends the running session cleanly (unlike a force-stop, it also tears
     * down this device's Wi-Fi Direct group, which would otherwise outlive the process).
     */
    private void applyPendingLaunch() {
        Intent intent = mPendingLaunch;
        if (mApi == null || intent == null) {
            return;
        }
        if (intent.getBooleanExtra("stop", false)) {
            mPendingLaunch = null;
            mApi.stop();
            return;
        }
        if (!intent.hasExtra("role")) {
            return;
        }
        mPendingLaunch = null;
        String key = intent.getStringExtra("key");
        if (key != null) {
            etFleetKey.setText(key);
        }
        String peers = intent.getStringExtra("peers");
        if (peers != null) {
            etStaticPeers.setText(peers);
        }
        mGroupCapacity = intent.getIntExtra("capacity", 0);
        boolean root = "root".equalsIgnoreCase(intent.getStringExtra("role"));
        rgRole.check(root ? R.id.rb_root : R.id.rb_seeker);
        rgDiscovery.check("wifidirect".equalsIgnoreCase(intent.getStringExtra("mode"))
                ? R.id.rb_wifidirect : R.id.rb_nsd);
        String path = intent.getStringExtra("file");
        if (intent.getBooleanExtra("autostart", false)) {
            if (mSnapshot == null || !mSnapshot.active) {
                mApi.start(buildConfig(root ? path : null));
            }
        } else if (root && path != null) {
            mApi.prepareFile(path);
        }
    }

    private void initialize() {
        rgRole.setOnCheckedChangeListener((group, checkedId) -> {
            applyRoleVisibility(selectedRole());
            if (mSnapshot == null || !mSnapshot.active) {
                btnAction.setText(selectedRole() == SessionSnapshot.Role.ROOT ? "Share data" : "Seek Data");
            }
        });
        btnSelectFile.setOnClickListener(v -> filePickerLauncher.launch("*/*"));
        btnAction.setOnClickListener(v -> {
            if (mApi == null) {
                return;
            }
            if (mSnapshot != null && mSnapshot.active) {
                mApi.stop();
            } else {
                tvCompletionInfo.setVisibility(View.GONE);
                mApi.start(buildConfig(null));
            }
        });

        rgDiscovery.check(R.id.rb_nsd);
        rgRole.check(R.id.rb_seeker);
    }

    private SessionSnapshot.Role selectedRole() {
        return rgRole.getCheckedRadioButtonId() == R.id.rb_root
                ? SessionSnapshot.Role.ROOT : SessionSnapshot.Role.SEEKER;
    }

    private SessionSnapshot.Mode selectedMode() {
        return rgDiscovery.getCheckedRadioButtonId() == R.id.rb_wifidirect
                ? SessionSnapshot.Mode.WIFI_DIRECT : SessionSnapshot.Mode.NSD;
    }

    private SessionConfig buildConfig(String filePath) {
        return new SessionConfig(selectedRole(), selectedMode(), etFleetKey.getText().toString(),
                SessionConfig.parsePeers(etStaticPeers.getText().toString()), filePath, mGroupCapacity);
    }

    private void applyRoleVisibility(SessionSnapshot.Role role) {
        boolean root = role == SessionSnapshot.Role.ROOT;
        llFilePickerContainer.setVisibility(root ? View.VISIBLE : View.GONE);
        cvPeers.setVisibility(root ? View.VISIBLE : View.GONE);
        cvSeekerProgress.setVisibility(root ? View.GONE : View.VISIBLE);
    }

    private void setControlsEnabled(boolean enabled) {
        for (int i = 0; i < rgDiscovery.getChildCount(); i++) {
            rgDiscovery.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < rgRole.getChildCount(); i++) {
            rgRole.getChildAt(i).setEnabled(enabled);
        }
        btnSelectFile.setEnabled(enabled);
        etFleetKey.setEnabled(enabled);
        etStaticPeers.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void onSnapshot(SessionSnapshot s) {
        mSnapshot = s;
        if (s.active) {
            // Reflect the running session (e.g. after reopening the app while it runs in background).
            rgRole.check(s.role == SessionSnapshot.Role.ROOT ? R.id.rb_root : R.id.rb_seeker);
            rgDiscovery.check(s.mode == SessionSnapshot.Mode.WIFI_DIRECT ? R.id.rb_wifidirect : R.id.rb_nsd);
        }
        applyRoleVisibility(s.active ? s.role : selectedRole());
        setControlsEnabled(!s.active && !s.preparing);
        btnAction.setEnabled(s.active || !s.preparing);
        btnAction.setText(s.active ? "Stop sharing"
                : selectedRole() == SessionSnapshot.Role.ROOT ? "Share data" : "Seek Data");
        tvStatus.setText(s.phase + " - " + s.detail);

        if (s.message != null && !s.message.equals(mLastMessage)) {
            Toast.makeText(this, s.message, Toast.LENGTH_SHORT).show();
        }
        mLastMessage = s.message;

        renderDevice(s);
        renderFile(s);
        renderSeekProgress(s);
        renderCompletion(s);
        renderPeerProgress(s.peers);
    }

    /** Shows the fleet key in clear text (e.g. to copy it to another device) or masks it again. */
    private void setFleetKeyVisible(boolean visible) {
        mFleetKeyVisible = visible;
        int cursor = etFleetKey.getSelectionEnd();
        etFleetKey.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        etFleetKey.setSelection(Math.max(0, Math.min(cursor, etFleetKey.length())));
        btnShowFleetKey.setText(visible ? "Hide" : "Show");
    }

    /** IP addresses (one per line, aligned) so the user can read them out or type them as static peers. */
    private void renderDevice(SessionSnapshot s) {
        if (s.addresses.isEmpty()) {
            tvDeviceIp.setText("IP: not connected to any network");
        } else {
            StringBuilder sb = new StringBuilder();
            for (SessionSnapshot.NetAddress a : s.addresses) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(String.format(Locale.US, "%-15s  %s", a.ip, a.label));
            }
            tvDeviceIp.setText(sb.toString());
        }
        tvDeviceMeta.setText(String.format(Locale.US, "Port %d  •  Device %s", s.servePort,
                PeerProgressAdapter.shortId(s.deviceId)));
    }

    private void renderFile(SessionSnapshot s) {
        if (s.preparing) {
            tvSelectedFileInfo.setText(String.format(Locale.US, "Hashing... %d%%", s.preparePercent));
        } else if (s.file != null) {
            tvSelectedFileInfo.setText(s.file.name + "  •  " + formatSize(s.file.size));
            if (!Objects.equals(mRenderedFileHash, s.file.fileHash)) {
                mRenderedFileHash = s.file.fileHash;
                showMetadataInfo(s.file);
            }
        } else {
            tvSelectedFileInfo.setText("Selected file: None");
        }
    }

    private void renderSeekProgress(SessionSnapshot s) {
        int percent = s.percent();
        pbSeeker.setMax(100);
        pbSeeker.setProgress(percent);
        tvSeekerPercent.setText(String.format(Locale.getDefault(), "%d%%", percent));
        if (s.totalChunks == 0) {
            tvSeekerProgress.setText("Waiting to leech chunks…");
        } else {
            tvSeekerProgress.setText(String.format(Locale.getDefault(),
                    "Chunks: %d / %d downloaded • %.1f MB/s • %d peer(s)", s.completedChunks,
                    s.totalChunks, s.bytesPerSec / (1024 * 1024), s.activePeers));
        }
    }

    private void renderCompletion(SessionSnapshot s) {
        switch (s.phase) {
            case COMPLETED:
                tvCompletionInfo.setText("Download completed and verified. Staying in the share session "
                        + "for a while to help other peers.");
                tvCompletionInfo.setVisibility(View.VISIBLE);
                break;
            case LEFT:
                tvCompletionInfo.setText("Left the share session.");
                tvCompletionInfo.setVisibility(View.VISIBLE);
                break;
            default:
                if (s.active) {
                    tvCompletionInfo.setVisibility(View.GONE);
                }
                break;
        }
    }

    private void renderPeerProgress(Map<String, PeerProgress> progressMap) {
        mLastProgressMap = progressMap;
        updateFilterStatus();

        Map<String, PeerProgress> display = applyPeerFilter(progressMap);

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
        for (PeerProgress p : display.values()) {
            pctSum += p.total > 0 ? ((double) p.completed / p.total) * 100.0 : 0.0;
            if (p.isDone()) {
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
    private Map<String, PeerProgress> applyPeerFilter(Map<String, PeerProgress> src) {
        if (mPeerFilter.isEmpty()) {
            return src;
        }
        Map<String, PeerProgress> out = new HashMap<>();
        for (Map.Entry<String, PeerProgress> entry : src.entrySet()) {
            if (mPeerFilter.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
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
        final Map<String, PeerProgress> peers = mLastProgressMap;
        if (peers.isEmpty()) {
            Toast.makeText(this, "No peers to filter yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<PeerFilterAdapter.Item> items = new ArrayList<>();
        for (Map.Entry<String, PeerProgress> entry : peers.entrySet()) {
            items.add(new PeerFilterAdapter.Item(entry.getKey(),
                    "Peer " + PeerProgressAdapter.shortId(entry.getKey()), entry.getValue().percent()));
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

    /** Host view of the payload: what will be shared, without the per-chunk hash table. */
    private void showMetadataInfo(SessionSnapshot.FileInfo file) {
        int slash = file.path.lastIndexOf('/');
        String folder = slash > 0 ? file.path.substring(0, slash) : file.path;
        String chunks = String.format(Locale.US, "%,d × %s", file.chunkCount, formatSize(file.chunkSize));
        if (file.chunkCount > 0 && file.lastChunkSize() != file.chunkSize) {
            chunks += " (last " + formatSize(file.lastChunkSize()) + ")";
        }
        String summary = String.format(Locale.US,
                "File     %s%nFolder   %s%nSize     %s (%,d bytes)%nChunks   %s%nSHA-256  %s",
                file.name, folder, formatSize(file.size), file.size, chunks, file.fileHash);
        tvMetadataDetails.setText(summary);
        // Reveal the summary of the freshly prepared file.
        setFileDetailsExpanded(true);
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.US, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.US, "%.0f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    /** Expands or collapses the metadata body of the selected-file card. */
    private void setFileDetailsExpanded(boolean expanded) {
        mFileDetailsExpanded = expanded;
        nsvMetadataDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
        tvFileCardToggle.setText(expanded ? "▾" : "▸");
    }

    // ------------------------------------------------------------------ permissions

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        // Without it the session still runs, but its progress notification is hidden.
        permissions.add(Manifest.permission.POST_NOTIFICATIONS);

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
}
