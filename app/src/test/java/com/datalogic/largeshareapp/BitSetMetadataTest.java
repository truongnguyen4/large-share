package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.datalogic.largeshareapp.model.BitSetMetadata;

import org.junit.Test;

import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BitSetMetadataTest {

    @Test
    public void concurrentWorkersNeverClaimTheSameChunk() throws Exception {
        final int chunks = 5000;
        final int workers = 32;
        BitSetMetadata bits = new BitSetMetadata(chunks);
        BitSet everything = new BitSet();
        everything.set(0, chunks);

        Set<Integer> claimed = Collections.synchronizedSet(new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            final Random random = new Random(w);
            pool.execute(() -> {
                try {
                    start.await();
                    int id;
                    while ((id = bits.claimRandom(everything, random)) >= 0) {
                        assertTrue("chunk " + id + " claimed twice", claimed.add(id));
                        bits.markAvailable(id);
                    }
                } catch (InterruptedException ignored) {
                    // test aborted
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(chunks, claimed.size());
        assertTrue(bits.isComplete());
        assertEquals(chunks, bits.availableCount());
    }

    @Test
    public void releasedChunksCanBeClaimedAgain() {
        BitSetMetadata bits = new BitSetMetadata(3);
        BitSet all = new BitSet();
        all.set(0, 3);
        Random random = new Random(1);

        int a = bits.claimRandom(all, random);
        int b = bits.claimRandom(all, random);
        int c = bits.claimRandom(all, random);
        assertEquals(-1, bits.claimRandom(all, random)); // all in flight
        bits.release(b);
        assertEquals(b, bits.claimRandom(all, random));
        assertFalse(bits.isComplete());
        bits.markAvailable(a);
        bits.markAvailable(b);
        bits.markAvailable(c);
        assertTrue(bits.isComplete());
    }

    @Test
    public void endgameLetsASecondWorkerFetchAChunkStuckInFlight() {
        BitSetMetadata bits = new BitSetMetadata(2);
        BitSet all = new BitSet();
        all.set(0, 2);
        Random random = new Random(3);
        bits.markAvailable(0);
        int stuck = bits.claimRandom(all, random); // held by a worker whose peer vanished
        assertEquals(1, stuck);
        assertEquals(-1, bits.claimRandom(all, random));
        assertEquals(stuck, bits.claimEndgame(all, random));
        assertEquals("at most two fetchers per chunk", -1, bits.claimEndgame(all, random));

        bits.release(stuck); // one fetcher gave up: the chunk is still in flight with the other
        assertEquals(-1, bits.claimRandom(all, random));
        assertEquals(stuck, bits.claimEndgame(all, random));
        bits.markAvailable(stuck); // first copy wins
        bits.release(stuck);       // the loser's late failure changes nothing
        assertTrue(bits.isComplete());
        assertEquals(-1, bits.claimEndgame(all, random));
    }

    @Test
    public void bothFetchersFailingReturnsTheChunkToThePool() {
        BitSetMetadata bits = new BitSetMetadata(1);
        BitSet all = new BitSet();
        all.set(0);
        Random random = new Random(5);
        assertEquals(0, bits.claimRandom(all, random));
        assertEquals(0, bits.claimEndgame(all, random));
        bits.release(0);
        bits.release(0);
        assertEquals(0, bits.claimRandom(all, random));
    }

    @Test
    public void onlyChunksThePeerHasAreClaimed() {
        BitSetMetadata bits = new BitSetMetadata(10);
        BitSet peerHas = new BitSet();
        peerHas.set(4);
        peerHas.set(8);
        Random random = new Random(7);
        Set<Integer> got = new HashSet<>();
        int id;
        while ((id = bits.claimRandom(peerHas, random)) >= 0) {
            got.add(id);
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList(4, 8)), got);
    }

    @Test
    public void corruptChunkIsForgottenAndRefetched() {
        BitSetMetadata bits = new BitSetMetadata(2);
        bits.setAllAvailable();
        assertTrue(bits.isComplete());
        bits.markMissing(1);
        assertFalse(bits.isComplete());
        BitSet peer = new BitSet();
        peer.set(0, 2);
        assertEquals(1, bits.claimRandom(peer, new Random(1)));
    }

    @Test
    public void resumedChunksAreMarkedAvailable() {
        BitSetMetadata bits = new BitSetMetadata(5);
        BitSet resumed = new BitSet();
        resumed.set(1);
        resumed.set(3);
        resumed.set(99); // outside the swarm: ignored
        bits.restoreAvailable(resumed);
        assertEquals(2, bits.availableCount());
        assertTrue(bits.isAvailable(3));
        assertFalse(bits.isAvailable(4));
    }
}
