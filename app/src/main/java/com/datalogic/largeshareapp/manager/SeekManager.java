package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.storage.StorageManager;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SeekManager {
    private static final String TAG = "LeechManager";
    private final int EXECUTOR_CAPABILITY = 5;
    private final int IN_FLIGHT_PEER_LEECHING_LIMIT = 5;
    private final int IN_FLIGHT_CHUNK_LEECHING_LIMIT = 5;
    private final AtomicInteger mInFlightPeerLeeching = new AtomicInteger(0);
    private final Random mRandom = new Random();
    private final ExecutorService mLeechExecutor = Executors.newFixedThreadPool(EXECUTOR_CAPABILITY);
    private StorageManager mStorageManager;
    private SharedData mSharedData = SharedData.getInstance();

    public SeekManager() {
    }

    public synchronized boolean startLeeching(final Peer peer) {
        if (peer == null) {
            return false;
        }

        if (getBudget() <= 0) {
            return false;
        }

        mLeechExecutor.submit(() -> {
            leechFromPeer(peer);
        });
        return true;
    }

    private void leechFromPeer(Peer peer) {
        try {
            AtomicBoolean isStop = new AtomicBoolean(false);
            if (SharedData.getInstance().instanceInformationMetadata == null) {
                InformationMetadata informationMetadata = leechInformationMetadata(peer, isStop);
                if (informationMetadata == null) {
                    Log.e(TAG, "Information metadata could not be updated, stop leeching");
                    return;
                }
                SharedData.getInstance().instanceInformationMetadata = informationMetadata;
                Log.d(TAG, "Information metadata updated: " + informationMetadata);

                mStorageManager = new StorageManager(informationMetadata);
            }

            BitSet referenceBits = leechBitSetMetadata(peer);
            BitSet currentBits = mSharedData.instanceBitSetMetadata.getAvailableChunks();
            BitSet bitsToFetch = SecureManager.findAvailableBits(currentBits, referenceBits);
//            CopyOnWriteArrayList<Integer> chunkIdsToFetch = availableBits
//                                                            .stream()
//                                                            .boxed()
//                                                            .collect(
//                                                                    Collectors.toCollection(CopyOnWriteArrayList::new));
//            int budget = Math.min(IN_FLIGHT_CHUNK_LEECHING_LIMIT, bitsetAvailableChunks.cardinality());
            int budget = 1;
            for (int i = 0; i < budget; i++) {
                mLeechExecutor.submit(() -> {
                    try {
                        leechChunkFromPeer(peer, isStop, bitsToFetch);
                    } catch (ExecutionException | InterruptedException | TimeoutException e) {
                        Log.d(TAG, "Error while leeching chunk from: " + peer, e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while leeching from: " + peer, e);
        }
    }

    private void leechChunkFromPeer(final Peer peer, AtomicBoolean isStop, BitSet bitsToFetch)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (isStop.get()) {
            Log.d(TAG, "Leeching stopped for peer: " + peer);
            return;
        }

        // If there are no more chunk IDs to fetch, try to fetch metadata again
        if (bitsToFetch.isEmpty()) {
            BitSet referenceBits = leechBitSetMetadata(peer);
            BitSet currentBits = mSharedData.instanceBitSetMetadata.getAvailableChunks();
            bitsToFetch = SecureManager.findAvailableBits(currentBits, referenceBits);

            if (bitsToFetch.isEmpty()) {
                Log.d(TAG, "No more chunks available from " + peer);
                peer.disconnect();
                isStop.set(true);
                return;
            }
        }

        // Get the next chunk ID to fetch
        int chunkId = getChunkToFetch(bitsToFetch);

        // Fetch chunk data from the peer
        Callable<byte[]> fetchChunkTask = () -> HttpClient.fetchChunk(peer.ip, peer.port, chunkId);
        Future<byte[]> fetchChunkFuture = mLeechExecutor.submit(fetchChunkTask);
        byte[] chunkData = fetchChunkFuture.get(HttpClient.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        if (chunkData == null || chunkData.length == 0) {
            Log.e(TAG, "Failed to fetch chunk from: " + peer);
            isStop.set(true);
            peer.disconnect();
            compensateChunk(chunkId, bitsToFetch);
            return;
        }

        // Write chunk data to storage
        Callable<Boolean> writeChunkTask = () -> mStorageManager.writeChunk(chunkId, chunkData);
        Future<Boolean> writeChunkFuture = mLeechExecutor.submit(writeChunkTask);
        Boolean wrote = writeChunkFuture.get(HttpClient.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        Log.d(TAG, "Wrote chunk " + chunkId + " to storage: " + wrote);
        if (!wrote) {
            Log.e(TAG, "Failed to write chunk " + chunkId + " to storage");
            compensateChunk(chunkId, bitsToFetch);
            return;
        }

        // Update the available chunks after successfully writing the chunk
        mSharedData.instanceBitSetMetadata.setAvailableChunk(chunkId, true);

        leechChunkFromPeer(peer, isStop, bitsToFetch);
    }

    private InformationMetadata leechInformationMetadata(final Peer peer, AtomicBoolean isStop)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (isStop.get()) {
            return null;
        }
        Callable<InformationMetadata> fetchMetadataTask = () -> HttpClient.fetchInformationMetadata(peer.ip, peer.port);
        Future<InformationMetadata> fetchMetadataFuture = mLeechExecutor.submit(fetchMetadataTask);
        InformationMetadata informationMetadata = fetchMetadataFuture.get(HttpClient.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        if (informationMetadata == null) {
            Log.e(TAG, "Failed to fetch metadata from: " + peer);
            return null;
        }

        return informationMetadata;
    }

    private BitSet leechBitSetMetadata(final Peer peer) {
        Callable<BitSet> fetchBitSetTask = () -> {
            return HttpClient.fetchBitSet(peer.ip, peer.port);
        };
        Future<BitSet> fetchBitSetFuture = mLeechExecutor.submit(fetchBitSetTask);
        try {
            return fetchBitSetFuture.get(HttpClient.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Log.e(TAG, "Failed to fetch bit set metadata", e);
        }
        return new BitSet();
    }

    public synchronized int getBudget() {
        return IN_FLIGHT_PEER_LEECHING_LIMIT - mInFlightPeerLeeching.get();
    }

    private synchronized int getChunkToFetch(BitSet bitsToFetch) {
        if (bitsToFetch == null || bitsToFetch.isEmpty()) {
            return -1;
        }
        BitSet availableBitsToFetch = SecureManager.findAvailableBits(
                mSharedData.instanceBitSetMetadata.getRequestedChunks(), bitsToFetch);
        CopyOnWriteArrayList<Integer> availableChunkIdsToFetch = availableBitsToFetch
                                        .stream()
                                        .boxed()
                                        .collect(
                                                Collectors.toCollection(CopyOnWriteArrayList::new));
        int randomIndex = mRandom.nextInt(availableChunkIdsToFetch.size());
        int chunkId = availableChunkIdsToFetch.get(randomIndex);

        bitsToFetch.set(chunkId, false);
        // TODO
        mSharedData.instanceBitSetMetadata.setRequestedChunk(chunkId, true);

        return chunkId;
    }

    private synchronized void compensateChunk(int chunkId, BitSet bitsToFetch) {
        mSharedData.instanceBitSetMetadata.setRequestedChunk(chunkId, false);
        // TODO
        bitsToFetch.set(chunkId, true);
    }
}
