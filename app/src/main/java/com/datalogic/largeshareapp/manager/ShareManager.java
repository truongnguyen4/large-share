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
import java.util.concurrent.atomic.AtomicInteger;

public class ShareManager {
    private static final String TAG = "ShareManager";
    private static final int HTTP_PORT = 8888;
    private static final int MAX_PEER_CONNECTIONS = 3;

    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 3;
    private final Map<String, Peer> mInFlightPeersSeeding = new ConcurrentHashMap<>();
    private final Map<String, Peer> mInFlightPeersLeeching = new ConcurrentHashMap<>();

    private final DiscoveryMode mDiscoveryMode;
    private final File mFileShared;
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
    private ExecutorService mLeechingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    private ExecutorService mSeedingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);

    private final Set<String> activeDistributorAddresses = ConcurrentHashMap.newKeySet();
    private final Map<String, PeerProgress> peerProgressMap = new ConcurrentHashMap<>();

    private final AtomicInteger receivedChunksCounterSinceReport = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Map<String, Peer> peerMap = new ConcurrentHashMap<>();

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
        InformationMetadata informationMetadata = SecureManager.createFileMetadata(mFileShared,
                SecureManager.CHUNK_SIZE_524KB,
                DiscoveryRole.SEEDER);
        if (informationMetadata == null) {
            Log.e(TAG, "FileMetadata is null. Cannot prepare as seeder.");
            return false;
        }
        SharedData.getInstance().instanceInformationMetadata = informationMetadata;

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
        return true;
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

    // public void startRootDistribution(long fileSize, int chunkSize) {
    // if (isSharingStarted) return;
    // isSharingStarted = true;
    // isDownloadingFinished = true; // Root is always finished downloading
    //
    // updateStatus("Initializing HTTP data server...");
    //
    // // Setup root server in thread pool to avoid freezing UI
    // Executors.newSingleThreadExecutor().execute(() -> {
    // try {
    //
    // // Start local server to distribute chunks
    // httpServer = new HttpServer(HTTP_PORT, storageManager, mMetadata,
    // this::handlePeerProgress);
    // httpServer.start();
    //
    // mainHandler.post(() -> {
    // updateStatus("Root initialized. Starting network publication...");
    // startServiceDiscoveryRegistration();
    // });
    // } catch (Exception e) {
    // Log.e(TAG, "Root initialization failed", e);
    // mainHandler.post(() -> updateStatus("Root setup failed: " + e.getMessage()));
    // stopSharing();
    // }
    // });
    // }

    // public void startRootDistributionWithUri(Uri fileUri, String fileName, long
    // fileSize, int chunkSize) {
    // if (isSharingStarted) return;
    // isSharingStarted = true;
    // isDownloadingFinished = true; // Root is always finished downloading
    //
    // updateStatus("Initializing HTTP data server...");
    //
    // // Setup root server in thread pool to avoid freezing UI
    // Executors.newSingleThreadExecutor().execute(() -> {
    // try {
    // if (mMetadata == null) {
    //// mMetadata = createRootFileMetadata(context, storageManager, fileUri,
    // fileName, fileSize, chunkSize);
    // }
    //
    // // Start local server to distribute chunks
    // httpServer = new HttpServer(HTTP_PORT, storageManager, mMetadata,
    // this::handlePeerProgress);
    // httpServer.start();
    //
    // mainHandler.post(() -> {
    // updateStatus("Root initialized for: " + mMetadata.getFileName() + ". Starting
    // network publication...");
    // startServiceDiscoveryRegistration();
    // });
    // } catch (Exception e) {
    // Log.e(TAG, "Root initialization with URI failed", e);
    // mainHandler.post(() -> updateStatus("Root setup failed: " + e.getMessage()));
    // stopSharing();
    // }
    // });
    // }
    //
    // /**
    // * Start Seeker peer. Start with a 1-5 second target random delay.
    // */
    // public void startSeeking() {
    // if (isSharingStarted) return;
    // isSharingStarted = false;
    // isDownloadingFinished = false;
    //
    // storageManager.clearFailedChunks();
    //
    // long delayMs = 1000 + random.nextInt(4000); // 1-5 second random delay to
    // avoid jam
    // updateStatus("Delaying seeker start for " + (delayMs / 1000.0) + "s...");
    //
    // mainHandler.postDelayed(() -> {
    // isSharingStarted = true;
    // clientExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    // updateStatus("Seeking distributors in network (Discovery Mode: " +
    // discoveryMode.name() + ")...");
    //
    // startNetworkDiscovery();
    // }, delayMs);
    // }
    //
    // /**
    // * Retry chunk failures - only retries chunks marked as failed, keeping
    // performance optimized.
    // */
    // public void retryFailuresOnly() {
    // if (!isSharingStarted || !isDownloadingFinished) {
    // updateStatus("Currently downloading, cannot retry failures yet.");
    // return;
    // }
    //
    // Set<Integer> failed = storageManager.getFailedChunks();
    // if (failed.isEmpty()) {
    // updateStatus("No failed chunks to retry!");
    // return;
    // }
    //
    // updateStatus("Retrying " + failed.size() + " failed chunks...");
    // isDownloadingFinished = false;
    //
    // // Reset executor for re-running downloader threads
    // if (clientExecutor == null || clientExecutor.isShutdown()) {
    // clientExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
    // }
    //
    // // Restart discovery to catch healthy peers
    // startNetworkDiscovery();
    // }

    // private void startNetworkDiscovery() {
    // if (discoveryMode == DiscoveryMode.NSD) {
    // nsdManager = new NsdDiscoveryManager(context, new
    // NsdDiscoveryManager.DiscoveryListener() {
    // @Override
    // public void onPeerFound(String ip, int port, String serviceName, String
    // serviceType) {
    // if (serviceType.equals(NsdDiscoveryManager.SERVICE_TYPE_SHARE)) {
    // Log.d(TAG, "NSD Share Peer Found: " + ip + ":" + port + " (" + serviceName +
    // ")");
    // scheduleDownloadFromPeer(ip, port);
    // } else if (serviceType.equals(NsdDiscoveryManager.SERVICE_TYPE_PROGRESS)) {
    // Log.d(TAG, "NSD Progress Root Found: " + ip + ":" + port + " (" + serviceName
    // + ")");
    // if (mMetadata != null) {
    // mMetadata.setRootHost(ip + ":" + port);
    // } else {
    // lastDiscoveredRootHost = ip + ":" + port;
    // }
    // }
    // }
    //
    // @Override
    // public void onPeerLost(String serviceName, String serviceType) {
    // Log.d(TAG, "NSD Peer Lost: " + serviceName + " (" + serviceType + ")");
    // }
    // });
    // nsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_SHARE);
    // nsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_PROGRESS);
    // } else {
    // wifiDirectManager = new WifiDirectDiscoveryManager(context, new
    // WifiDirectDiscoveryManager.WifiDirectListener() {
    // @Override
    // public void onPeersChanged(List<WifiP2pDevice> peers) {
    // Log.d(TAG, "WifiDirect Peers Discovered: " + peers.size());
    // // Try to connect to the first available Peer
    // if (!peers.isEmpty() && wifiDirectManager.getConnectionInfo() == null) {
    // for (WifiP2pDevice peer : peers) {
    // // Check if this peer can act as host or connect directly
    // updateStatus("Connecting to WiFi Direct peer: " + peer.deviceName);
    // wifiDirectManager.connect(peer);
    // break;
    // }
    // }
    // }
    //
    // @Override
    // public void onConnectionChanged(WifiP2pInfo info,
    // android.net.wifi.p2p.WifiP2pGroup group) {
    // if (info != null && info.groupFormed) {
    // if (info.isGroupOwner) {
    // Log.d(TAG, "Connected as WiFi Direct Group Owner.");
    // updateStatus("WiFi Direct Master Group Formed. Waiting for downloads...");
    // // Group owner can start serving chunks
    // ensureServerStarted();
    // } else {
    // String ownerIp = info.groupOwnerAddress.getHostAddress();
    // Log.d(TAG, "Connected to WifiDirect Owner: " + ownerIp);
    // updateStatus("Connected to Wi-Fi Direct Owner: " + ownerIp);
    // scheduleDownloadFromPeer(ownerIp, HTTP_PORT);
    // }
    // } else {
    // Log.d(TAG, "WifiDirect connection dropped.");
    // }
    // }
    // });
    //
    // updateStatus("Activating WiFi Direct...");
    // wifiDirectManager.startPeerDiscovery();
    // }
    // }
    //
    // private synchronized void scheduleDownloadFromPeer(String ip, int port) {
    // if (isDownloadingFinished) return;
    //
    // String address = ip + ":" + port;
    // if (activeDistributorAddresses.contains(address)) {
    // return;
    // }
    //
    // if (activeDistributorAddresses.size() >= MAX_PEER_CONNECTIONS) {
    // Log.d(TAG, "Connection limit reached. Skipping peer: " + address);
    // return;
    // }
    //
    // activeDistributorAddresses.add(address);
    // clientExecutor.submit(() -> runDownloadLoop(ip, port));
    // }
    //
    // private void runDownloadLoop(String ip, int port) {
    // String peerAddr = ip + ":" + port;
    // Log.d(TAG, "Starting consumer thread for peer: " + peerAddr);
    //
    // try {
    // // 1. Fetch metadata if we don't have it yet from root or other distributor
    // if (mMetadata == null) {
    // updateStatus("Fetching metadata from " + peerAddr);
    // FileMetadata meta = HttpClient.fetchMetadata(ip, port);
    // synchronized (this) {
    // if (mMetadata == null) {
    // mMetadata = meta;
    // // Dynamically update the root ip format
    // if (mMetadata.getRootHost() == null || mMetadata.getRootHost().isEmpty()) {
    // if (!lastDiscoveredRootHost.isEmpty()) {
    // mMetadata.setRootHost(lastDiscoveredRootHost);
    // } else {
    // mMetadata.setRootHost(peerAddr);
    // }
    // }
    // storageManager.initializeSeekerFile(mMetadata);
    // }
    // }
    // }
    //
    // // 2. Fetch the metadata renew algorithm loop
    // while (isSharingStarted && !isDownloadingFinished) {
    // updateStatus("Downloading chunk map from " + peerAddr);
    // boolean[] peerBitmap = HttpClient.fetchBitmap(ip, port,
    // mMetadata.getTotalChunks());
    //
    // // Get chunks that peer has but we are missing code
    // List<Integer> missing = storageManager.getMissingChunks();
    // if (missing.isEmpty()) {
    // // Download complete!
    // checkFullDownloadCompletion();
    // break;
    // }
    //
    // List<Integer> availableFromPeer = new ArrayList<>();
    // for (int m : missing) {
    // if (peerBitmap[m]) {
    // availableFromPeer.add(m);
    // }
    // }
    //
    // if (availableFromPeer.isEmpty()) {
    // Log.d(TAG, "No more chunks available on " + peerAddr + ". Requesting metadata
    // renew...");
    // try {
    // FileMetadata renewedMeta = HttpClient.fetchMetadata(ip, port);
    // synchronized (this) {
    // mMetadata = renewedMeta;
    // if (mMetadata.getRootHost() == null || mMetadata.getRootHost().isEmpty()) {
    // if (!lastDiscoveredRootHost.isEmpty()) {
    // mMetadata.setRootHost(lastDiscoveredRootHost);
    // } else {
    // mMetadata.setRootHost(peerAddr);
    // }
    // }
    // }
    // } catch (Exception e) {
    // Log.e(TAG, "Failed requesting metadata renew from peer " + peerAddr, e);
    // }
    // Thread.sleep(5000);
    // continue;
    // }
    //
    // // Shuffling the selection list to fully implement RANDOM chunk updates to
    // avoid congestion spikes
    // Collections.shuffle(availableFromPeer);
    //
    // for (int chunkId : availableFromPeer) {
    // if (!isSharingStarted || isDownloadingFinished) break;
    //
    // // Skip if another concurrent peer thread fetched this chunk first
    // if (storageManager.isChunkPossessed(chunkId)) {
    // continue;
    // }
    //
    // try {
    // Log.d(TAG, "Requesting chunk " + chunkId + " from " + peerAddr);
    // byte[] data = HttpClient.fetchChunk(ip, port, chunkId);
    //
    // // Storage manager chunk verification against original Root table is done
    // in-flight
    // boolean writeOk = storageManager.writeChunk(chunkId, data);
    // if (writeOk) {
    // // After downloading first chunk, immediately initiate local server to share
    // like a root!
    // if (storageManager.getPossessedCount() == 1) {
    // ensureServerStarted();
    // }
    //
    // triggerProgressUpdate();
    // } else {
    // Log.e(TAG, "Verifying chunk " + chunkId + " failed from " + peerAddr);
    // storageManager.markChunkAsFailed(chunkId);
    // }
    // } catch (Exception e) {
    // Log.e(TAG, "Failed downloading chunk " + chunkId + " from peer " + peerAddr +
    // " due to timeout/error", e);
    // storageManager.markChunkAsFailed(chunkId);
    //
    // // REQUIREMENT: "If get chunk failed due to time out, disconnect device and
    // find new device"
    // updateStatus("Peer " + peerAddr + " timed out. Disconnecting...");
    // activeDistributorAddresses.remove(peerAddr);
    // return; // Exit this download thread immediately to find a new device!
    // }
    // }
    //
    // // Let thread rest slightly
    // Thread.sleep(500);
    // }
    //
    // } catch (InterruptedException e) {
    // Log.d(TAG, "Download worker thread interrupted for " + peerAddr);
    // } catch (Exception e) {
    // Log.e(TAG, "Execution exception in downloader loop " + peerAddr, e);
    // updateStatus("Distributor connection error: " + e.getMessage());
    // } finally {
    // activeDistributorAddresses.remove(peerAddr);
    // Log.d(TAG, "Consumer thread terminado for " + peerAddr);
    // }
    // }
    //
    // private synchronized void ensureServerStarted() {
    // if (httpServer == null) {
    // try {
    // httpServer = new HttpServer(HTTP_PORT, storageManager, mMetadata,
    // this::handlePeerProgress);
    // httpServer.start();
    // updateStatus("Local Node Server spawned! Distributing chunks to other
    // seekers...");
    //
    // // Also register service on network so searchers can see us now!
    // startServiceDiscoveryRegistration();
    // } catch (IOException e) {
    // Log.e(TAG, "Failed to start local background distributor HTTP server", e);
    // }
    // }
    // }

    // private void startServiceDiscoveryRegistration() {
    // if (discoveryMode == DiscoveryMode.NSD) {
    // if (nsdManager == null) {
    // nsdManager = new NsdDiscoveryManager(context, null);
    // }
    // if (isRoot) {
    // // Root registers both:
    // // 1. Service with metadata/chunks
    // nsdManager.registerService(HTTP_PORT, deviceId,
    // NsdDiscoveryManager.SERVICE_TYPE_SHARE,
    // NsdDiscoveryManager.SERVICE_NAME_PREFIX_SHARE);
    // // 2. Root service for dynamic progress reports tracking
    // nsdManager.registerService(HTTP_PORT, deviceId,
    // NsdDiscoveryManager.SERVICE_TYPE_PROGRESS,
    // NsdDiscoveryManager.SERVICE_NAME_PREFIX_PROGRESS);
    // } else {
    // // Seeker node after receiving first chunk starts sharing as a secondary
    // distributor
    // nsdManager.registerService(HTTP_PORT, deviceId,
    // NsdDiscoveryManager.SERVICE_TYPE_SHARE,
    // NsdDiscoveryManager.SERVICE_NAME_PREFIX_SHARE);
    // }
    // } else {
    // if (wifiDirectManager == null) {
    // wifiDirectManager = new WifiDirectDiscoveryManager(context, null);
    // }
    // // WiFi Direct GO needs to establish a group to let other client peers check
    // in
    // if (wifiDirectManager.getActiveGroup() == null) {
    // wifiDirectManager.createGroup();
    // }
    // }
    // }
    //
    // private synchronized void checkFullDownloadCompletion() {
    // if (isDownloadingFinished) return;
    //
    // List<Integer> missing = storageManager.getMissingChunks();
    // if (missing.isEmpty()) {
    // isDownloadingFinished = true;
    // updateStatus("All chunks retrieved! Verifying complete SHA-256
    // integrity...");
    //
    // Executors.newSingleThreadExecutor().execute(() -> {
    // boolean verified = storageManager.verifyFullChecksum();
    // mainHandler.post(() -> {
    // if (verified) {
    // updateStatus("Success! Fully checked & compiled download. Transfer active for
    // 180s...");
    // triggerProgressUpdate(true);
    // if (listener != null) {
    // listener.onDownloadCompleted(true);
    // }
    //
    // // "Each device after receiving all chunks, then continue roles as node in
    // network to transfer data within 3 minutes, then clean up"
    // startThreeMinuteSurvivalCleanup();
    // } else {
    // updateStatus("Verification failed! Some chunks are corrupt. Retry
    // failures.");
    // if (listener != null) {
    // listener.onDownloadCompleted(false);
    // }
    // }
    // });
    // });
    // }
    // }

    // private void startThreeMinuteSurvivalCleanup() {
    // Log.d(TAG, "Download completed. Node is set to survive for 3 minutes to serve
    // peers...");
    // mainHandler.postDelayed(() -> {
    // Log.d(TAG, "Survival period up. Initiating cleanup.");
    // stopSharing();
    // if (listener != null) {
    // listener.onServiceStopped();
    // }
    // }, 3 * 60 * 1000); // 3 minutes = 180,000 ms
    // }
    //
    // private void triggerProgressUpdate(boolean force) {
    // int completed = storageManager.getPossessedCount();
    // int total = storageManager.getTotalChunks();
    // int failures = storageManager.getFailedChunks().size();
    //
    // mainHandler.post(() -> {
    // if (listener != null) {
    // listener.onProgressUpdated(completed, total, failures);
    // }
    // });
    //
    // // Report status update to root node after receiving 5 chunks, or when forced
    // (completion/retry)
    // if (mMetadata != null && !isRoot) {
    // int countSinceReport = receivedChunksCounterSinceReport.incrementAndGet();
    // if (force || countSinceReport >= 5 || completed == total) {
    // receivedChunksCounterSinceReport.set(0);
    // HttpClient.reportProgress(mMetadata.getRootHost(), deviceId, completed,
    // total, failures);
    // }
    // }
    // }
    //
    // private void triggerProgressUpdate() {
    // triggerProgressUpdate(false);
    // }
    //
    // private void handlePeerProgress(String devId, String ip, int completed, int
    // total, int failures) {
    // peerProgressMap.put(devId, new PeerProgress(ip, completed, total, failures));
    // mainHandler.post(() -> {
    // if (listener != null) {
    // listener.onPeerProgressUpdated(peerProgressMap);
    // }
    // });
    // }
    //
    // private void updateStatus(String status) {
    // mainHandler.post(() -> {
    // if (listener != null) {
    // listener.onStatusUpdated(status);
    // }
    // });
    // }

    public synchronized void stopSharing() {
        discoveryMode = DiscoveryMode.NSD;
        isSharingStarted = false;
        isDownloadingFinished = true;

        if (mLeechingExecutor != null) {
            mLeechingExecutor.shutdownNow();
            mLeechingExecutor = Executors.newFixedThreadPool(MAX_PEER_CONNECTIONS);
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
