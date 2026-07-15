package com.datalogic.largeshareapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.ShareManager;
import com.datalogic.largeshareapp.model.InformationMetadata;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 456;

    private final AtomicBoolean mIsSharing = new AtomicBoolean(false);
    private ShareManager.DiscoveryMode mDiscoveryMode = ShareManager.DiscoveryMode.NSD;
    private ShareManager.DiscoveryRole mDiscoveryRole = ShareManager.DiscoveryRole.SEEKER;

    // UI Widgets
    private RadioGroup rgDiscovery;
    private RadioGroup rgRole;
    private RadioButton rbNsd;
    private RadioButton rbWifiDirect;
    private RadioButton rbRoot;
    private RadioButton rbSeeker;
    
    private Button btnAction;
    private Button btnRetry;
    
    private TextView tvNodeId;
    private TextView tvProgressText;
    private ProgressBar pbDownload;
    private TextView tvFailureText;
    private TextView tvPeersList;
    private TextView tvLogs;

    // Custom File Picker Widgets & Fields
    private View llFilePickerContainer;
    private Button btnSelectFile;
    private TextView tvSelectedFileInfo;
    private TextView tvMetadataDetails;

    private final ActivityResultLauncher<String> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                parseFileSelection(uri);
            }
        });


    private ShareManager shareManager;
    ShareManager.ShareListener mShareListener = new ShareManager.ShareListener() {
        @Override
        public void onStatusUpdated(String status) {
            Log.d(TAG, status);
        }
        @Override
        public void onProgressUpdated(int completed, int total, int failures) {
            pbDownload.setMax(total);
            pbDownload.setProgress(completed);

            double pct = total > 0 ? ((double) completed / total) * 100.0 : 0.0;
            tvProgressText.setText(String.format(Locale.getDefault(), "Chunks: %d / %d (%.2f%%)", completed, total, pct));

        }

        @Override
        public void onPeerProgressUpdated(Map<String, ShareManager.PeerProgress> progressMap) {
            StringBuilder sb = new StringBuilder();
            if (progressMap.isEmpty()) {
                sb.append("No active peer details reported yet.");
            } else {
                for (Map.Entry<String, ShareManager.PeerProgress> entry : progressMap.entrySet()) {
                    String activeNodeId = entry.getKey();
                    ShareManager.PeerProgress p = entry.getValue();
                    double pct = p.total > 0 ? ((double) p.completed / p.total) * 100.0 : 0.0;
                    sb.append(String.format(Locale.getDefault(), "• Node[%s] IP: %s -> Chunks: %d/%d (%.1f%%), Failures: %d\n",
                            activeNodeId, p.ip, p.completed, p.total, p.failures, p.failures));
                }
            }
            tvPeersList.setText(sb.toString());
        }

        @Override
        public void onDownloadCompleted(boolean success) {
            if (success) {
                btnRetry.setEnabled(false);
            } else {
                btnRetry.setEnabled(true);
            }
        }

        @Override
        public void onServiceStopped() {
            Log.d(TAG, "P2P Lifecycle Finished. Re-enabling controls.");
            btnAction.setText(rgRole.getCheckedRadioButtonId() == R.id.rb_root ? "Share data" : "Seek Data");
            btnRetry.setEnabled(false);
            toggleFreezeUI(true);
            shareManager = null;
        }
    };
    private File mFileShared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Map widgets
        rgDiscovery = findViewById(R.id.rg_discovery);
        rgRole = findViewById(R.id.rg_role);
        rbNsd = findViewById(R.id.rb_nsd);
        rbWifiDirect = findViewById(R.id.rb_wifidirect);
        rbRoot = findViewById(R.id.rb_root);
        rbSeeker = findViewById(R.id.rb_seeker);
        
        btnAction = findViewById(R.id.btn_distribute);
        btnRetry = findViewById(R.id.btn_retry);
        
        tvNodeId = findViewById(R.id.tv_node_id);
        tvProgressText = findViewById(R.id.tv_progress_text);
        pbDownload = findViewById(R.id.pb_download);
        tvFailureText = findViewById(R.id.tv_failure_text);
        tvPeersList = findViewById(R.id.tv_peers_list);
        tvLogs = findViewById(R.id.tv_logs);

        // Map File Picker widgets
        btnSelectFile = findViewById(R.id.btn_select_file);
        tvSelectedFileInfo = findViewById(R.id.tv_selected_file_info);
        tvMetadataDetails = findViewById(R.id.tv_metadata_details);
        llFilePickerContainer = findViewById(R.id.ll_file_picker_container);

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
            } else {
                btnAction.setText("Seek Data");
                llFilePickerContainer.setVisibility(View.GONE);
                mDiscoveryRole = ShareManager.DiscoveryRole.SEEKER;
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
        }
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