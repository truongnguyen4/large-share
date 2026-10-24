package com.datalogic.largeshareapp.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.datalogic.largeshareapp.discovery.GroupAdvert;
import com.datalogic.largeshareapp.discovery.NsdDiscoveryManager;
import com.datalogic.largeshareapp.discovery.WifiDirectSwarm;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.NetworkMonitor;
import com.datalogic.largeshareapp.network.PeerException;
import com.datalogic.largeshareapp.security.Identity;
import com.datalogic.largeshareapp.security.IdentityStore;
import com.datalogic.largeshareapp.storage.StorageLocations;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinator of one share session: wires discovery, the download (SeekManager), the upload
 * (SeedManager), identity, network monitoring and progress reporting together.
 */
public class ShareManager {
    private static final String TAG = "ShareManager";
    public static final int HTTP_PORT = 8888;
    private static final String SWARM_OWNER = "group-owner";
    public static final int DEFAULT_GROUP_CAPACITY = 3;
    private static final long SLOT_LEASE_MS = 15_000L;
    private static final long SLOT_HEARTBEAT_MS = 4_000L;
    /** A group owner that has not answered for this long is gone (its app died, the group lingers). */
    private static final long OWNER_SILENCE_MS = 20_000L;

    /** After leeching finishes we keep seeding briefly so late peers can still pull chunks. */
    public static final long STOP_SEED_DELAY_MS = 15_000L;

    public enum DiscoveryMode {
        UNKNOWN,
        NSD,
        WIFI_DIRECT
    }

    public enum DiscoveryRole {
        UNKNOWN,
        SEEDER,
        SEEKER
    }

    /** Coarse, UI-friendly phases of a session. */
    public enum Phase {
        IDLE, PREPARING, SHARING, SEARCHING, DOWNLOADING, PAUSED_NETWORK, VERIFYING, COMPLETED, FAILED, LEFT
    }

    public interface StatusListener {
        void onStatus(Phase phase, String detail);
    }

    private final Context mContext;
    private final DiscoveryMode mDiscoveryMode;
    private final Identity mIdentity;
    private final String mFleetSecret;
    private final HttpClient mClient;
    private final SwarmSession mSession = new SwarmSession();
    private final PeerRegistry mRegistry = new PeerRegistry();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "share-manager");
        t.setDaemon(true);
        return t;
    });

    private final Set<SeekManager.SeekEventListener> mSeekListeners = new CopyOnWriteArraySet<>();
    private final Set<SeedManager.PeersTrackingListener> mTrackingListeners = new CopyOnWriteArraySet<>();
    private final Set<StatusListener> mStatusListeners = new CopyOnWriteArraySet<>();

    private File mFileShared;
    private File mReceiveDir;
    private InformationMetadata mPrecomputed;
    private long mProgressIntervalMs = 3_000L;
    private int mMaxServeConnections = SeedManager.DEFAULT_MAX_CONNECTIONS;

    private DiscoveryRole mRole = DiscoveryRole.UNKNOWN;
    private boolean mStarted;
    private boolean mSeeding;
    private volatile Phase mPhase = Phase.IDLE;

    private SeekManager mSeekManager;
    private SeedManager mSeedManager;
    private ProgressReporter mProgressReporter;
    private NsdDiscoveryManager mNsdManager;
    private volatile WifiDirectSwarm mSwarm;
    private volatile SlotLeases mSlots;
    private volatile Peer mSlotOwner;
    private ScheduledExecutorService mSlotHeartbeat;
    private int mGroupCapacity = DEFAULT_GROUP_CAPACITY;
    private NetworkMonitor mNetworkMonitor;

    private final Runnable mStopSeedRunnable = this::stopSeed;

    public ShareManager(Context context, File file, DiscoveryMode mode) {
        mContext = context.getApplicationContext();
        mFileShared = file;
        mDiscoveryMode = mode;
        mIdentity = IdentityStore.load(mContext);
        mFleetSecret = IdentityStore.getFleetSecret(mContext);
        mClient = new HttpClient(mIdentity);
        mReceiveDir = StorageLocations.defaultReceiveDir(mContext);
    }

    // ------------------------------------------------------------------ configuration

    /** Re-use a manifest the UI already computed so a multi-GB file is not hashed twice. */
    public void setPrecomputedMetadata(InformationMetadata metadata) {
        mPrecomputed = metadata;
    }

    public void setReceiveDir(File dir) {
        if (dir != null) {
            mReceiveDir = dir;
        }
    }

    public void setProgressIntervalMs(long ms) {
        mProgressIntervalMs = ms;
    }

    public void setMaxServeConnections(int max) {
        mMaxServeConnections = max;
    }

    public String getDeviceId() {
        return mIdentity.deviceId();
    }

    /**
     * Adds a peer by address, for networks that block multicast/mDNS so NSD cannot find anyone.
     * Static peers are tried exactly like discovered ones (back-off, identity check) but are never
     * dropped because discovery lost sight of them.
     *
     * @return false when the address is malformed
     */
    public boolean addStaticPeer(String hostPort) {
        if (hostPort == null) {
            return false;
        }
        String[] parts = hostPort.trim().split(":");
        if (parts.length < 1 || parts[0].isEmpty()) {
            return false;
        }
        int port = HTTP_PORT;
        if (parts.length == 2) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (parts.length > 2) {
            return false;
        }
        mRegistry.upsert(new Peer(parts[0], port, null, "static"));
        Log.i(TAG, "Static peer added: " + parts[0] + ":" + port);
        return true;
    }

    public Phase getPhase() {
        return mPhase;
    }

    public void addSeekEventListener(SeekManager.SeekEventListener listener) {
        if (listener == null) {
            return;
        }
        mSeekListeners.add(listener);
        SeekManager seek = mSeekManager;
        if (seek != null) {
            seek.addSeekListener(listener);
        }
    }

    public void removeSeekEventListener(SeekManager.SeekEventListener listener) {
        mSeekListeners.remove(listener);
        SeekManager seek = mSeekManager;
        if (seek != null) {
            seek.removeSeekListener(listener);
        }
    }

    public void addPeersTrackingListener(SeedManager.PeersTrackingListener listener) {
        if (listener == null) {
            return;
        }
        mTrackingListeners.add(listener);
        SeedManager seed = mSeedManager;
        if (seed != null) {
            seed.addPeersTrackingListener(listener);
        }
    }

    public void removePeersTrackingListener(SeedManager.PeersTrackingListener listener) {
        mTrackingListeners.remove(listener);
        SeedManager seed = mSeedManager;
        if (seed != null) {
            seed.removePeersTrackingListener(listener);
        }
    }

    public void addStatusListener(StatusListener listener) {
        if (listener != null) {
            mStatusListeners.add(listener);
        }
    }

    public void removeStatusListener(StatusListener listener) {
        mStatusListeners.remove(listener);
    }

    private void publish(Phase phase, String detail) {
        mPhase = phase;
        Log.i(TAG, "[" + phase + "] " + detail);
        for (StatusListener l : mStatusListeners) {
            try {
                l.onStatus(phase, detail);
            } catch (RuntimeException e) {
                Log.e(TAG, "StatusListener failed", e);
            }
        }
    }

    /** Runs work on the session thread; silently ignored once the session has been shut down. */
    private void runAsync(Runnable task) {
        try {
            mWorker.execute(task);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "Task ignored: session already stopped");
        }
    }

    // ------------------------------------------------------------------ discovery callbacks

    private final NsdDiscoveryManager.DiscoveryListener mDiscoveryListener =
            new NsdDiscoveryManager.DiscoveryListener() {
                @Override
                public void onPeerFound(String ip, int port, String serviceName, String serviceType,
                                        Map<String, String> attributes) {
                    Log.d(TAG, "NSD peer found: " + ip + ":" + port + " (" + serviceName + ") [" + serviceType + "]");

                    ProgressReporter reporter = mProgressReporter;
                    if (NsdDiscoveryManager.SERVICE_TYPE_PROGRESS.equals(serviceType)) {
                        if (reporter != null) {
                            reporter.setRoot(new Peer(ip, port, serviceName, serviceType));
                        }
                        return;
                    }

                    if (serviceName.equals(Peer.NAME_PREFIX_SHARE + mIdentity.deviceId())) {
                        return; // ourselves
                    }
                    String tag = attributes != null ? attributes.get("h") : null;
                    InformationMetadata info = mSession.info();
                    if (info != null && tag != null && !tag.isEmpty() && !tag.equals(info.swarmTag())) {
                        Log.d(TAG, "Ignoring " + serviceName + ": different payload");
                        return;
                    }
                    mRegistry.upsert(new Peer(ip, port, serviceName, serviceType, tag));
                }

                @Override
                public void onPeerLost(String serviceName, String serviceType) {
                    Log.d(TAG, "NSD peer lost: " + serviceName + " [" + serviceType + "]");
                    ProgressReporter reporter = mProgressReporter;
                    if (NsdDiscoveryManager.SERVICE_TYPE_PROGRESS.equals(serviceType)) {
                        if (reporter != null) {
                            reporter.clearRoot(serviceName);
                        }
                        return;
                    }
                    // Forget it for now; if it comes back it is rediscovered and retried at once.
                    mRegistry.remove(serviceName);
                }
            };

    private final SeekManager.SeekEventListener mInternalSeekListener = new SeekManager.SeekEventListener() {
        @Override
        public void onMetadataReady(InformationMetadata metadata) {
            // Become a source immediately: every leecher is also a seeder.
            runAsync(ShareManager.this::startSeeding);
        }

        @Override
        public void onChunkBatchLeeched(int completed, int total) {
            ProgressReporter reporter = mProgressReporter;
            if (reporter != null) {
                reporter.update(completed, total);
            }
        }

        @Override
        public void onLeechingCompleted() {
            Log.d(TAG, "Leeching completed. Stopping seek now; seed stops in "
                    + (STOP_SEED_DELAY_MS / 1000) + "s.");
            if (isSwarmMode() && mSwarm != null) {
                // Graduate: leave the group (freeing a slot) and own a new group to share further.
                publish(Phase.COMPLETED, "Download complete and verified, graduating to group owner");
                runAsync(ShareManager.this::stopSeek);
                runAsync(ShareManager.this::graduate);
                return;
            }
            publish(Phase.COMPLETED, "Download complete and verified");
            runAsync(() -> {
                stopSeek();
                // Stay online briefly so other peers can keep leeching chunks from us.
                mMainHandler.postDelayed(mStopSeedRunnable, STOP_SEED_DELAY_MS);
            });
        }

        @Override
        public void onLeechingFailed(String reason) {
            publish(Phase.FAILED, reason);
        }

        @Override
        public void onStateChanged(SeekManager.State state, String detail) {
            switch (state) {
                case WAITING_PEERS:
                    publish(Phase.SEARCHING, detail);
                    break;
                case DOWNLOADING:
                    publish(Phase.DOWNLOADING, detail);
                    break;
                case PAUSED:
                    publish(Phase.PAUSED_NETWORK, detail);
                    break;
                case VERIFYING:
                    publish(Phase.VERIFYING, detail);
                    break;
                default:
                    break; // COMPLETED / FAILED are published by their own callbacks
            }
        }

        @Override
        public void onStats(double bytesPerSec, int activePeers, int knownPeers) {
            if (mPhase == Phase.DOWNLOADING) {
                publish(Phase.DOWNLOADING, String.format(Locale.US,
                        "%.1f MB/s from %d peer(s), %d known", bytesPerSec / (1024 * 1024),
                        activePeers, knownPeers));
            }
        }

        @Override
        public void onNeedPeers() {
            NsdDiscoveryManager nsd = mNsdManager;
            if (nsd != null) {
                Log.i(TAG, "No usable peer for a while, refreshing discovery");
                nsd.restart();
            }
        }
    };

    private final NetworkMonitor.Listener mNetworkListener = new NetworkMonitor.Listener() {
        @Override
        public void onNetworkLost() {
            SeekManager seek = mSeekManager;
            if (seek != null) {
                seek.pause();
            }
            if (mRole == DiscoveryRole.SEEDER) {
                publish(Phase.PAUSED_NETWORK, "Network lost, waiting to reconnect");
            }
        }

        @Override
        public void onNetworkAvailable(boolean addressChanged) {
            // After a reconnect or roaming our advertisement points at a dead address and cached
            // peers may have moved: redo discovery, then let the download retry everyone at once.
            NsdDiscoveryManager nsd = mNsdManager;
            if (nsd != null) {
                nsd.restart();
            }
            SeekManager seek = mSeekManager;
            if (seek != null) {
                seek.resume();
            }
            ProgressReporter reporter = mProgressReporter;
            if (reporter != null) {
                reporter.kick();
            }
            if (mRole == DiscoveryRole.SEEDER) {
                publish(Phase.SHARING, "Network restored, sharing again");
            }
        }
    };

    // ------------------------------------------------------------------ start

    public synchronized void startSharing(DiscoveryRole role) {
        if (mStarted) {
            Log.w(TAG, "Already started");
            return;
        }
        Log.d(TAG, "Starting sharing as " + role + " with discovery mode: " + mDiscoveryMode
                + ", deviceId=" + mIdentity.deviceId());
        mStarted = true;
        mRole = role;
        switch (role) {
            case SEEDER:
                publish(Phase.PREPARING, "Preparing file manifest");
                runAsync(this::startRoot);
                break;
            case SEEKER:
                startSeeker();
                break;
            default:
                mStarted = false;
                Log.w(TAG, "Unknown role: " + role);
        }
    }

    private void startRoot() {
        InformationMetadata info = mPrecomputed;
        if (info == null) {
            info = SecureManager.createFileMetadata(mFileShared, SecureManager.CHUNK_SIZE_512KB,
                    (done, total) -> {
                        if (done % 64 == 0 || done == total) {
                            publish(Phase.PREPARING, "Hashing " + done + "/" + total + " chunks");
                        }
                    });
        }
        if (info == null) {
            publish(Phase.FAILED, "Cannot read the file to share");
            return;
        }
        try {
            File source = info.getSourceFile() != null ? info.getSourceFile() : mFileShared;
            StorageManager storage = StorageManager.forSource(info, source);
            BitSetMetadata bits = new BitSetMetadata(info.getChunkCount());
            bits.setAllAvailable();
            mSession.attach(info, bits, storage);
        } catch (IOException e) {
            publish(Phase.FAILED, "Cannot open file: " + e.getMessage());
            return;
        }
        if (!startSeeding()) {
            publish(Phase.FAILED, "Cannot start the share server (port " + HTTP_PORT + " busy?)");
            return;
        }
        if (mDiscoveryMode == DiscoveryMode.WIFI_DIRECT) {
            startSwarm();
        } else {
            trackSharing();
            startNetworkMonitor();
        }
        publish(Phase.SHARING, "Sharing " + info.getFileName() + " (" + info.getChunkCount() + " chunks)");
    }

    // ------------------------------------------------------------------ Wi-Fi Direct swarm

    /** Wi-Fi Direct mode is the self-organising swarm (no infrastructure needed). */
    private boolean isSwarmMode() {
        return mDiscoveryMode == DiscoveryMode.WIFI_DIRECT;
    }

    public void setGroupCapacity(int capacity) {
        mGroupCapacity = Math.max(1, capacity);
    }

    /**
     * Graduation model: the root owns the first group; members that finish leave their group and
     * own a new one, so the number of sources grows with every generation.
     */
    private void startSwarm() {
        String id = mIdentity.deviceId();
        String ownGroupId = id.length() > 8 ? id.substring(0, 8) : id;
        mSwarm = new WifiDirectSwarm(mContext, mFleetSecret, ownGroupId, new WifiDirectSwarm.Listener() {
            @Override
            public void onJoinedGroup(String ownerAddress, GroupAdvert group) {
                // A new group is a new subnet: addresses learned in the previous one are void.
                mRegistry.clear();
                // Keyed by address (no service name), so the same ip:port learned again through
                // peer exchange is not registered twice.
                Peer owner = new Peer(ownerAddress, HTTP_PORT, null, SWARM_OWNER);
                mRegistry.upsert(owner);
                publish(Phase.SEARCHING, "Member of " + group + ", leasing a slot");
                startSlotHeartbeat(owner);
            }

            @Override
            public void onLeftGroup() {
                stopSlotHeartbeat();
                mRegistry.clear();
            }

            @Override
            public void onOwnerReady(String groupId, int generation) {
                mSlots = new SlotLeases(mGroupCapacity, SLOT_LEASE_MS);
                publish(Phase.SHARING, String.format(Locale.US, "Owner of group '%s' (generation %d, %d slots)",
                        groupId, generation, mGroupCapacity));
            }

            @Override
            public void onMembersChanged(int members) {
                Log.i(TAG, "Wi-Fi Direct clients in our group: " + members);
            }

            @Override
            public void onSearching(String detail) {
                if (mPhase == Phase.SEARCHING || mPhase == Phase.PREPARING) {
                    publish(Phase.SEARCHING, detail);
                }
            }
        });
        if (!mSwarm.isSupported()) {
            publish(Phase.FAILED, "Wi-Fi Direct is not supported on this device");
            return;
        }
        WifiDirectSwarm swarm = mSwarm;
        if (mRole == DiscoveryRole.SEEDER) {
            mMainHandler.post(swarm::startAsRoot);
        } else {
            mMainHandler.post(swarm::startAsSeeker);
        }
    }

    /** Owner side of the slot lease (GET /group from an authenticated member). */
    private byte[] groupStatus(String deviceId, boolean leave) {
        WifiDirectSwarm swarm = mSwarm;
        SlotLeases slots = mSlots;
        if (swarm == null || slots == null || swarm.getRole() != WifiDirectSwarm.Role.OWNER) {
            return null; // not owning a group: "not ready"
        }
        long now = System.currentTimeMillis();
        boolean admitted;
        if (leave) {
            slots.release(deviceId);
            admitted = false;
        } else {
            admitted = slots.admit(deviceId, now);
        }
        int holders = slots.holders(now);
        Log.d(TAG, "Slot " + (leave ? "released by " : admitted ? "leased to " : "refused to ") + deviceId
                + " (" + holders + "/" + slots.capacity() + ")");
        if (!leave) {
            publish(Phase.SHARING, String.format(Locale.US, "Owner: %d/%d members", holders, slots.capacity()));
        }
        try {
            return new JSONObject().put("admitted", admitted).put("members", holders)
                    .put("capacity", slots.capacity()).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Member side: renew our slot every few seconds; leave the group if the owner refuses it. */
    private synchronized void startSlotHeartbeat(Peer owner) {
        stopSlotHeartbeat();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slot-heartbeat");
            t.setDaemon(true);
            return t;
        });
        mSlotHeartbeat = heartbeat;
        mSlotOwner = owner;
        long[] lastHeard = {SystemClock.elapsedRealtime()};
        heartbeat.scheduleWithFixedDelay(() -> {
            WifiDirectSwarm swarm = mSwarm;
            try {
                HttpClient.GroupStatus status = mClient.leaseSlot(owner, false);
                lastHeard[0] = SystemClock.elapsedRealtime();
                if (!status.admitted) {
                    Log.i(TAG, "Owner refused a slot (" + status.members + "/" + status.capacity + " taken)");
                    if (swarm != null) {
                        mMainHandler.post(swarm::leaveFullGroup);
                    }
                }
            } catch (PeerException e) {
                long silent = SystemClock.elapsedRealtime() - lastHeard[0];
                if (e.kind != PeerException.Kind.NETWORK) {
                    lastHeard[0] = SystemClock.elapsedRealtime(); // busy or not ready, but alive
                    Log.d(TAG, "Slot lease failed: " + e.kind);
                } else if (silent >= OWNER_SILENCE_MS) {
                    // The group can outlive its owner's app: without this the member would wait forever.
                    Log.w(TAG, "Group owner silent for " + silent / 1000 + " s, leaving its group");
                    if (swarm != null) {
                        mMainHandler.post(swarm::leaveDeadGroup);
                    }
                } else {
                    Log.d(TAG, "Slot lease failed: " + e.kind);
                }
            }
        }, 0, SLOT_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopSlotHeartbeat() {
        if (mSlotHeartbeat != null) {
            mSlotHeartbeat.shutdownNow();
            mSlotHeartbeat = null;
        }
    }

    /** Graduation: give the slot back at once, then leave and become an owner. */
    private void graduate() {
        Peer owner = mSlotOwner;
        stopSlotHeartbeat();
        if (owner != null) {
            try {
                mClient.leaseSlot(owner, true);
            } catch (PeerException e) {
                Log.d(TAG, "Could not release the slot (" + e.kind + "); it expires by itself");
            }
        }
        WifiDirectSwarm swarm = mSwarm;
        if (swarm != null) {
            mMainHandler.post(swarm::graduate);
        }
    }

    private void startSeeker() {
        SeekManager.Config seekConfig = new SeekManager.Config();
        mSeekManager = new SeekManager(mSession, mClient, mRegistry, mReceiveDir, seekConfig);
        mSeekManager.addSeekListener(mInternalSeekListener);
        for (SeekManager.SeekEventListener l : mSeekListeners) {
            mSeekManager.addSeekListener(l);
        }
        mProgressReporter = new ProgressReporter(mClient, mIdentity.deviceId(), mProgressIntervalMs);

        if (mDiscoveryMode == DiscoveryMode.WIFI_DIRECT) {
            // Serve from the start: once this member graduates, the same server answers slot
            // leases of its own group, and other members can pull chunks as soon as they are verified.
            if (!startServer()) {
                publish(Phase.FAILED, "Cannot start the share server (port " + HTTP_PORT + " busy?)");
                return;
            }
            mSeekManager.start();
            startSwarm();
            publish(Phase.SEARCHING, "Looking for a Wi-Fi Direct group");
            return;
        }

        mNsdManager = new NsdDiscoveryManager(mContext);
        mNsdManager.addDiscoveryListener(mDiscoveryListener);
        mNsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_SHARE);
        mNsdManager.startDiscovery(NsdDiscoveryManager.SERVICE_TYPE_PROGRESS);

        mSeekManager.start();
        startNetworkMonitor();
        publish(Phase.SEARCHING, "Looking for peers");
    }

    private void startNetworkMonitor() {
        if (mNetworkMonitor == null) {
            mNetworkMonitor = new NetworkMonitor(mContext, mNetworkListener);
            mNetworkMonitor.start();
        }
    }

    /** Starts serving chunks and advertises this device as a source. Idempotent. */
    private synchronized boolean startSeeding() {
        if (mSeeding) {
            return true;
        }
        InformationMetadata info = mSession.info();
        if (info == null || !startServer()) {
            return false;
        }
        mSeeding = true;

        if (mDiscoveryMode == DiscoveryMode.NSD) {
            if (mNsdManager == null) {
                mNsdManager = new NsdDiscoveryManager(mContext);
            }
            Map<String, String> attributes = new HashMap<>();
            attributes.put("h", info.swarmTag());
            attributes.put("v", "2");
            mNsdManager.registerService(HTTP_PORT, NsdDiscoveryManager.SERVICE_TYPE_SHARE,
                    NsdDiscoveryManager.SERVICE_NAME_PREFIX_SHARE, mIdentity.deviceId(), attributes);
        }
        Log.d(TAG, "Seeding started for " + info.getFileName());
        return true;
    }

    /**
     * Starts the HTTP server. It answers "not ready" until the session has a manifest, but it
     * already records the peers that contact it (for peer exchange). Idempotent.
     */
    private synchronized boolean startServer() {
        if (mSeedManager != null) {
            return true;
        }
        SeedManager seed = new SeedManager(mSession, mIdentity, HTTP_PORT, mMaxServeConnections);
        for (SeedManager.PeersTrackingListener l : mTrackingListeners) {
            seed.addPeersTrackingListener(l);
        }
        if (isSwarmMode()) {
            seed.setGroupStatusProvider(this::groupStatus);
        }
        if (!seed.initialize()) {
            return false;
        }
        mSeedManager = seed;
        // From now on every request we make tells the peer where we serve.
        mClient.setAdvertisedPort(HTTP_PORT);
        return true;
    }

    /** The root also advertises the progress sink the other devices report to. */
    public synchronized void trackSharing() {
        if (mNsdManager == null || mDiscoveryMode != DiscoveryMode.NSD) {
            return;
        }
        mNsdManager.registerService(HTTP_PORT, NsdDiscoveryManager.SERVICE_TYPE_PROGRESS,
                NsdDiscoveryManager.SERVICE_NAME_PREFIX_PROGRESS, mIdentity.deviceId(), null);
    }

    // ------------------------------------------------------------------ stop

    /** Stops everything immediately (leeching and seeding). */
    public synchronized void stopSharing() {
        mMainHandler.removeCallbacks(mStopSeedRunnable);
        stopSeek();
        stopSeed();
        if (mProgressReporter != null) {
            mProgressReporter.closeNow();
            mProgressReporter = null;
        }
        mWorker.shutdown();
    }

    /** Stops leeching (downloading) while seeding continues. */
    public synchronized void stopSeek() {
        if (mSeekManager != null) {
            mSeekManager.stop();
        }
        if (mNsdManager != null) {
            mNsdManager.stopDiscovery(NsdDiscoveryManager.SERVICE_TYPE_SHARE);
        }
    }

    /** Stops seeding and tears down discovery, networking and storage. */
    public synchronized void stopSeed() {
        mMainHandler.removeCallbacks(mStopSeedRunnable);
        boolean wasActive = mStarted || mSeeding;
        if (mNetworkMonitor != null) {
            mNetworkMonitor.stop();
            mNetworkMonitor = null;
        }
        if (mSeedManager != null) {
            mSeedManager.stop();
            mSeedManager = null;
        }
        if (mNsdManager != null) {
            mNsdManager.cleanUp();
            mNsdManager = null;
        }
        stopSlotHeartbeat();
        if (mSwarm != null) {
            WifiDirectSwarm swarm = mSwarm;
            mSwarm = null;
            mMainHandler.post(swarm::stop);
        }
        if (mProgressReporter != null) {
            // Do not lose the final 100% report just because the session is closing.
            mProgressReporter.closeAfterDelivery(10_000L);
        }
        mSession.close();
        mSeeding = false;
        mStarted = false;
        if (!wasActive) {
            return;
        }
        if (mPhase == Phase.COMPLETED) {
            publish(Phase.LEFT, "Left the share session");
        } else if (mPhase != Phase.FAILED) {
            publish(Phase.IDLE, "Stopped");
        }
    }
}
