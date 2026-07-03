package com.datalogic.largeshareapp.model;

import com.datalogic.largeshareapp.manager.SecureManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.BitSet;

public class BitSetMetadata {
    private final BitSet availableChunks; // BitSet representing which chunks are available
    private final BitSet requestedChunks; // BitSet representing which chunks are requested
    private final int mSize; // Total number of chunks
    private final Object lockAvailableChunks = new Object();
    private final Object lockRequestedChunks = new Object();

    public BitSetMetadata(int size) {
        this.mSize = size;
        this.availableChunks = new BitSet(size);
        this.requestedChunks = new BitSet(size);
    }

    public void setAllAvailable() {
        synchronized (lockAvailableChunks) {
            availableChunks.set(0, mSize);
        }
    }

    public void setAllRequested() {
        synchronized (lockRequestedChunks) {
            requestedChunks.set(0, mSize);
        }
    }

    public void setAvailableChunk(int index, boolean value) {
        synchronized (lockAvailableChunks) {
            availableChunks.set(index, value);
        }
    }

    public void setRequestedChunk(int index, boolean value) {
        synchronized (lockRequestedChunks) {
            requestedChunks.set(index, value);
        }
    }

    public BitSet getAvailableChunks() {
        synchronized (lockAvailableChunks) {
            return (BitSet) availableChunks.clone();
        }
    }

    public BitSet getRequestedChunks() {
        synchronized (lockRequestedChunks) {
            return (BitSet) requestedChunks.clone();
        }
    }

    public static String toJsonString(BitSetMetadata bitSetMetadata) throws JSONException {
        if (bitSetMetadata == null) {
            return "{}";
        }
        JSONObject json = new JSONObject();
        String availableChunksStr = SecureManager.convertBitSetToString(bitSetMetadata.getAvailableChunks());
        json.put("availableChunks", availableChunksStr);
        return json.toString();
    }
}
