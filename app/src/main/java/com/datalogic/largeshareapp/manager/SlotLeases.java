package com.datalogic.largeshareapp.manager;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Member slots of a Wi-Fi Direct group owner. Android cannot cap the number of clients of a group,
 * so capacity is enforced one level up: members lease a slot by device id with periodic
 * heartbeats. The first {@code capacity} devices hold the slots; a lease that is not renewed
 * (the member graduated, left or vanished) expires and the slot goes to the next device.
 */
public class SlotLeases {
    private final int mCapacity;
    private final long mTtlMs;
    private final Map<String, Long> mLeases = new LinkedHashMap<>();

    public SlotLeases(int capacity, long ttlMs) {
        mCapacity = Math.max(1, capacity);
        mTtlMs = ttlMs;
    }

    /** Admits (or renews) a device. Returns false when every slot is held by someone else. */
    public synchronized boolean admit(String deviceId, long nowMs) {
        expire(nowMs);
        if (mLeases.containsKey(deviceId) || mLeases.size() < mCapacity) {
            mLeases.put(deviceId, nowMs);
            return true;
        }
        return false;
    }

    /** A member said goodbye (e.g. it is graduating): its slot is free at once. */
    public synchronized void release(String deviceId) {
        mLeases.remove(deviceId);
    }

    public synchronized int holders(long nowMs) {
        expire(nowMs);
        return mLeases.size();
    }

    public int capacity() {
        return mCapacity;
    }

    private void expire(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = mLeases.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > mTtlMs) {
                it.remove();
            }
        }
    }
}
