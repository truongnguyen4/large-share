package com.datalogic.largeshareapp.manager;

import com.datalogic.largeshareapp.model.Peer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Local cache of discovered peers plus their scheduling health. Working from this cache instead of
 * re-resolving the network is what keeps multicast traffic low (the disclosure's "local peer
 * caching"), and the per-peer back-off is what turns a flapping link into automatic reconnection
 * instead of either a hot retry loop or a permanently dropped peer.
 */
public class PeerRegistry {
    /** How a leech session with a peer ended. */
    public enum Outcome {
        /** Transferred data and ended normally. */
        SUCCESS,
        /** Reachable but had nothing we need right now. */
        IDLE,
        /** At capacity: retry soon, no penalty. */
        BUSY,
        /** Unreachable or dropped mid-transfer: exponential back-off. */
        NETWORK_FAILURE,
        /** Sent data that failed verification. */
        BAD_DATA,
        /** Failed the identity check. */
        REJECTED_IDENTITY,
        /** Serves a different payload than the one we are downloading. */
        WRONG_SWARM,
        /** Ended because we paused or stopped; says nothing about the peer. */
        NEUTRAL
    }

    public static final long BASE_BACKOFF_MS = 1_000L;
    public static final long MAX_BACKOFF_MS = 30_000L;
    public static final long IDLE_COOLDOWN_MS = 2_000L;
    public static final long WRONG_SWARM_MS = 60_000L;
    public static final long BAN_MS = 5 * 60_000L;
    public static final int BAD_DATA_BAN_THRESHOLD = 3;
    public static final int MAX_KNOWN_PEERS = 256;
    private static final double EWMA_ALPHA = 0.3;

    private static final class Entry {
        Peer peer;
        int sessions; // concurrent leech sessions currently using this peer
        int failures;
        int badData;
        long retryAt;
        long bannedUntil;
        long lastSeen;
        double bytesPerSec;
    }

    /**
     * Parallel sessions allowed against one peer. One chunk in flight per connection leaves the
     * link idle for a round trip between chunks; a few concurrent connections keep it full.
     */
    public static final int DEFAULT_SESSIONS_PER_PEER = 3;

    private final Map<String, Entry> mEntries = new HashMap<>();
    private final LongSupplier mClock;
    private final Random mRandom;
    private final int mMaxSessionsPerPeer;

    public PeerRegistry() {
        this(System::currentTimeMillis, new Random(), DEFAULT_SESSIONS_PER_PEER);
    }

    public PeerRegistry(int maxSessionsPerPeer) {
        this(System::currentTimeMillis, new Random(), maxSessionsPerPeer);
    }

    public PeerRegistry(LongSupplier clock, Random random) {
        this(clock, random, 1);
    }

    public PeerRegistry(LongSupplier clock, Random random, int maxSessionsPerPeer) {
        mClock = clock;
        mRandom = random;
        mMaxSessionsPerPeer = Math.max(1, maxSessionsPerPeer);
    }

    /** Adds a peer or refreshes its address. Returns true when it was not known before. */
    public synchronized boolean upsert(Peer peer) {
        long now = mClock.getAsLong();
        Entry entry = mEntries.get(peer.key());
        if (entry == null) {
            if (mEntries.size() >= MAX_KNOWN_PEERS && !evictOne()) {
                return false;
            }
            entry = new Entry();
            mEntries.put(peer.key(), entry);
            entry.peer = peer;
            entry.lastSeen = now;
            return true;
        }
        boolean moved = !entry.peer.ip.equals(peer.ip) || entry.peer.port != peer.port;
        entry.peer = peer;
        entry.lastSeen = now;
        if (moved) {
            // New address (roaming / DHCP): previous failures say nothing about the new one.
            entry.failures = 0;
            entry.retryAt = 0;
        }
        return false;
    }

    private boolean evictOne() {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
            if (e.getValue().sessions == 0 && e.getValue().lastSeen < oldest) {
                oldest = e.getValue().lastSeen;
                victim = e.getKey();
            }
        }
        if (victim == null) {
            return false;
        }
        mEntries.remove(victim);
        return true;
    }

    public synchronized void remove(String key) {
        Entry entry = mEntries.get(key);
        if (entry != null && entry.sessions == 0) {
            mEntries.remove(key);
        } else if (entry != null) {
            // Still in use: mark as unreachable so it is dropped when its session ends.
            entry.retryAt = Long.MAX_VALUE;
        }
    }

    /** Forgets every peer, e.g. after moving to another Wi-Fi Direct group (another subnet). */
    public synchronized void clear() {
        mEntries.clear();
    }

    public synchronized boolean contains(String key) {
        return mEntries.containsKey(key);
    }

    /**
     * Picks an eligible peer and counts one more session on it. Peers with fewer failures and higher
     * measured throughput are more likely; the randomness spreads load across the swarm.
     */
    public synchronized Peer acquire() {
        long now = mClock.getAsLong();
        List<Entry> eligible = new ArrayList<>();
        double total = 0;
        double maxSpeed = 0;
        for (Entry e : mEntries.values()) {
            if (isEligible(e, now)) {
                eligible.add(e);
                maxSpeed = Math.max(maxSpeed, e.bytesPerSec);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        double[] weights = new double[eligible.size()];
        for (int i = 0; i < weights.length; i++) {
            Entry e = eligible.get(i);
            double speedFactor = (maxSpeed <= 0 || e.bytesPerSec <= 0) ? 1.0 : 0.5 + e.bytesPerSec / maxSpeed;
            // Prefer peers we are not using yet, so sessions spread across the swarm first.
            weights[i] = speedFactor / (1 + e.failures) / (1 + 2 * e.sessions);
            total += weights[i];
        }
        double pick = mRandom.nextDouble() * total;
        Entry chosen = eligible.get(eligible.size() - 1);
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0) {
                chosen = eligible.get(i);
                break;
            }
        }
        chosen.sessions++;
        return chosen.peer;
    }

    private boolean isEligible(Entry e, long now) {
        return e.sessions < mMaxSessionsPerPeer && now >= e.retryAt && now >= e.bannedUntil;
    }

    public synchronized void release(Peer peer, Outcome outcome, long bytes, long millis) {
        Entry e = mEntries.get(peer.key());
        if (e == null) {
            return;
        }
        long now = mClock.getAsLong();
        e.sessions = Math.max(0, e.sessions - 1);
        if (e.retryAt == Long.MAX_VALUE) {
            if (e.sessions == 0) {
                mEntries.remove(peer.key()); // was reported lost while in use
            }
            return;
        }
        boolean alreadyPenalised = now < e.retryAt;
        if (alreadyPenalised && (outcome == Outcome.NETWORK_FAILURE || outcome == Outcome.BAD_DATA)) {
            // A sibling session on the same peer already reported this outage: count it once.
            if (outcome == Outcome.BAD_DATA) {
                e.badData++;
                if (e.badData >= BAD_DATA_BAN_THRESHOLD) {
                    e.bannedUntil = now + BAN_MS;
                }
            }
            return;
        }
        switch (outcome) {
            case SUCCESS:
                e.failures = 0;
                e.badData = 0;
                e.retryAt = 0;
                recordSpeed(e, bytes, millis);
                break;
            case IDLE:
                e.retryAt = now + IDLE_COOLDOWN_MS;
                recordSpeed(e, bytes, millis);
                break;
            case BUSY:
                e.retryAt = now + 1_000L + mRandom.nextInt(2_000);
                break;
            case NETWORK_FAILURE:
                if (bytes > 0) {
                    // It was delivering data before the link dropped: a transient hiccup, not a
                    // dead peer. Retry soon and do not let repeated hiccups escalate the back-off.
                    e.failures = 1;
                    recordSpeed(e, bytes, millis);
                } else {
                    e.failures++;
                }
                e.retryAt = now + backoff(e.failures);
                break;
            case BAD_DATA:
                e.failures++;
                e.badData++;
                if (e.badData >= BAD_DATA_BAN_THRESHOLD) {
                    e.bannedUntil = now + BAN_MS;
                } else {
                    e.retryAt = now + backoff(e.failures);
                }
                break;
            case REJECTED_IDENTITY:
                e.bannedUntil = now + BAN_MS;
                break;
            case WRONG_SWARM:
                e.retryAt = now + WRONG_SWARM_MS;
                break;
            case NEUTRAL:
            default:
                break;
        }
    }

    private static void recordSpeed(Entry e, long bytes, long millis) {
        if (bytes > 0 && millis > 0) {
            double sample = bytes * 1000.0 / millis;
            e.bytesPerSec = e.bytesPerSec <= 0 ? sample : EWMA_ALPHA * sample + (1 - EWMA_ALPHA) * e.bytesPerSec;
        }
    }

    /** Exponential back-off with +/-25% jitter so a fleet does not retry in lock-step. */
    long backoff(int failures) {
        long base = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << Math.min(failures - 1, 16));
        double jitter = 0.75 + mRandom.nextDouble() * 0.5;
        return (long) (base * jitter);
    }

    /** After the network came back every peer deserves an immediate fresh attempt. */
    public synchronized void resetBackoffs() {
        for (Entry e : mEntries.values()) {
            e.failures = 0;
            e.retryAt = 0;
        }
    }

    public synchronized int size() {
        return mEntries.size();
    }

    /** Leech sessions currently running, across all peers. */
    public synchronized int activeCount() {
        int n = 0;
        for (Entry e : mEntries.values()) {
            n += e.sessions;
        }
        return n;
    }

    /** Distinct peers currently being downloaded from. */
    public synchronized int activePeerCount() {
        int n = 0;
        for (Entry e : mEntries.values()) {
            if (e.sessions > 0) {
                n++;
            }
        }
        return n;
    }

    public synchronized int eligibleCount() {
        long now = mClock.getAsLong();
        int n = 0;
        for (Entry e : mEntries.values()) {
            if (isEligible(e, now)) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<Peer> snapshot() {
        List<Peer> peers = new ArrayList<>(mEntries.size());
        for (Entry e : mEntries.values()) {
            peers.add(e.peer);
        }
        return peers;
    }
}
