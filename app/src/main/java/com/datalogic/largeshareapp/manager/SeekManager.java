package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.PeerException;
import com.datalogic.largeshareapp.storage.StorageManager;

import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads (leeches) the shared payload from whatever peers are known.
 *
 * <p>A single supervisor loop keeps up to {@code maxPeers} worker sessions running against
 * healthy peers taken from the {@link PeerRegistry}. When a session fails the chunk it was fetching
 * is returned to the pool, the peer is put into exponential back-off (never forgotten), and the
 * supervisor simply starts a session with another peer, so a dropped peer, a flapping network or a
 * restarted root are all handled by the same "keep retrying" loop. Completion is only declared
 * after the whole-file SHA-256 matches; a mismatch re-fetches just the corrupt chunks.
 */
public class SeekManager {
    private static final String TAG = "SeekManager";
    /** Immediate reconnect attempts to the same peer after a dropped connection. */
    private static final int MAX_INLINE_RETRIES = 3;

    public enum State { IDLE, WAITING_PEERS, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, STOPPED }

    public static class Config {
        /** Total concurrent leech sessions (connections), across all peers. */
        public int maxPeers = 5;
        public long tickMs = 500;
        public long idleRefreshMs = 700;
        public int maxIdleRounds = 6;
        public long stateSaveMs = 3_000;
        public long statsMs = 1_000;
        public long needPeersAfterMs = 15_000;
        public int maxRepairRounds = 3;
        /** How often a source is asked for the other devices it has seen serving (peer exchange). */
        public long pexIntervalMs = 10_000;
    }

    public interface SeekEventListener {
        default void onMetadataReady(InformationMetadata metadata) {}
        default void onChunkBatchLeeched(int completed, int total) {}
        default void onLeechingCompleted() {}
        default void onLeechingFailed(String reason) {}
        default void onStateChanged(State state, String detail) {}
        default void onStats(double bytesPerSec, int activePeers, int knownPeers) {}
        /** No usable peer for a while: the owner should refresh discovery. */
        default void onNeedPeers() {}
    }

    private final SwarmSession mSession;
    private final HttpClient mClient;
    private final PeerRegistry mRegistry;
    private final File mReceiveDir;
    private final Config mConfig;
    private final Random mRandom = new Random();
    private final Set<SeekEventListener> mListeners = new CopyOnWriteArraySet<>();

    private final ScheduledExecutorService mSupervisor =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("seek-supervisor-"));
    private final ThreadPoolExecutor mWorkers;

    private final Map<String, Long> mLastExchange = new ConcurrentHashMap<>();

    private final Object mInitLock = new Object();
    private final AtomicBoolean mCompleting = new AtomicBoolean(false);
    private final AtomicInteger mRepairRounds = new AtomicInteger(0);
    private final AtomicLong mDownloadedBytes = new AtomicLong(0);
    private final AtomicLong mLastProgressNotify = new AtomicLong(0);

    private volatile boolean mRunning;
    private volatile boolean mPaused;
    private volatile boolean mDirty;
    private volatile State mState = State.IDLE;
    private volatile String mStateDetail = "";

    private long mLastSave;
    private long mLastStatsAt;
    private long mLastStatsBytes;
    private long mLastNeedPeersAt;
    private long mNoPeerSince;

    public SeekManager(SwarmSession session, HttpClient client, PeerRegistry registry,
                       File receiveDir, Config config) {
        mSession = session;
        mClient = client;
        mRegistry = registry;
        mReceiveDir = receiveDir;
        mConfig = config != null ? config : new Config();
        mWorkers = new ThreadPoolExecutor(mConfig.maxPeers, mConfig.maxPeers, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonFactory("seek-worker-"));
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        long now = System.currentTimeMillis();
        mLastSave = mLastStatsAt = mLastNeedPeersAt = mNoPeerSince = now;
        setState(State.WAITING_PEERS, "Looking for peers");
        mSupervisor.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                Log.e(TAG, "Supervisor tick failed", e);
            }
        }, 0, mConfig.tickMs, TimeUnit.MILLISECONDS);
    }

    /** Stops for good. Verified chunks are persisted so a later session can resume. */
    public void stop() {
        mRunning = false;
        mSupervisor.shutdownNow();
        mWorkers.shutdownNow();
        saveState();
        if (mState != State.COMPLETED && mState != State.FAILED) {
            setState(State.STOPPED, "Stopped");
        }
    }

    /** The network is gone: stop spending effort, keep all progress. */
    public void pause() {
        if (!mRunning || mPaused) {
            return;
        }
        mPaused = true;
        saveState();
        setState(State.PAUSED, "Network lost, waiting to reconnect");
    }

    /** The network is back: every peer gets an immediate fresh attempt. */
    public void resume() {
        if (!mRunning || !mPaused) {
            return;
        }
        mRegistry.resetBackoffs();
        mPaused = false;
        mNoPeerSince = System.currentTimeMillis();
        setState(State.WAITING_PEERS, "Network back, reconnecting");
    }

    public State getState() {
        return mState;
    }

    public boolean isComplete() {
        return mState == State.COMPLETED;
    }

    public long getDownloadedBytes() {
        return mDownloadedBytes.get();
    }

    // ------------------------------------------------------------------ supervisor

    private void tick() {
        if (!mRunning || mPaused) {
            return;
        }
        State state = mState;
        if (state == State.COMPLETED || state == State.FAILED || state == State.VERIFYING) {
            return;
        }

        while (mRegistry.activeCount() < mConfig.maxPeers) {
            Peer peer = mRegistry.acquire();
            if (peer == null) {
                break;
            }
            try {
                mWorkers.execute(() -> runPeer(peer));
            } catch (RejectedExecutionException e) {
                mRegistry.release(peer, PeerRegistry.Outcome.NEUTRAL, 0, 0);
                return;
            }
        }

        long now = System.currentTimeMillis();
        int active = mRegistry.activePeerCount();
        if (active > 0) {
            mNoPeerSince = now;
            setState(State.DOWNLOADING, "Downloading from " + active + " peer(s)");
        } else {
            setState(State.WAITING_PEERS, mRegistry.size() == 0
                    ? "Looking for peers"
                    : "Waiting to retry " + mRegistry.size() + " known peer(s)");
            if (now - mNoPeerSince > mConfig.needPeersAfterMs
                    && now - mLastNeedPeersAt > mConfig.needPeersAfterMs) {
                mLastNeedPeersAt = now;
                notifyListeners(l -> l.onNeedPeers());
            }
        }

        if (mDirty && now - mLastSave >= mConfig.stateSaveMs) {
            saveState();
        }
        if (now - mLastStatsAt >= mConfig.statsMs) {
            long bytes = mDownloadedBytes.get();
            double speed = (bytes - mLastStatsBytes) * 1000.0 / Math.max(1, now - mLastStatsAt);
            mLastStatsAt = now;
            mLastStatsBytes = bytes;
            int known = mRegistry.size();
            notifyListeners(l -> l.onStats(speed, active, known));
        }
    }

    private void saveState() {
        StorageManager storage = mSession.storage();
        BitSetMetadata bits = mSession.bits();
        if (storage != null && bits != null) {
            mDirty = false;
            mLastSave = System.currentTimeMillis();
            storage.persistState(bits.getAvailableChunks());
        }
    }

    // ------------------------------------------------------------------ one peer session

    private void runPeer(Peer peer) {
        PeerRegistry.Outcome outcome = PeerRegistry.Outcome.NEUTRAL;
        long[] bytes = {0};
        long started = System.currentTimeMillis();
        try {
            ensureInitialized(peer);
            outcome = leech(peer, bytes);
        } catch (PeerException e) {
            outcome = classify(e);
            Log.w(TAG, "Session with " + peer + " ended: " + e.kind + " - " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "Local storage error while leeching from " + peer, e);
            fail("Storage error: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "Unexpected error leeching from " + peer, e);
            outcome = PeerRegistry.Outcome.NETWORK_FAILURE;
        } finally {
            if (!mRunning || mPaused) {
                // Interrupted by us, not caused by the peer: do not hold it against the peer.
                if (outcome == PeerRegistry.Outcome.NETWORK_FAILURE) {
                    outcome = PeerRegistry.Outcome.NEUTRAL;
                }
            }
            mRegistry.release(peer, outcome, bytes[0], System.currentTimeMillis() - started);
        }
    }

    private PeerRegistry.Outcome classify(PeerException e) {
        switch (e.kind) {
            case BUSY:
                return PeerRegistry.Outcome.BUSY;
            case NOT_AVAILABLE:
                return PeerRegistry.Outcome.IDLE;
            case UNAUTHORIZED:
            case IDENTITY:
            case SELF:
                return PeerRegistry.Outcome.REJECTED_IDENTITY;
            case NETWORK:
            case PROTOCOL:
            default:
                return PeerRegistry.Outcome.NETWORK_FAILURE;
        }
    }

    /** The first peer that answers provides the manifest; storage is then created or resumed. */
    private void ensureInitialized(Peer peer) throws PeerException, IOException {
        if (mSession.isReady()) {
            return;
        }
        synchronized (mInitLock) {
            if (mSession.isReady()) {
                return;
            }
            InformationMetadata info = mClient.fetchMetadata(peer);
            File target = new File(mReceiveDir, info.getFileName());
            StorageManager storage = StorageManager.forLeech(info, target);
            BitSetMetadata bits = new BitSetMetadata(info.getChunkCount());
            bits.restoreAvailable(storage.loadResumeState());
            mSession.attach(info, bits, storage);
            Log.d(TAG, "Initialized leeching: " + info + ", resumed " + bits.availableCount() + " chunks");
            notifyListeners(l -> l.onMetadataReady(info));
        }
        if (mSession.bits().isComplete()) {
            maybeFinish();
        }
    }

    private PeerRegistry.Outcome leech(Peer peer, long[] bytes) throws PeerException, IOException {
        InformationMetadata info = mSession.info();
        BitSetMetadata bits = mSession.bits();
        StorageManager storage = mSession.storage();
        if (info == null || bits == null || storage == null) {
            return PeerRegistry.Outcome.NEUTRAL;
        }

        HttpClient.BitsetResult view = mClient.fetchBitset(peer);
        if (!info.getFileHash().equalsIgnoreCase(view.fileHash)) {
            Log.w(TAG, peer + " serves a different payload, skipping");
            return PeerRegistry.Outcome.WRONG_SWARM;
        }
        BitSet reference = view.bits;
        exchangePeers(peer);
        int idleRounds = 0;
        int consecutiveErrors = 0;
        boolean transferred = false;

        while (mRunning && !mPaused && !bits.isComplete()) {
            int chunkId = bits.claimRandom(reference, mRandom);
            if (chunkId < 0) {
                chunkId = bits.claimEndgame(reference, mRandom);
            }
            if (chunkId < 0) {
                // This peer has nothing new for us right now. It may be a leecher itself, so poll
                // its bitset for a while before giving the slot to somebody else.
                if (bits.isComplete()) {
                    break;
                }
                if (++idleRounds > mConfig.maxIdleRounds) {
                    return transferred ? PeerRegistry.Outcome.SUCCESS : PeerRegistry.Outcome.IDLE;
                }
                if (!sleep(mConfig.idleRefreshMs)) {
                    return PeerRegistry.Outcome.NEUTRAL;
                }
                view = mClient.fetchBitset(peer);
                if (!info.getFileHash().equalsIgnoreCase(view.fileHash)) {
                    return PeerRegistry.Outcome.WRONG_SWARM;
                }
                reference = view.bits;
                exchangePeers(peer);
                continue;
            }
            idleRounds = 0;

            boolean stored = false;
            try {
                byte[] data = mClient.fetchChunk(peer, chunkId, info.getChunkLength(chunkId));
                if (!storage.verifyChunk(chunkId, data)) {
                    Log.e(TAG, "Chunk " + chunkId + " from " + peer + " failed SHA-256 verification");
                    return PeerRegistry.Outcome.BAD_DATA;
                }
                stored = true;
                transferred = true;
                consecutiveErrors = 0;
                if (bits.isAvailable(chunkId)) {
                    continue; // endgame duplicate: the other copy won the race
                }
                storage.writeChunk(chunkId, data);
                bits.markAvailable(chunkId);
                bytes[0] += data.length;
                mDownloadedBytes.addAndGet(data.length);
                mDirty = true;
            } catch (PeerException e) {
                if (e.kind == PeerException.Kind.NOT_AVAILABLE) {
                    reference.clear(chunkId); // the peer lost it; do not ask again this session
                    continue;
                }
                if (e.kind == PeerException.Kind.NETWORK && ++consecutiveErrors <= MAX_INLINE_RETRIES
                        && mRunning && !mPaused) {
                    // A dropped connection is routine on Wi-Fi: reconnect to the same peer right
                    // away (the chunk goes back to the pool) before escalating to back-off.
                    Log.d(TAG, "Connection to " + peer + " dropped, reconnecting (" + consecutiveErrors + ")");
                    if (!sleep(100L * consecutiveErrors + mRandom.nextInt(100))) {
                        return PeerRegistry.Outcome.NEUTRAL;
                    }
                    continue;
                }
                throw e;
            } finally {
                if (!stored) {
                    bits.release(chunkId);
                }
            }
            notifyProgress(bits, false);
            exchangePeers(peer); // throttled; keeps discovery fresh during long transfers
        }

        if (bits.isComplete()) {
            notifyProgress(bits, true);
            maybeFinish();
            return PeerRegistry.Outcome.SUCCESS;
        }
        return PeerRegistry.Outcome.NEUTRAL;
    }

    /**
     * Peer exchange: ask a source which other devices it has seen serving and add them to the
     * registry. Discovery then spreads over the data connections we already have instead of
     * relying only on multicast, which scales to large fleets and works where mDNS is blocked.
     * Throttled per source so it adds almost no traffic.
     */
    private void exchangePeers(Peer source) {
        long now = System.currentTimeMillis();
        Long last = mLastExchange.put(source.key(), now);
        if (last != null && now - last < mConfig.pexIntervalMs) {
            mLastExchange.put(source.key(), last);
            return;
        }
        try {
            for (Peer learned : mClient.fetchPeers(source)) {
                mRegistry.upsert(learned);
            }
        } catch (PeerException e) {
            Log.d(TAG, "Peer exchange with " + source + " failed: " + e.kind);
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void notifyProgress(BitSetMetadata bits, boolean force) {
        long now = System.currentTimeMillis();
        long last = mLastProgressNotify.get();
        if (!force && now - last < 250) {
            return;
        }
        if (!force && !mLastProgressNotify.compareAndSet(last, now)) {
            return;
        }
        int completed = bits.availableCount();
        int total = bits.size();
        notifyListeners(l -> l.onChunkBatchLeeched(completed, total));
    }

    // ------------------------------------------------------------------ completion

    /**
     * Layer 2 verification and publication. Runs once when every chunk is present; if the
     * whole-file hash disagrees, only the corrupt chunks are re-fetched.
     */
    private void maybeFinish() {
        if (!mCompleting.compareAndSet(false, true)) {
            return;
        }
        StorageManager storage = mSession.storage();
        BitSetMetadata bits = mSession.bits();
        if (storage == null || bits == null) {
            mCompleting.set(false);
            return;
        }
        setState(State.VERIFYING, "Verifying whole-file SHA-256");
        try {
            if (storage.verifyWholeFile()) {
                storage.finalizeDownload();
                setState(State.COMPLETED, "Saved to " + storage.getFile());
                Log.d(TAG, "Leeching completed and verified: " + storage.getFile());
                notifyProgress(bits, true);
                notifyListeners(l -> l.onLeechingCompleted());
                return;
            }
            BitSet corrupt = storage.findCorruptChunks();
            if (corrupt.isEmpty() || mRepairRounds.incrementAndGet() > mConfig.maxRepairRounds) {
                fail("Whole-file SHA-256 mismatch");
                return;
            }
            for (int i = corrupt.nextSetBit(0); i >= 0; i = corrupt.nextSetBit(i + 1)) {
                bits.markMissing(i);
            }
            Log.w(TAG, "Re-fetching " + corrupt.cardinality() + " corrupt chunk(s)");
            mDirty = true;
            setState(State.DOWNLOADING, "Re-fetching " + corrupt.cardinality() + " corrupt chunk(s)");
            mCompleting.set(false);
        } catch (IOException e) {
            Log.e(TAG, "Verification failed", e);
            fail("Verification error: " + e.getMessage());
        }
    }

    private void fail(String reason) {
        if (mState == State.FAILED) {
            return;
        }
        Log.e(TAG, "Leeching failed: " + reason);
        setState(State.FAILED, reason);
        notifyListeners(l -> l.onLeechingFailed(reason));
    }

    // ------------------------------------------------------------------ listeners

    private void setState(State state, String detail) {
        synchronized (this) {
            if (state == mState && detail.equals(mStateDetail)) {
                return;
            }
            mState = state;
            mStateDetail = detail;
        }
        // Notify outside the lock so listeners can never deadlock against the state machine.
        notifyListeners(l -> l.onStateChanged(state, detail));
    }

    public void addSeekListener(SeekEventListener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    public void removeSeekListener(SeekEventListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    private interface ListenerCall {
        void accept(SeekEventListener listener);
    }

    private void notifyListeners(ListenerCall call) {
        for (SeekEventListener listener : mListeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                Log.e(TAG, "SeekEventListener callback failed", e);
            }
        }
    }
}
