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

    private final DiscoveryMode mDiscoveryMode;
    private final String mDeviceId;
    private File mFileShared;
    private final Context mContext;
    // Core components
    private HttpServer httpServer;
    private NsdDiscoveryManager nsdManager;
    private WifiDirectDiscoveryManager wifiDirectManager;
    // Concurrency / Thread pools
    private ExecutorService mHttpExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    private ExecutorService mSeedingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    private final Map<String, Peer> peerMap = new ConcurrentHashMap<>();
    private volatile Peer mRootPeer;
    private SeekManager mSeekManager;
    private SeedManager mSeedManager;

    // After leeching finishes we keep seeding for a short grace period so other peers
    // can still pull chunks from us before we leave the network.
    public static final long STOP_SEED_DELAY_MS = 15_000L;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStopSeedRunnable = this::stopSeed;

    public static class PeerProgress {
        public String ip;
        public int completed;
        public int total;
        public String deviceId;

        public PeerProgress(String ip, int completed, int total, String deviceId) {
            this.ip = ip;
            this.completed = completed;
            this.total = total;
            this.deviceId = deviceId;
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
            Peer lostPeer = peerMap.getOrDefault(serviceName, mRootPeer);
            if (lostPeer != null && lostPeer.isConnected()) {
                lostPeer.disconnect();
                replaceLeechingPeer(lostPeer);
            }
            peerMap.remove(serviceName);
        }
    };

    private final SeekManager.SeekEventListener mSeekEventListener = new SeekManager.SeekEventListener() {
        @Override
        public void onMetadataReady(InformationMetadata metadata) {
            startSharing(DiscoveryRole.SEEDER);
        }

        @Override
        public void onChunkBatchLeeched(int completedChunks, int totalChunks) {
            reportProgressToRoot(completedChunks, totalChunks);
        }

        @Override
        public void onLeechingPeerStopped(Peer peer) {
            replaceLeechingPeer(peer);
        }

        @Override
        public void onLeechingCompleted() {
            Log.d(TAG, "Leeching completed. Stopping seek now; seed stops in "
                    + (STOP_SEED_DELAY_MS / 1000) + "s.");
            stopSeek();
            // Stay online briefly so other peers can keep leeching chunks from us.
            mMainHandler.postDelayed(mStopSeedRunnable, STOP_SEED_DELAY_MS);
        }
    };

    private void replaceLeechingPeer(Peer stoppedPeer) {
        if (mSeekManager == null) {
            return;
        }

        // Prefer a different peer from the discovered set as the replacement.
        Peer replacementPeer = null;
        for (Peer candidate : peerMap.values()) {
            if (candidate.serviceName.equals(stoppedPeer.serviceName)
                || candidate.isConnected()) {
                continue;
            }
            replacementPeer = candidate;
            break;
        }
        
        if (replacementPeer != null) {
            mSeekManager.startLeeching(replacementPeer);
            Log.d(TAG, "Replaced stopped peer " + stoppedPeer + " with " + replacementPeer);
            return;
        }

        Log.w(TAG, "Could not start leeching from any peer to replace " + stoppedPeer);
    }

    public void addPeersTrackingListener(SeedManager.PeersTrackingListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to add null PeersTrackingListener. Ignored.");
            return;
        }
        mSeedManager.addPeersTrackingListener(listener);
    }

    public void removePeersTrackingListener(SeedManager.PeersTrackingListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to remove null PeersTrackingListener. Ignored.");
            return;
        }
        mSeedManager.removePeersTrackingListener(listener);
    }

    public void addSeekEventListener(SeekManager.SeekEventListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to add null SeekEventListener. Ignored.");
            return;
        }
        mSeekManager.addSeekListener(listener);
    }
    
    public void removeSeekEventListener(SeekManager.SeekEventListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to remove null SeekEventListener. Ignored.");
            return;
        }
        mSeekManager.removeSeekListener(listener);
    }

    public ShareManager(Context context, File file, DiscoveryMode mode) {
        this.mContext = context;
        this.mFileShared = file;
        this.mDiscoveryMode = mode;
        this.mDeviceId = getUniqueId();
    }

    public void trackSharing() {
        // start mechanism to listen for progress updates from other peers
        switch (mDiscoveryMode) {
            case NSD: {
                nsdManager.registerService(HTTP_PORT, NsdDiscoveryManager.SERVICE_TYPE_PROGRESS,
                        NsdDiscoveryManager.SERVICE_NAME_PREFIX_PROGRESS, mDeviceId);
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
        boolean ownsWholeFile = mFileShared != null;

        SharedData sharedData = SharedData.getInstance();
        if (ownsWholeFile) {
            InformationMetadata information = SecureManager.createFileMetadata(mFileShared,
                    SecureManager.CHUNK_SIZE_524KB);
            if (information == null) {
                Log.e(TAG, "FileMetadata is null. Cannot prepare as seeder.");
                return false;
            }
            sharedData.instanceInformationMetadata = information;
            sharedData.instanceBitSetMetadata = new BitSetMetadata(information.getChunkHashes().length);
            sharedData.instanceBitSetMetadata.setAllAvailable();
        } else {
            InformationMetadata information = sharedData.instanceInformationMetadata;
            mFileShared = information.getFileShared();
        }

        StorageManager storageManager;
        try {
            storageManager = new StorageManager(sharedData.instanceInformationMetadata);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to prepare SeedManager: " + e.getMessage());
            return false;
        }

        mSeedManager = new SeedManager(storageManager);
        return mSeedManager.initialize();
    }

    private boolean prepareSeekerManager() {
        mSeekManager = new SeekManager();
        mSeekManager.addSeekListener(mSeekEventListener);
        return true;
    }

    private void reportProgressToRoot(int completed, int total) {
        if (mRootPeer == null) {
            return;
        }
        mHttpExecutor.submit(() -> {
            try {
                HttpClient.reportProgress(mRootPeer.ip, mRootPeer.port, mDeviceId, completed, total);
            } catch (Exception e) {
                Log.e(TAG, "Failed to report progress to Root: " + e.getMessage());
            }
        });
    }

    private boolean prepareConnectionAsSeeder(DiscoveryMode mode) {
        switch (mode) {
            case NSD: {
                if (nsdManager == null) {
                    nsdManager = new NsdDiscoveryManager(mContext);
                }
                nsdManager.registerService(HTTP_PORT,
                                            NsdDiscoveryManager.SERVICE_TYPE_SHARE,
                                            NsdDiscoveryManager.SERVICE_NAME_PREFIX_SHARE,
                                            mDeviceId);
                Log.d(TAG, "NSD Service registered for SEEDER with device ID: " + mDeviceId);
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
                if (nsdManager == null) {
                    nsdManager = new NsdDiscoveryManager(mContext);
                }
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

    /** Stops everything immediately (leeching and seeding). */
    public synchronized void stopSharing() {
        mMainHandler.removeCallbacks(mStopSeedRunnable);
        stopSeek();
        stopSeed();
    }

    /** Stops leeching (downloading) from other peers. */
    public synchronized void stopSeek() {
        if (mSeekManager != null) {
            mSeekManager.stop();
        }
        
        if (mHttpExecutor != null) {
            mHttpExecutor.shutdownNow();
            mHttpExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
        }
    }

    /** Stops seeding (serving chunks) and tears down discovery / networking. */
    public synchronized void stopSeed() {
        if (mSeedManager != null) {
            mSeedManager.stop();
            mSeedManager = null;
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
    }
}
