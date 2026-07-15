package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.storage.StorageManager;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SeekManager {
    private static final String TAG = "LeechManager";
    private static final int PROGRESS_10_CHUNK_COMPLETED = 1;
    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 5;
    private final int IN_FLIGHT_CHUNK_LEECHING_LIMIT = 1;

    public interface LeechEventListener {
        void onMetadataReady(InformationMetadata metadata);

        void onChunkBatchLeeched(int completed, int total);

        void onLeechingCompleted();
    }

    private final AtomicInteger mInFlightPeerLeeching = new AtomicInteger(0);
    private final Random mRandom = new Random();
    private final ExecutorService mPeerExecutor =
            Executors.newFixedThreadPool(IN_FLIGHT_PEER_LEECHING_LIMIT);
    private final ExecutorService mChunkExecutor =
            Executors.newFixedThreadPool(IN_FLIGHT_PEER_LEECHING_LIMIT * IN_FLIGHT_CHUNK_LEECHING_LIMIT);

    private final SharedData mSharedData = SharedData.getInstance();

    private final Object mClaimLock = new Object();
    private final Object mInitLock = new Object();

    private final AtomicBoolean mAllComplete = new AtomicBoolean(false);
    private final AtomicInteger mCompletedChunks = new AtomicInteger(0);

    private volatile InformationMetadata mInformationMetadata;
    private volatile BitSetMetadata mBitSetMetadata;
    private volatile StorageManager mStorageManager;
    private volatile int mTotalChunks;
    private volatile LeechEventListener mLeechEventListener;

    public SeekManager() {}

    public void setLeechEventListener(LeechEventListener listener) {
        this.mLeechEventListener = listener;
    }

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
            if (!ensureInitialized(peer)) {
                Log.e(TAG, "Could not initialize metadata from " + peer + ", stop leeching");
                return;
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
                PerformanceTracker.startRecord("refreshBitSet", System.currentTimeMillis());
                referenceBits = leechBitSetMetadata(peer);
                PerformanceTracker.endRecord("refreshBitSet", System.currentTimeMillis());
                chunkId = claimChunk(referenceBits);
                if (chunkId < 0) {
                    if (isDownloadComplete()) {
                        notifySeekProgress(listener -> listener.onLeechingCompleted());
                    }
                    return;
                }
            }
            PerformanceTracker.startRecord("leechChunk-" + chunkId , System.currentTimeMillis());

            if (!performFetchVerifyWriteChunk(peer, chunkId)) {
                // Release the claim so another peer/worker can retry this chunk, then drop this peer.
                compensateChunk(chunkId);
                isStop.set(true);
                peer.disconnect();
                Log.e(TAG, "Stopping peer " + peer + " after failure on chunk " + chunkId);
                return;
            }

            Log.d(TAG, "Successfully leeched chunk " + chunkId + " from " + peer.ip);
            onSeekChunkSuccessful(chunkId);

            if (mAllComplete.get()) {
                return;
            }
            PerformanceTracker.endRecord("leechChunk-" + chunkId, System.currentTimeMillis());
        }
    }

    private void onSeekChunkSuccessful(int chunkId) {
        mBitSetMetadata.setAvailableChunk(chunkId, true);
        int completed = mCompletedChunks.incrementAndGet();
        int total = mTotalChunks;

        boolean isUpdate = completed % PROGRESS_10_CHUNK_COMPLETED == 0;
        if (isUpdate) {
            notifySeekProgress(listener -> listener.onChunkBatchLeeched(completed, total));
        }

        boolean complete = completed >= total;
        if (complete) {
            notifySeekProgress(listener -> listener.onLeechingCompleted());
            notifySeekProgress(listener -> listener.onChunkBatchLeeched(completed, total));
        }
    }

    private void notifySeekProgress(Consumer<LeechEventListener> callback) {
        LeechEventListener listener = mLeechEventListener;
        if (listener != null) {
            try {
                callback.accept(listener);
            } catch (Exception e) {
                Log.e(TAG, "LeechEventListener callback failed", e);
            }
        }
    }

    private int claimChunk(BitSet referenceBits) {
        final BitSetMetadata currentBitSetMetadata = mBitSetMetadata;
        if (referenceBits == null || referenceBits.isEmpty() || currentBitSetMetadata == null) {
            return -1;
        }

        synchronized (mClaimLock) {
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
    }

    private void compensateChunk(int chunkId) {
        if (chunkId < 0 || mBitSetMetadata == null) {
            return;
        }
        mBitSetMetadata.setRequestedChunk(chunkId, false);
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

            PerformanceTracker.startRecord("verifyChunk-" + chunkId, System.currentTimeMillis());
            if (!mStorageManager.verifyChunk(chunkId, chunkData)) {
                Log.e(TAG, "Chunk " + chunkId + " failed hash verification from " + peer);
                return false;
            }
            PerformanceTracker.endRecord("verifyChunk-" + chunkId, System.currentTimeMillis());

            PerformanceTracker.startRecord("writeChunk-" + chunkId, System.currentTimeMillis());
            if (!mStorageManager.writeChunk(chunkId, chunkData)) {
                Log.e(TAG, "Failed to write chunk " + chunkId + " from " + peer);
                return false;
            }
            PerformanceTracker.endRecord("writeChunk-" + chunkId, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch/write chunk " + chunkId + " from " + peer, e);
            return false;
        }
    }

    private boolean isDownloadComplete() {
        final BitSetMetadata meta = mBitSetMetadata;
        if (meta == null || mTotalChunks <= 0) {
            return false;
        }
        return meta.getAvailableChunks().cardinality() >= mTotalChunks;
    }

    /**
     * One-time, thread-safe initialization of the information metadata, chunk bitset and storage.
     * The first peer thread to arrive fetches the metadata; all others reuse it.
     */
    private boolean ensureInitialized(final Peer peer) {
        if (mInformationMetadata != null && mBitSetMetadata != null && mStorageManager != null) {
            return true;
        }

        boolean initializedNow = false;
        synchronized (mInitLock) {
            if (mInformationMetadata == null || mBitSetMetadata == null || mStorageManager == null) {
                InformationMetadata metadata = mSharedData.instanceInformationMetadata;
                if (metadata == null) {
                    metadata = leechInformationMetadata(peer);
                    if (metadata == null) {
                        return false;
                    }
                }

                StorageManager storageManager;
                try {
                    storageManager = new StorageManager(metadata);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to initialize storage for " + metadata.getFileShared(), e);
                    return false;
                }

                int totalChunks = metadata.getChunkHashes().length;
                BitSetMetadata bitSetMetadata = mSharedData.instanceBitSetMetadata;
                if (bitSetMetadata == null) {
                    bitSetMetadata = new BitSetMetadata(totalChunks);
                }

                // Publish locally (correct visibility for workers) and to SharedData (for others).
                mStorageManager = storageManager;
                mTotalChunks = totalChunks;
                mBitSetMetadata = bitSetMetadata;
                mInformationMetadata = metadata;
                mSharedData.instanceBitSetMetadata = bitSetMetadata;
                mSharedData.instanceInformationMetadata = metadata;
                initializedNow = true;

                Log.d(TAG, "Initialized leeching for " + totalChunks + " chunks: " + metadata);
            }
        }

        boolean ready = mInformationMetadata != null && mBitSetMetadata != null && mStorageManager != null;
        // Fire the callback outside the init lock to avoid holding it across coordinator work.
        if (initializedNow && ready) {
            LeechEventListener listener = mLeechEventListener;
            if (listener != null) {
                try {
                    listener.onMetadataReady(mInformationMetadata);
                } catch (Exception e) {
                    Log.e(TAG, "onMetadataReady callback failed", e);
                }
            }
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
}
