package com.datalogic.largeshareapp.manager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.datalogic.largeshareapp.discovery.NsdDiscoveryManager;
import com.datalogic.largeshareapp.discovery.WifiDirectDiscoveryManager;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.storage.StorageManager;

import java.io.File;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ShareManager {
    private static final String TAG = "ShareManager";
    private static final int HTTP_PORT = 8888;
    private static final int MAX_PEER_CONNECTIONS = 3;

    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 3;
    private final Map<String, Peer> mInFlightPeersSeeding = new ConcurrentHashMap<>();
    private final Map<String, Peer> mInFlightPeersLeeching = new ConcurrentHashMap<>();

    private final DiscoveryMode mDiscoveryMode;
    private File mFileShared;
    private StorageManager mStorageManager = null;
    private final Set<ShareListener> mShareListeners = new CopyOnWriteArraySet<>();
    private final Context mContext;

    // Core components
    private HttpServer httpServer;
    private NsdDiscoveryManager nsdManager;
    private WifiDirectDiscoveryManager wifiDirectManager;

    // Discovery options
    private DiscoveryMode discoveryMode;
    private boolean isSharingStarted = false;
    private boolean isDownloadingFinished = false;

    // Concurrency / Thread pools
    private ExecutorService mHttpExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    private ExecutorService mSeedingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);

    private final Set<String> activeDistributorAddresses = ConcurrentHashMap.newKeySet();
    private final Map<String, PeerProgress> peerProgressMap = new ConcurrentHashMap<>();

    private final AtomicInteger receivedChunksCounterSinceReport = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Map<String, Peer> peerMap = new ConcurrentHashMap<>();

    private volatile Peer mRootPeer;
    // Ensures a leecher only promotes itself to a seeder once.
    private final AtomicBoolean mIsSeedingStarted = new AtomicBoolean(false);

    private SeekManager mSeekManager;
    private SeedManager mSeedManager;

    public static class PeerProgress {
        public String ip;
        public int completed;
        public int total;
        public int failures;

        public PeerProgress(String ip, int completed, int total, int failures) {
            this.ip = ip;
            this.completed = completed;
            this.total = total;
            this.failures = failures;
        }
    }

    public enum DiscoveryMode {
        UNKNOWN,
        NSD,
        WIFI_DIRECT;
    }

    public enum DiscoveryRole {
        UNKNOWN,
        SEEDER,
        SEEKER;
    }

    public interface ShareListener {
        void onStatusUpdated(String status);

        void onProgressUpdated(int completed, int total, int failures);

        void onPeerProgressUpdated(Map<String, PeerProgress> progressMap);

        void onDownloadCompleted(boolean success);

        void onServiceStopped();
    }

    private final NsdDiscoveryManager.DiscoveryListener mDiscoveryListener = new NsdDiscoveryManager.DiscoveryListener() {
        @Override
        public void onPeerFound(String ip, int port, String serviceName, String serviceType) {
            Log.d(TAG, "NSD Peer Found: " + ip + ":" + port + " (" + serviceName + ") [" + serviceType + "]");

            // Store the root peer for progress reporting
            if (NsdDiscoveryManager.SERVICE_TYPE_PROGRESS.equals(serviceType)) {
                mRootPeer = new Peer(ip, port, serviceName, serviceType);
                Log.d(TAG, "Discovered Root progress host: " + mRootPeer);
                return;
            }

            // Otherwise this is a share peer we can leech chunks from.
            Peer peer = new Peer(ip, port, serviceName, serviceType);
            if (!mSeekManager.startLeeching(peer)) {
                peer.connect();
            }
            peerMap.put(serviceName, peer);
        }

        @Override
        public void onPeerLost(String serviceName, String serviceType) {
            Log.d(TAG, "NSD Peer Lost: " + serviceName + " [" + serviceType + "]");
            peerMap.remove(serviceName);
            // TODO: Find other peer to replace
        }
    };

    private final SeekManager.LeechEventListener mLeechEventListener = new SeekManager.LeechEventListener() {
        @Override
        public void onMetadataReady(InformationMetadata metadata) {
            startSharing(DiscoveryRole.SEEDER);
        }

        @Override
        public void onChunkBatchLeeched(int completedChunks, int totalChunks) {
            reportProgressToRoot(completedChunks, totalChunks);
        }

        @Override
        public void onLeechingCompleted() {
            // onDownloadFinished();
        }
    };

    public ShareManager(Context context, File file, DiscoveryMode mode) {
        this.mContext = context;
        this.mFileShared = file;
        this.mDiscoveryMode = mode;
    }

    public void addShareListener(ShareListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to add null ShareListener. Ignored.");
            return;
        }
        this.mShareListeners.add(listener);
    }

    public void trackSharing() {
        // start mechanism to listen for progress updates from other peers
        switch (mDiscoveryMode) {
            case NSD: {
                nsdManager.registerService(HTTP_PORT, NsdDiscoveryManager.SERVICE_TYPE_PROGRESS,
                        NsdDiscoveryManager.SERVICE_NAME_PREFIX_PROGRESS, getUniqueId());
                break;
            }
            case WIFI_DIRECT: {
                break;
            }
        }
    }

    public synchronized void startSharing(DiscoveryRole role) {
        Log.d(TAG, "Starting sharing as " + role + " with discovery mode: " + mDiscoveryMode);
        switch (role) {
            case SEEDER: {
                if (!prepareSeedManager()) {
                    Log.e(TAG, "Failed to prepare SeedManager. Aborting seeder start.");
                    return;
                }
                if (!prepareConnectionAsSeeder(mDiscoveryMode)) {
                    Log.e(TAG, "Failed to prepare connection as SEEDER. Aborting seeder start.");
                    return;
                }
                Log.d(TAG, "Started as SEEDER successfully");
                break;
            }
            case SEEKER: {
                if (!prepareSeekerManager()) {
                    Log.e(TAG, "Failed to prepare SeekManager. Aborting seeker start.");
                    return;
                }
                if (!prepareConnectionAsSeeker(mDiscoveryMode)) {
                    Log.e(TAG, "Failed to prepare connection as SEEKER. Aborting seeker start.");
                    return;
                }
                Log.d(TAG, "Started as SEEKER successfully");
                break;
            }
            default:
                Log.w(TAG, "Unknown discovery mode for sharing: " + mDiscoveryMode);
        }
    }

    private boolean prepareSeedManager() {
        if (mFileShared != null) {
            InformationMetadata informationMetadata = SecureManager.createFileMetadata(mFileShared,
                    SecureManager.CHUNK_SIZE_524KB);
            if (informationMetadata == null) {
                Log.e(TAG, "FileMetadata is null. Cannot prepare as seeder.");
                return false;
            }
            SharedData.getInstance().instanceInformationMetadata = informationMetadata;
        }

        if (SharedData.getInstance().instanceInformationMetadata != null) {
            mFileShared = SharedData.getInstance().instanceInformationMetadata.getFileShared();
        }

        InformationMetadata informationMetadata = SharedData.getInstance().instanceInformationMetadata;
        BitSetMetadata bitSetMetadata = new BitSetMetadata(informationMetadata.getChunkHashes().length);
        bitSetMetadata.setAllAvailable();
        SharedData.getInstance().instanceBitSetMetadata = bitSetMetadata;

        StorageManager storageManager;
        try {
            storageManager = new StorageManager(informationMetadata);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to prepare SeedManager: " + e.getMessage());
            return false;
        }

        mSeedManager = new SeedManager(storageManager);
        return mSeedManager.initialize();
    }

    private boolean prepareSeekerManager() {
        mSeekManager = new SeekManager();
        mSeekManager.setLeechEventListener(mLeechEventListener);
        return true;
    }

    private void reportProgressToRoot(int completed, int total) {
        // int percent = total > 0 ? (int) ((completed * 100L) / total) : 0;

        if (mRootPeer == null) {
            return;
        }

        mHttpExecutor.submit(() -> {
            try {
                HttpClient.reportProgress(mRootPeer.ip, mRootPeer.port, getUniqueId(), completed, total, 0);
            } catch (Exception e) {
                Log.e(TAG, "Failed to report progress to Root: " + e.getMessage());
            }
        });
    }

    private boolean prepareConnectionAsSeeder(DiscoveryMode mode) {
        switch (mode) {
            case NSD: {
                if (nsdManager != null) {
                    nsdManager.cleanUp();
                    nsdManager = null;
                }
                nsdManager = new NsdDiscoveryManager(mContext);
                nsdManager.registerService(HTTP_PORT,
                                            NsdDiscoveryManager.SERVICE_TYPE_SHARE,
                                            NsdDiscoveryManager.SERVICE_NAME_PREFIX_SHARE,
                                            getUniqueId());
                break;
            }
            case WIFI_DIRECT: {
                if (wifiDirectManager == null) {
                    wifiDirectManager = new WifiDirectDiscoveryManager(mContext, null);
                }
                wifiDirectManager.createGroup();
                break;
            }
            default: {
                Log.w(TAG, "Unknown discovery mode for SEEDER: " + mode);
                return false;
            }
        }
        return true;
    }

    private boolean prepareConnectionAsSeeker(DiscoveryMode mode) {
        switch (mode) {
            case NSD: {
                if (nsdManager != null) {
                    nsdManager.cleanUp();
                    nsdManager = null;
                }
                nsdManager = new NsdDiscoveryManager(mContext);
                nsdManager.addDiscoveryListener(mDiscoveryListener);
                nsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_SHARE);
                nsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_PROGRESS);
                break;
            }
            case WIFI_DIRECT: {
                if (wifiDirectManager == null) {
                    wifiDirectManager = new WifiDirectDiscoveryManager(mContext, null);
                }
                wifiDirectManager.startPeerDiscovery();
                break;
            }
            default: {
                Log.w(TAG, "Unknown discovery mode for SEEKER: " + mode);
                return false;
            }
        }
        return true;
    }

    @SuppressLint("HardwareIds")
    private String getUniqueId() {
        return Settings.Secure.getString(
                mContext.getContentResolver(),
                Settings.Secure.ANDROID_ID);
    }

    public synchronized void stopSharing() {
        discoveryMode = DiscoveryMode.NSD;
        isSharingStarted = false;
        isDownloadingFinished = true;

        if (mSeekManager != null) {
            mSeekManager.stop();
        }
        if (mSeedManager != null) {
            mSeedManager.stop();
            mSeedManager = null;
        }

        if (mHttpExecutor != null) {
            mHttpExecutor.shutdownNow();
            mHttpExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
        }
        if (mSeedingExecutor != null) {
            mSeedingExecutor.shutdownNow();
            mSeedingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
        }

        if (nsdManager != null) {
            nsdManager.cleanUp();
            nsdManager = null;
        }

        if (wifiDirectManager != null) {
            wifiDirectManager.cleanUp();
            wifiDirectManager = null;
        }

        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }

        if (mStorageManager != null) {
            mStorageManager.close();
        }
        // updateStatus("All background modules disabled.");
    }
}
