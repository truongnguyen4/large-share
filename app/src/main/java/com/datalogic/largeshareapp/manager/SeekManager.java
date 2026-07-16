package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.storage.StorageManager;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SeekManager {
    private static final String TAG = "LeechManager";
    private final int PROGRESS_CHUNK_COMPLETED = 2;
    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 5;
    private final int IN_FLIGHT_CHUNK_LEECHING_LIMIT = 1;
    private final ExecutorService mPeerExecutor = Executors.newFixedThreadPool(
            IN_FLIGHT_PEER_LEECHING_LIMIT);
    private final ExecutorService mChunkExecutor = Executors.newFixedThreadPool(
            IN_FLIGHT_PEER_LEECHING_LIMIT * IN_FLIGHT_CHUNK_LEECHING_LIMIT);

    private final Random mRandom = new Random();

    private final AtomicInteger mInFlightPeerLeeching = new AtomicInteger(0);
    private final AtomicBoolean mAllComplete = new AtomicBoolean(false);
    private final AtomicInteger mDownloadedChunks = new AtomicInteger(0);

    private volatile StorageManager mStorageManager;
    private volatile int mTotalChunks = 0;

    private final Set<SeekEventListener> mSeekEventListeners = new CopyOnWriteArraySet<>();


    public SeekManager() {}

    public synchronized boolean startLeeching(final Peer peer) {
        if (peer == null) {
            return false;
        }
        if (mAllComplete.get()) {
            return false;
        }
        if (getBudget() <= 0) {
            return false;
        }

        // Reserve the slot up-front so concurrent callers see an accurate budget.
        mInFlightPeerLeeching.incrementAndGet();
        try {
            mPeerExecutor.submit(() -> leechFromPeer(peer));
        } catch (RuntimeException e) {
            mInFlightPeerLeeching.decrementAndGet();
            Log.e(TAG, "Failed to submit leech task for: " + peer, e);
            return false;
        }
        return true;
    }

    private void leechFromPeer(Peer peer) {
        final AtomicBoolean isStop = new AtomicBoolean(false);
        try {
            if (SharedData.getInstance().instanceInformationMetadata == null
                    || SharedData.getInstance().instanceBitSetMetadata == null
                    || mStorageManager == null) {
                if (!initialize(peer)) {
                    Log.e(TAG, "Could not initialize metadata from " + peer + ", stop leeching");
                    return;
                }
            }

            if (mAllComplete.get()) {
                return;
            }

            final CountDownLatch done = new CountDownLatch(IN_FLIGHT_CHUNK_LEECHING_LIMIT);
            for (int i = 0; i < IN_FLIGHT_CHUNK_LEECHING_LIMIT; i++) {
                mChunkExecutor.submit(() -> {
                    try {
                        leechChunkLoop(peer, isStop);
                    } catch (Exception e) {
                        Log.e(TAG, "Chunk worker failed for peer: " + peer, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            notifyToSeekListener(listener -> listener.onLeechingPeerStopped(peer));

        } catch (Exception e) {
            Log.e(TAG, "Error while leeching from: " + peer, e);
            Thread.currentThread().interrupt();
        } finally {
            peer.disconnect();
            mInFlightPeerLeeching.decrementAndGet();
        }
    }

    private void leechChunkLoop(final Peer peer, final AtomicBoolean isStop) {
        BitSet referenceBits = leechBitSetMetadata(peer); // which chunks this peer can serve

        while (!isStop.get() && !mAllComplete.get()) {
            int chunkId = claimChunk(referenceBits);
            if (chunkId < 0) {
                // Nothing left to claim from this peer's current view. Refresh one more time
                // then give up on this peer if there is still nothing for us.
                referenceBits = leechBitSetMetadata(peer);
                chunkId = claimChunk(referenceBits);
                if (chunkId < 0) {
                    if (isDownloadComplete()) {
                        mAllComplete.set(true);
                        notifyToSeekListener(listener -> listener.onLeechingPeerStopped(peer));
                        Log.d(TAG, "Leeching complete. Stopping peer " + peer);
                    }
                    return;
                }
            }

            if (!performFetchVerifyWriteChunk(peer, chunkId)) {
                // Release the claim so another peer/worker can retry this chunk, then drop this peer.
                compensateChunk(chunkId);
                isStop.set(true);
                peer.disconnect();
                notifyToSeekListener(listener -> listener.onLeechingPeerStopped(peer));
                Log.d(TAG, "Stopping peer " + peer + " after failure on chunk " + chunkId);
                return;
            }

            Log.d(TAG, "Successfully leeched chunk " + chunkId + " from " + peer.ip);
            onSeekChunkSuccessful(chunkId);
            
            if (isDownloadComplete()) {
                mAllComplete.set(true);
                notifyToSeekListener(listener -> listener.onLeechingCompleted());
                return;
            }
        }
    }

    private void onSeekChunkSuccessful(int chunkId) {
        SharedData.getInstance().instanceBitSetMetadata.setAvailableChunk(chunkId, true);
        int completed = mDownloadedChunks.incrementAndGet();
        int total = mTotalChunks;

        boolean isUpdate = completed % PROGRESS_CHUNK_COMPLETED == 0;
        boolean isComplete = completed >= total;
        if (isUpdate || isComplete) {
            notifyToSeekListener(listener -> listener.onChunkBatchLeeched(completed, total));
        }
    }

    private synchronized int claimChunk(BitSet referenceBits) {
        final BitSetMetadata currentBitSetMetadata = SharedData.getInstance().instanceBitSetMetadata;
        if (referenceBits == null || referenceBits.isEmpty() || currentBitSetMetadata == null) {
            return -1;
        }

        BitSet candidates = (BitSet) referenceBits.clone();
        candidates.andNot(currentBitSetMetadata.getAvailableChunks()); // drop chunks we already have
        candidates.andNot(currentBitSetMetadata.getRequestedChunks()); // drop chunks already in-flight

        int count = candidates.cardinality();
        if (count == 0) {
            // No claimable chunks available.
            return -1;
        }

        // Pick a random claimable chunk
        int target = mRandom.nextInt(count);
        int chunkId = candidates.nextSetBit(0);
        for (int i = 0; i < target; i++) {
            chunkId = candidates.nextSetBit(chunkId + 1);
        }

        currentBitSetMetadata.setRequestedChunk(chunkId, true);
        return chunkId;
    }

    private void compensateChunk(int chunkId) {
        if (chunkId < 0 || SharedData.getInstance().instanceBitSetMetadata == null) {
            return;
        }
        SharedData.getInstance().instanceBitSetMetadata.setRequestedChunk(chunkId, false);
    }

    private boolean performFetchVerifyWriteChunk(final Peer peer, int chunkId) {
        try {
            PerformanceTracker.startRecord("fetchChunk-" + chunkId, System.currentTimeMillis());
            byte[] chunkData = HttpClient.fetchChunk(peer.ip, peer.port, chunkId);
            if (chunkData.length == 0) {
                Log.e(TAG, "Empty chunk " + chunkId + " from " + peer);
                return false;
            }
            PerformanceTracker.endRecord("fetchChunk-" + chunkId, System.currentTimeMillis());

            if (!mStorageManager.verifyChunk(chunkId, chunkData)) {
                Log.e(TAG, "Chunk " + chunkId + " failed hash verification from " + peer);
                return false;
            }

            if (!mStorageManager.writeChunk(chunkId, chunkData)) {
                Log.e(TAG, "Failed to write chunk " + chunkId + " from " + peer);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch/write chunk " + chunkId + " from " + peer, e);
            return false;
        }
    }

    private boolean isDownloadComplete() {
        final BitSetMetadata bitSetMetadata = SharedData.getInstance().instanceBitSetMetadata;
        if (bitSetMetadata == null || mTotalChunks <= 0) {
            return false;
        }
        return bitSetMetadata.getAvailableChunks().cardinality() >= mTotalChunks;
    }

    private synchronized boolean initialize(final Peer peer) {
        SharedData sharedData = SharedData.getInstance();
        if (sharedData.instanceInformationMetadata == null
                || sharedData.instanceBitSetMetadata == null
                || mStorageManager == null) {
            InformationMetadata informationMetadata = leechInformationMetadata(peer);
            if (informationMetadata == null) {
                return false;
            }
            sharedData.instanceInformationMetadata = informationMetadata;

            StorageManager storageManager;
            try {
                storageManager = new StorageManager(informationMetadata);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to initialize storage for " + informationMetadata.getFileShared(), e);
                return false;
            }

            int totalChunks = informationMetadata.getChunkHashes().length;
            if (sharedData.instanceBitSetMetadata == null) {
                sharedData.instanceBitSetMetadata = new BitSetMetadata(totalChunks);
            }

            mStorageManager = storageManager;
            mTotalChunks = totalChunks;
            Log.d(TAG, "Initialized leeching for " + totalChunks + " chunks: " + informationMetadata);
        }

        boolean ready = sharedData.instanceInformationMetadata != null
                            && sharedData.instanceBitSetMetadata != null
                            && mStorageManager != null;
        if (ready) {
            notifyToSeekListener(listener -> listener.onMetadataReady(sharedData.instanceInformationMetadata));
        }
        return ready;
    }

    private InformationMetadata leechInformationMetadata(final Peer peer) {
        try {
            InformationMetadata metadata = HttpClient.fetchInformationMetadata(peer.ip, peer.port);
            if (metadata == null) {
                Log.e(TAG, "Failed to fetch metadata from: " + peer);
            }
            return metadata;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching metadata from: " + peer, e);
            return null;
        }
    }

    private BitSet leechBitSetMetadata(final Peer peer) {
        try {
            BitSet bitSet = HttpClient.fetchBitSet(peer.ip, peer.port);
            return bitSet != null ? bitSet : new BitSet();
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch bit set metadata from: " + peer, e);
            return new BitSet();
        }
    }

    public synchronized int getBudget() {
        return IN_FLIGHT_PEER_LEECHING_LIMIT - mInFlightPeerLeeching.get();
    }

    /** Stops accepting new work and shuts down both thread layers. */
    public void stop() {
        mAllComplete.set(true);
        mChunkExecutor.shutdownNow();
        mPeerExecutor.shutdownNow();
    }

    public interface SeekEventListener {
        default void onMetadataReady(InformationMetadata metadata) {}
        void onChunkBatchLeeched(int completed, int total);
        default void onLeechingPeerStopped(Peer peer) {}
        default void onLeechingCompleted() {}
    }

    public void addSeekListener(SeekEventListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to add null SeekEventListener. Ignored.");
            return;
        }
        mSeekEventListeners.add(listener);
    }

    public void removeSeekListener(SeekEventListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to remove null SeekEventListener. Ignored.");
            return;
        }
        mSeekEventListeners.remove(listener);
    }

    private interface SeekEventListenerConsumer {
        void accept(SeekEventListener listener);
    }

    private void notifyToSeekListener(SeekEventListenerConsumer callback) {
        for (SeekEventListener listener : mSeekEventListeners) {
            try {
                callback.accept(listener);
            } catch (Exception e) {
                Log.e(TAG, "SeekEventListener callback failed", e);
            }
        }
    }
}
