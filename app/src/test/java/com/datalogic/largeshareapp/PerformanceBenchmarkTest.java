package com.datalogic.largeshareapp;

import static org.junit.Assume.assumeTrue;

import com.datalogic.largeshareapp.SwarmHarness.Node;
import com.datalogic.largeshareapp.SwarmHarness.Swarm;
import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.security.Identity;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance measurements, not pass/fail tests. Skipped unless LS_BENCH is set. Prints "[bench]"
 * lines. Runs on loopback, so absolute transfer speeds reflect CPU/IO limits, not Wi-Fi.
 */
public class PerformanceBenchmarkTest {
    private static final int MB = 1024 * 1024;

    private static void out(String fmt, Object... args) {
        System.out.println("[bench] " + String.format(java.util.Locale.US, fmt, args));
    }

    @Test(timeout = 900_000)
    public void benchmarks() throws Exception {
        assumeTrue("LS_BENCH not set", System.getenv("LS_BENCH") != null);
        File dir = TestFiles.tempDir("bench");
        try {
            hashingAndVerification(dir);
            hmacCost();
            chunkClaimContention(5_000);
            chunkClaimContention(200_000);
            serverRequestRate(dir);
            if (System.getenv("LS_BENCH_LOOPBACK") != null) {
                swarmScaling(dir);
            }
            bandwidthLimitedScaling(dir);
        } finally {
            TestFiles.deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------ CPU / IO costs

    private void hashingAndVerification(File dir) throws Exception {
        File file = TestFiles.randomFile(dir, "h.bin", 256L * MB, 1);
        SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB); // warm page cache + JIT
        long t0 = System.nanoTime();
        InformationMetadata meta = SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB);
        double manifestS = (System.nanoTime() - t0) / 1e9;
        out("manifest (per-chunk + whole-file SHA-256, one pass): 256 MB in %.2f s = %.0f MB/s", manifestS, 256 / manifestS);

        try (StorageManager sm = StorageManager.forSource(meta, file)) {
            t0 = System.nanoTime();
            sm.verifyWholeFile();
            double layer2 = (System.nanoTime() - t0) / 1e9;
            out("layer-2 whole-file verify: 256 MB in %.2f s = %.0f MB/s", layer2, 256 / layer2);

            byte[] chunk = sm.readChunk(0);
            int n = 400;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sm.verifyChunk(0, chunk);
            }
            double perChunkMs = (System.nanoTime() - t0) / 1e6 / n;
            out("layer-1 verify of one 512 KB chunk: %.2f ms (%.0f MB/s)", perChunkMs, 0.5 / (perChunkMs / 1000));
        }
    }

    private void hmacCost() {
        Identity a = new Identity("device-a", "fleet");
        Identity b = new Identity("device-b", "fleet");
        int n = 50_000;
        for (int i = 0; i < 5_000; i++) { // warm-up
            Identity.SignedRequest r = a.signRequest("GET", "/chunk?id=" + i);
            b.verifyRequest(r.headers.get(Identity.HDR_ID), r.headers.get(Identity.HDR_TS),
                    r.headers.get(Identity.HDR_NONCE), r.headers.get(Identity.HDR_SIG), "GET", "/chunk?id=" + i, false);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            Identity.SignedRequest r = a.signRequest("GET", "/chunk?id=" + i);
            Map<String, String> h = r.headers;
            b.verifyRequest(h.get(Identity.HDR_ID), h.get(Identity.HDR_TS), h.get(Identity.HDR_NONCE),
                    h.get(Identity.HDR_SIG), "GET", "/chunk?id=" + i, false);
            b.signResponse(r.nonce, 200, 524288);
        }
        double us = (System.nanoTime() - t0) / 1e3 / n;
        out("identity check per chunk request (sign + verify + response MAC): %.1f us", us);
    }

    private void chunkClaimContention(int chunks) throws Exception {
        int threads = 16;
        BitSetMetadata bits = new BitSetMetadata(chunks);
        BitSet all = new BitSet();
        all.set(0, chunks);
        CountDownLatch done = new CountDownLatch(threads);
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            Random r = new Random(t);
            new Thread(() -> {
                int id;
                while ((id = bits.claimRandom(all, r)) >= 0) {
                    bits.markAvailable(id);
                }
                done.countDown();
            }).start();
        }
        done.await(120, TimeUnit.SECONDS);
        double s = (System.nanoTime() - t0) / 1e9;
        // 200k chunks of 512 KB = ~100 GB; this is the bookkeeping cost alone.
        out("random chunk claim+complete, %d threads, %d chunks: %.0f ops/s (%.1f us/op)",
                threads, chunks, chunks / s, s * 1e6 / chunks);
    }

    private void serverRequestRate(File dir) throws Exception {
        File file = TestFiles.randomFile(dir, "s.bin", 32L * MB, 2);
        InformationMetadata info = SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB);
        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 24);
        try {
            Peer peer = root.asPeer();
            for (int clients : new int[]{1, 8, 24}) {
                AtomicLong requests = new AtomicLong();
                AtomicLong bytes = new AtomicLong();
                long end = System.currentTimeMillis() + 3000;
                CountDownLatch done = new CountDownLatch(clients);
                for (int c = 0; c < clients; c++) {
                    HttpClient client = new HttpClient(new Identity("c" + c, SwarmHarness.SECRET));
                    Random r = new Random(c);
                    new Thread(() -> {
                        try {
                            while (System.currentTimeMillis() < end) {
                                int id = r.nextInt(info.getChunkCount());
                                bytes.addAndGet(client.fetchChunk(peer, id, info.getChunkLength(id)).length);
                                requests.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                            // measured as fewer requests
                        } finally {
                            done.countDown();
                        }
                    }).start();
                }
                done.await(20, TimeUnit.SECONDS);
                out("one seeder serving 512 KB chunks to %2d concurrent clients: %.0f chunks/s = %.0f MB/s (loopback)",
                        clients, requests.get() / 3.0, bytes.get() / 3.0 / MB);
            }
        } finally {
            root.stop();
        }
    }

    // ------------------------------------------------------------------ swarm scaling

    /**
     * Every device, root included, can upload at most 4 MB/s (a stand-in for a shared Wi-Fi
     * uplink), so completion time is governed by bandwidth, not by the PC's CPU. Compares the
     * real client/server behaviour (leechers never serve) with the swarm.
     */
    private void bandwidthLimitedScaling(File dir) throws Exception {
        double rate = 4.0 * MB;
        long size = 16L * MB;
        File file = TestFiles.randomFile(new File(dir, "bwroot"), "fw16.bin", size, 4); // 32 chunks
        InformationMetadata info = SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB);
        out("bandwidth-limited swarm: each device uploads <= 4 MB/s, payload 16 MB (32 x 512 KB)");
        out("  mode          |  N | wall s | mean s | p95 s | root egress MB | ideal C/S s");
        for (boolean p2p : new boolean[]{false, true}) {
            int[] sizes = p2p ? new int[]{1, 4, 8, 16, 32} : new int[]{1, 4, 8, 16};
            for (int n : sizes) {
                Swarm swarm = new Swarm();
                swarm.uplinkBytesPerSec = rate;
                swarm.leechersSeed = p2p;
                // Client/server gets an unlimited connection budget: its best case.
                Node root = SwarmHarness.throttledRoot(swarm, file, info, p2p ? 4 : Math.max(4, n));
                List<Node> fleet = new ArrayList<>();
                long start = System.nanoTime();
                double[] times = new double[n];
                try {
                    for (int i = 0; i < n; i++) {
                        Node node = new Node("b" + i, swarm, new File(dir, "bw" + p2p + n + "-" + i),
                                SwarmHarness.SECRET, 6);
                        synchronized (swarm) {
                            swarm.leechers.add(node);
                        }
                        fleet.add(node);
                    }
                    for (Node node : fleet) {
                        node.start(Collections.singletonList(root.asPeer()), SwarmHarness.fastConfig());
                    }
                    for (int i = 0; i < n; i++) {
                        fleet.get(i).completed.await(400, TimeUnit.SECONDS);
                        times[i] = (System.nanoTime() - start) / 1e9;
                    }
                    double wall = (System.nanoTime() - start) / 1e9;
                    Arrays.sort(times);
                    double mean = Arrays.stream(times).average().orElse(0);
                    double p95 = times[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)];
                    out("  %-13s | %2d | %6.1f | %6.1f | %5.1f | %14.0f | %11.1f",
                            p2p ? "P2P swarm" : "client/server", n, wall, mean, p95,
                            root.bytesServed() / (double) MB, n * size / rate);
                } finally {
                    for (Node node : fleet) {
                        node.stop();
                    }
                    root.stop();
                }
            }
        }
    }

    private void swarmScaling(File dir) throws Exception {
        File file = TestFiles.randomFile(new File(dir, "root"), "fw.bin", 32L * MB, 3); // 64 chunks of 512 KB
        InformationMetadata info = SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB);
        out("swarm scaling: payload 32 MB (64 x 512 KB), root limited to 4 connections, loopback");
        out("  N | wall s | mean s | p95 s | root egress MB | client/server MB | root share | busy rejects");
        for (int n : new int[]{1, 4, 8, 16, 32, 48}) {
            Swarm swarm = new Swarm();
            Node root = SwarmHarness.root(swarm, file, info, 4);
            List<Node> fleet = new ArrayList<>();
            long[] doneAt = new long[n];
            long start = System.nanoTime();
            try {
                for (int i = 0; i < n; i++) {
                    Node node = new Node("n" + i, swarm, new File(dir, "s" + n + "-" + i), SwarmHarness.SECRET, 6);
                    synchronized (swarm) {
                        swarm.leechers.add(node);
                    }
                    fleet.add(node);
                }
                for (Node node : fleet) {
                    node.start(Collections.singletonList(root.asPeer()), SwarmHarness.fastConfig());
                }
                for (int i = 0; i < n; i++) {
                    fleet.get(i).completed.await(300, TimeUnit.SECONDS);
                    doneAt[i] = System.nanoTime();
                }
                double wall = (System.nanoTime() - start) / 1e9;
                double[] times = new double[n];
                for (int i = 0; i < n; i++) {
                    times[i] = (doneAt[i] - start) / 1e9;
                }
                Arrays.sort(times);
                double mean = Arrays.stream(times).average().orElse(0);
                double p95 = times[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)];
                long rootBytes = root.seed.getServer().bytesServed.get();
                long cs = (long) n * info.getFileSize();
                out(" %2d | %6.2f | %6.2f | %5.2f | %14.0f | %16.0f | %9.1f%% | %d",
                        n, wall, mean, p95, rootBytes / (double) MB, cs / (double) MB, 100.0 * rootBytes / cs,
                        root.seed.getServer().rejectedBusy.get());
            } finally {
                for (Node node : fleet) {
                    node.stop();
                }
                root.stop();
            }
        }
    }
}
