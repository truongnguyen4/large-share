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

public class SeekManager {
    private static final String TAG = "LeechManager";

    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 5;
    private final int IN_FLIGHT_CHUNK_LEECHING_LIMIT = 5;

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

    private volatile InformationMetadata mInformationMetadata;
    private volatile BitSetMetadata mBitSetMetadata;
    private volatile StorageManager mStorageManager;
    private volatile int mTotalChunks;

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
                if (isDownloadComplete()) {
                    mAllComplete.set(true);
                }
                // Nothing left to claim from this peer's current view. Refresh one more time
                // then give up on this peer if there is still nothing for us.
                referenceBits = leechBitSetMetadata(peer);
                chunkId = claimChunk(referenceBits);
                if (chunkId < 0) {
                    if (isDownloadComplete()) {
                        mAllComplete.set(true);
                    }
                    return;
                }
            }

            if (!performFetchVerifyWriteChunk(peer, chunkId)) {
                // Release the claim so another peer/worker can retry this chunk, then drop this peer.
                compensateChunk(chunkId);
                isStop.set(true);
                peer.disconnect();
                Log.e(TAG, "Stopping peer " + peer + " after failure on chunk " + chunkId);
                return;
            }

            // Successfully persisted: publish progress to the shared metadata.
            mBitSetMetadata.setAvailableChunk(chunkId, true);
            Log.d(TAG, "Leeched chunk " + chunkId + " from " + peer);

            if (isDownloadComplete()) {
                mAllComplete.set(true);
                return;
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
            byte[] chunkData = HttpClient.fetchChunk(peer.ip, peer.port, chunkId);
            if (chunkData.length == 0) {
                Log.e(TAG, "Empty chunk " + chunkId + " from " + peer);
                return false;
            }
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

                Log.d(TAG, "Initialized leeching for " + totalChunks + " chunks: " + metadata);
            }
        }
        return mInformationMetadata != null && mBitSetMetadata != null && mStorageManager != null;
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
