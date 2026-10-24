package com.datalogic.largeshareapp.model;

import com.datalogic.largeshareapp.manager.SecureManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.BitSet;
import java.util.Random;

/**
 * Thread-safe ownership map of the chunks of one swarm: which chunks are verified and on disk
 * ("available") and which are currently being fetched ("requested"). All mutations take a single
 * lock so that claim / release / complete are atomic with respect to each other.
 */
public class BitSetMetadata {
    public static final String AVAILABLE_CHUNKS_KEY = "availableChunks";
    public static final String FILE_HASH_KEY = "fileHash";

    private final BitSet availableChunks;
    private final BitSet requestedChunks;
    /** In-flight chunks that a second worker is also fetching (endgame). */
    private final BitSet duplicatedChunks;
    private final int mSize;

    public BitSetMetadata(int size) {
        this.mSize = size;
        this.availableChunks = new BitSet(size);
        this.requestedChunks = new BitSet(size);
        this.duplicatedChunks = new BitSet(size);
    }

    public int size() {
        return mSize;
    }

    public synchronized void setAllAvailable() {
        availableChunks.set(0, mSize);
        requestedChunks.clear();
        duplicatedChunks.clear();
    }

    /** Marks resumed chunks as available; indexes outside the swarm are ignored. */
    public synchronized void restoreAvailable(BitSet restored) {
        if (restored == null) {
            return;
        }
        for (int i = restored.nextSetBit(0); i >= 0 && i < mSize; i = restored.nextSetBit(i + 1)) {
            availableChunks.set(i);
        }
    }

    /**
     * Atomically picks a random chunk that the reference peer has, that we do not have and that no
     * other worker is fetching, and marks it as requested. Returns -1 if there is none.
     */
    public synchronized int claimRandom(BitSet reference, Random random) {
        if (reference == null || reference.isEmpty()) {
            return -1;
        }
        BitSet candidates = (BitSet) reference.clone();
        candidates.andNot(availableChunks);
        candidates.andNot(requestedChunks);
        int chunk = pickRandom(candidates, random);
        if (chunk >= 0) {
            requestedChunks.set(chunk);
        }
        return chunk;
    }

    /**
     * Endgame: every chunk the reference peer could give us is already in flight. Picks one that
     * exactly one worker is fetching and lets a second worker fetch it too, so the last chunks are
     * not held hostage by a slow or vanished peer. Whichever copy arrives first is kept. Returns
     * -1 if there is none.
     */
    public synchronized int claimEndgame(BitSet reference, Random random) {
        if (reference == null || reference.isEmpty()) {
            return -1;
        }
        BitSet candidates = (BitSet) reference.clone();
        candidates.andNot(availableChunks);
        candidates.and(requestedChunks);
        candidates.andNot(duplicatedChunks);
        int chunk = pickRandom(candidates, random);
        if (chunk >= 0) {
            duplicatedChunks.set(chunk);
        }
        return chunk;
    }

    private int pickRandom(BitSet candidates, Random random) {
        int count = candidates.cardinality();
        if (count == 0) {
            return -1;
        }
        int skip = random.nextInt(count);
        int chunk = candidates.nextSetBit(0);
        for (int i = 0; i < skip; i++) {
            chunk = candidates.nextSetBit(chunk + 1);
        }
        return chunk < mSize ? chunk : -1;
    }

    /** Gives an in-flight chunk back to the pool after a failed attempt (one fetcher fewer). */
    public synchronized void release(int index) {
        if (index >= 0 && index < mSize) {
            if (duplicatedChunks.get(index)) {
                duplicatedChunks.clear(index); // the other fetcher is still on it
            } else {
                requestedChunks.clear(index);
            }
        }
    }

    /** A chunk was fetched, verified and written: it becomes available (and no longer in flight). */
    public synchronized void markAvailable(int index) {
        if (index >= 0 && index < mSize) {
            availableChunks.set(index);
            requestedChunks.clear(index);
            duplicatedChunks.clear(index);
        }
    }

    /** A chunk turned out to be corrupt on disk: forget it so it is fetched again. */
    public synchronized void markMissing(int index) {
        if (index >= 0 && index < mSize) {
            availableChunks.clear(index);
            requestedChunks.clear(index);
            duplicatedChunks.clear(index);
        }
    }

    public synchronized boolean isAvailable(int index) {
        return index >= 0 && index < mSize && availableChunks.get(index);
    }

    public synchronized int availableCount() {
        return availableChunks.cardinality();
    }

    public synchronized boolean isComplete() {
        return mSize > 0 && availableChunks.cardinality() >= mSize;
    }

    public synchronized BitSet getAvailableChunks() {
        return (BitSet) availableChunks.clone();
    }

    public synchronized BitSet getRequestedChunks() {
        return (BitSet) requestedChunks.clone();
    }

    public static String toJsonString(BitSetMetadata bitSetMetadata, String fileHash) throws JSONException {
        if (bitSetMetadata == null) {
            return "{}";
        }
        JSONObject json = new JSONObject();
        json.put(AVAILABLE_CHUNKS_KEY, SecureManager.convertBitSetToString(bitSetMetadata.getAvailableChunks()));
        json.put(FILE_HASH_KEY, fileHash == null ? "" : fileHash);
        return json.toString();
    }
}
