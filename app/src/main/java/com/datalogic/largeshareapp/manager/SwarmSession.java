package com.datalogic.largeshareapp.manager;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.storage.StorageManager;

/**
 * The state of one share: manifest, chunk ownership map and file storage. It replaces the old
 * process-wide singleton, so sessions are isolated from each other, can be reset cleanly and can
 * coexist in tests.
 */
public class SwarmSession {
    private volatile InformationMetadata mInfo;
    private volatile BitSetMetadata mBits;
    private volatile StorageManager mStorage;

    public synchronized void attach(InformationMetadata info, BitSetMetadata bits, StorageManager storage) {
        mInfo = info;
        mBits = bits;
        mStorage = storage;
    }

    public boolean isReady() {
        return mInfo != null && mBits != null && mStorage != null;
    }

    public InformationMetadata info() {
        return mInfo;
    }

    public BitSetMetadata bits() {
        return mBits;
    }

    public StorageManager storage() {
        return mStorage;
    }

    public synchronized void close() {
        StorageManager storage = mStorage;
        mInfo = null;
        mBits = null;
        mStorage = null;
        if (storage != null) {
            storage.close();
        }
    }
}
