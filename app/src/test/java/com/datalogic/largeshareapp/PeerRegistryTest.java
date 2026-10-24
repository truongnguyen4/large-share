package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.datalogic.largeshareapp.manager.PeerRegistry;
import com.datalogic.largeshareapp.manager.PeerRegistry.Outcome;
import com.datalogic.largeshareapp.model.Peer;

import org.junit.Before;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class PeerRegistryTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private PeerRegistry registry;

    private static Peer peer(String name) {
        return new Peer("10.0.0.1", 8888, name, "_largeshare._tcp");
    }

    @Before
    public void setUp() {
        registry = new PeerRegistry(clock::get, new Random(42));
    }

    @Test
    public void duplicateDiscoveryIsSuppressedByTheCache() {
        assertTrue(registry.upsert(peer("a")));
        assertTrue(!registry.upsert(peer("a")));
        assertEquals(1, registry.size());
    }

    @Test
    public void failedPeerBacksOffExponentiallyButIsNeverForgotten() {
        registry.upsert(peer("a"));
        long previousWait = 0;
        for (int failure = 1; failure <= 6; failure++) {
            Peer p = registry.acquire();
            assertNotNull("peer must be retried eventually (failure " + failure + ")", p);
            registry.release(p, Outcome.NETWORK_FAILURE, 0, 0);

            assertNull("must not be retried immediately", registry.acquire());
            long waited = 0;
            while (registry.eligibleCount() == 0) {
                clock.addAndGet(100);
                waited += 100;
                assertTrue("back-off must be bounded", waited <= 40_000);
            }
            assertTrue("wait grows (or caps): " + waited + " vs " + previousWait,
                    waited + 400 >= previousWait || waited >= 20_000);
            previousWait = waited;
        }
        assertEquals(1, registry.size());
    }

    @Test
    public void peerThatWasDeliveringBeforeADropIsRetriedQuicklyEveryTime() {
        registry.upsert(peer("a"));
        for (int i = 0; i < 8; i++) {
            Peer p = registry.acquire();
            assertNotNull("round " + i, p);
            registry.release(p, Outcome.NETWORK_FAILURE, 512 * 1024, 400); // moved data, then dropped
            clock.addAndGet(1_500); // base back-off (+jitter) is at most 1.25 s
        }
        assertNotNull(registry.acquire());
    }

    @Test
    public void successResetsFailures() {
        registry.upsert(peer("a"));
        Peer p = registry.acquire();
        registry.release(p, Outcome.NETWORK_FAILURE, 0, 0);
        clock.addAndGet(60_000);
        p = registry.acquire();
        registry.release(p, Outcome.SUCCESS, 1_000_000, 1000);
        assertNotNull("healthy again: immediately eligible", registry.acquire());
    }

    @Test
    public void peerServingBadDataIsBannedAfterRepeatedOffences() {
        registry.upsert(peer("evil"));
        for (int i = 0; i < PeerRegistry.BAD_DATA_BAN_THRESHOLD; i++) {
            clock.addAndGet(60_000);
            Peer p = registry.acquire();
            assertNotNull(p);
            registry.release(p, Outcome.BAD_DATA, 0, 0);
        }
        clock.addAndGet(60_000); // a minute later: still banned (ban is 5 minutes)
        assertNull(registry.acquire());
        clock.addAndGet(PeerRegistry.BAN_MS);
        assertNotNull("ban expires", registry.acquire());
    }

    @Test
    public void identityFailureBansThePeer() {
        registry.upsert(peer("rogue"));
        registry.release(registry.acquire(), Outcome.REJECTED_IDENTITY, 0, 0);
        clock.addAndGet(120_000);
        assertNull(registry.acquire());
    }

    @Test
    public void busyPeerIsRetriedSoonWithoutPenalty() {
        registry.upsert(peer("busy"));
        registry.release(registry.acquire(), Outcome.BUSY, 0, 0);
        clock.addAndGet(3_500);
        assertNotNull(registry.acquire());
    }

    @Test
    public void networkRecoveryClearsAllBackoffs() {
        registry.upsert(peer("a"));
        registry.upsert(peer("b"));
        registry.release(registry.acquire(), Outcome.NETWORK_FAILURE, 0, 0);
        registry.release(registry.acquire(), Outcome.NETWORK_FAILURE, 0, 0);
        assertEquals(0, registry.eligibleCount());
        registry.resetBackoffs();
        assertEquals(2, registry.eligibleCount());
    }

    @Test
    public void aPeerIsNeverHandedToTwoWorkers() {
        registry.upsert(peer("a"));
        assertNotNull(registry.acquire());
        assertNull(registry.acquire());
        assertEquals(1, registry.activeCount());
    }

    @Test
    public void aPeerCanCarryAFewParallelSessionsButNoMore() {
        PeerRegistry multi = new PeerRegistry(clock::get, new Random(1), 3);
        multi.upsert(peer("a"));
        assertNotNull(multi.acquire());
        assertNotNull(multi.acquire());
        assertNotNull(multi.acquire());
        assertNull(multi.acquire());
        assertEquals(3, multi.activeCount());
        assertEquals(1, multi.activePeerCount());
    }

    @Test
    public void parallelSessionsSpreadOverDifferentPeersFirst() {
        PeerRegistry multi = new PeerRegistry(clock::get, new Random(7), 3);
        multi.upsert(peer("a"));
        multi.upsert(peer("b"));
        multi.upsert(peer("c"));
        int distinctInFirstThree = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            if (seen.add(multi.acquire().key())) {
                distinctInFirstThree++;
            }
        }
        assertTrue("sessions should prefer unused peers, got " + distinctInFirstThree, distinctInFirstThree >= 2);
    }

    @Test
    public void oneOutageSeenBySeveralSessionsIsCountedOnce() {
        PeerRegistry multi = new PeerRegistry(clock::get, new Random(3), 3);
        multi.upsert(peer("a"));
        Peer p1 = multi.acquire();
        Peer p2 = multi.acquire();
        Peer p3 = multi.acquire();
        multi.release(p1, Outcome.NETWORK_FAILURE, 0, 0);
        multi.release(p2, Outcome.NETWORK_FAILURE, 0, 0);
        multi.release(p3, Outcome.NETWORK_FAILURE, 0, 0);
        clock.addAndGet(1_300); // a single failure backs off at most 1.25 s
        assertNotNull("three sessions dropping together must not triple the back-off", multi.acquire());
    }

    @Test
    public void movedPeerGetsAFreshStart() {
        registry.upsert(peer("a"));
        registry.release(registry.acquire(), Outcome.NETWORK_FAILURE, 0, 0);
        assertNull(registry.acquire());
        registry.upsert(new Peer("10.0.0.99", 8888, "a", "_largeshare._tcp")); // roamed to a new IP
        assertNotNull(registry.acquire());
    }

    @Test
    public void knownPeerCacheIsBounded() {
        for (int i = 0; i < PeerRegistry.MAX_KNOWN_PEERS + 100; i++) {
            clock.addAndGet(1);
            registry.upsert(peer("p" + i));
        }
        assertEquals(PeerRegistry.MAX_KNOWN_PEERS, registry.size());
    }

    @Test
    public void lostPeerInUseIsDroppedWhenItsSessionEnds() {
        registry.upsert(peer("a"));
        Peer p = registry.acquire();
        registry.remove("a");
        registry.release(p, Outcome.NEUTRAL, 0, 0);
        assertEquals(0, registry.size());
    }
}
