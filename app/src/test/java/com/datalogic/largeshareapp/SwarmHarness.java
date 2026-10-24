package com.datalogic.largeshareapp;

import com.datalogic.largeshareapp.manager.SeedManager;
import com.datalogic.largeshareapp.manager.SeekManager;
import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.PeerRegistry;
import com.datalogic.largeshareapp.manager.SwarmSession;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.security.Identity;
import com.datalogic.largeshareapp.storage.StorageManager;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs many real SeekManager / SeedManager / HttpServer / HttpClient instances in one JVM over the
 * loopback interface. Discovery is replaced by a {@link Swarm} directory; everything else is the
 * production code path.
 */
final class SwarmHarness {
    static final String SECRET = "test-fleet-secret";

    private SwarmHarness() {}

    /** The stand-in for NSD: tells nodes about each other. */
    static final class Swarm {
        final List<Node> leechers = new ArrayList<>();
        final List<Peer> sources = new ArrayList<>();
        final Random random = new Random(1234);
        /** When false nodes learn about each other only through peer exchange (no NSD stand-in). */
        volatile boolean announceViaDiscovery = true;
        /** When false leechers never serve: a pure client/server baseline. */
        volatile boolean leechersSeed = true;
        /** Upload capacity of every simulated device in bytes/s (0 = unlimited loopback). */
        volatile double uplinkBytesPerSec = 0;

        synchronized void addSource(Peer peer) {
            sources.add(peer);
        }

        /** A node started serving: announce it to the other leechers (like registering via NSD). */
        synchronized void advertise(Node node) {
            Peer peer = node.asPeer();
            if (peer == null) {
                return;
            }
            sources.add(peer);
            for (Node other : leechers) {
                if (other != node) {
                    other.registry.upsert(peer);
                }
            }
        }
    }

    /** A device taking part in the swarm as a leecher that becomes a seeder. */
    static final class Node {
        final String name;
        final Swarm swarm;
        final Identity identity;
        final HttpClient client;
        final SwarmSession session = new SwarmSession();
        final PeerRegistry registry = new PeerRegistry();
        final File receiveDir;
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean seeding = new AtomicBoolean();
        final AtomicInteger progress = new AtomicInteger();
        volatile String failure;
        SeedManager seed;
        HttpServer throttled;
        SeekManager seek;
        final int maxServe;

        Node(String name, Swarm swarm, File receiveDir, String secret, int maxServe) {
            this.name = name;
            this.swarm = swarm;
            this.identity = new Identity(name, secret);
            this.client = new HttpClient(identity);
            this.receiveDir = receiveDir;
            this.maxServe = maxServe;
        }

        Peer asPeer() {
            SeedManager s = seed;
            HttpServer t = throttled;
            int port = s != null ? s.getPort() : t != null ? t.getLocalPort() : -1;
            return port < 0 ? null : new Peer("127.0.0.1", port, "node-" + name, "sim");
        }

        long bytesServed() {
            return seed != null ? seed.getServer().bytesServed.get()
                    : throttled != null ? throttled.bytesServed.get() : 0;
        }

        void start(List<Peer> initialPeers, SeekManager.Config config) {
            for (Peer p : initialPeers) {
                registry.upsert(p);
            }
            seek = new SeekManager(session, client, registry, receiveDir, config);
            seek.addSeekListener(new SeekManager.SeekEventListener() {
                @Override
                public void onMetadataReady(InformationMetadata metadata) {
                    startSeeding();
                }

                @Override
                public void onChunkBatchLeeched(int done, int total) {
                    progress.set(done);
                }

                @Override
                public void onLeechingCompleted() {
                    completed.countDown();
                }

                @Override
                public void onLeechingFailed(String reason) {
                    failure = reason;
                    completed.countDown();
                }
            });
            seek.start();
        }

        synchronized void startSeeding() {
            if (!swarm.leechersSeed || !seeding.compareAndSet(false, true)) {
                return;
            }
            if (swarm.uplinkBytesPerSec > 0) {
                try {
                    throttled = throttledServer(session, identity, new Uplink(swarm.uplinkBytesPerSec), maxServe);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                client.setAdvertisedPort(throttled.getLocalPort());
            } else {
                seed = new SeedManager(session, identity, 0, maxServe);
                if (!seed.initialize()) {
                    throw new IllegalStateException("cannot start seed server");
                }
                client.setAdvertisedPort(seed.getPort()); // peer exchange: tell others where we serve
            }
            if (swarm.announceViaDiscovery) {
                swarm.advertise(this);
            }
        }

        void stop() {
            if (seek != null) {
                seek.stop();
            }
            if (seed != null) {
                seed.stop();
            }
            if (throttled != null) {
                throttled.stop();
            }
            session.close();
        }

        File downloaded(InformationMetadata info) {
            return new File(receiveDir, info.getFileName());
        }
    }

    static SeekManager.Config fastConfig() {
        SeekManager.Config c = new SeekManager.Config();
        c.tickMs = 100;
        c.idleRefreshMs = 100;
        c.maxIdleRounds = 30;
        c.statsMs = 500;
        c.stateSaveMs = 300;
        c.pexIntervalMs = 200;
        return c;
    }

    /** The root: holds the complete file and serves it. */
    static Node root(Swarm swarm, File file, InformationMetadata info, int maxServe) throws IOException {
        Node root = new Node("root", swarm, file.getParentFile(), SECRET, maxServe);
        StorageManager storage = StorageManager.forSource(info, file);
        BitSetMetadata bits = new BitSetMetadata(info.getChunkCount());
        bits.setAllAvailable();
        root.session.attach(info, bits, storage);
        root.seeding.set(true);
        root.seed = new SeedManager(root.session, root.identity, 0, maxServe);
        if (!root.seed.initialize()) {
            throw new IllegalStateException("cannot start root");
        }
        swarm.addSource(root.asPeer());
        return root;
    }

    /** Root whose total upload is capped like a real device's Wi-Fi uplink. */
    static Node throttledRoot(Swarm swarm, File file, InformationMetadata info, int maxServe) throws IOException {
        Node root = new Node("root", swarm, file.getParentFile(), SECRET, maxServe);
        StorageManager storage = StorageManager.forSource(info, file);
        BitSetMetadata bits = new BitSetMetadata(info.getChunkCount());
        bits.setAllAvailable();
        root.session.attach(info, bits, storage);
        root.seeding.set(true);
        root.throttled = throttledServer(root.session, root.identity, new Uplink(swarm.uplinkBytesPerSec), maxServe);
        swarm.addSource(root.asPeer());
        return root;
    }

    /** One device's upload link: all of its connections together get at most this rate. */
    static final class Uplink {
        private final double bytesPerNano;
        private long next;

        Uplink(double bytesPerSec) {
            bytesPerNano = bytesPerSec / 1e9;
        }

        void take(int bytes) throws java.io.InterruptedIOException {
            long wait;
            synchronized (this) {
                long now = System.nanoTime();
                long start = Math.max(now, next);
                next = start + (long) (bytes / bytesPerNano);
                wait = next - now;
            }
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
        }
    }

    /** Serves a session like SeedManager does, but through a bandwidth-limited uplink. */
    static HttpServer throttledServer(SwarmSession session, Identity identity, Uplink uplink, int maxConnections)
            throws IOException {
        HttpServer.Handler handler = new HttpServer.Handler() {
            private volatile byte[] manifest;

            @Override
            public byte[] getMetadata() {
                InformationMetadata info = session.info();
                if (info == null) {
                    return null;
                }
                if (manifest == null) {
                    try {
                        manifest = InformationMetadata.toJsonString(info, identity.signManifest(info))
                                .getBytes(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return null;
                    }
                }
                return manifest;
            }

            @Override
            public byte[] getBitset() {
                InformationMetadata info = session.info();
                BitSetMetadata bits = session.bits();
                if (info == null || bits == null) {
                    return null;
                }
                try {
                    return BitSetMetadata.toJsonString(bits, info.getFileHash()).getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public long getChunkLength(int chunkId) {
                InformationMetadata info = session.info();
                BitSetMetadata bits = session.bits();
                return info != null && bits != null && bits.isAvailable(chunkId) ? info.getChunkLength(chunkId) : -1;
            }

            @Override
            public void writeChunk(int chunkId, OutputStream out) throws IOException {
                byte[] data = session.storage().readChunk(chunkId);
                for (int off = 0; off < data.length; off += 64 * 1024) {
                    int n = Math.min(64 * 1024, data.length - off);
                    uplink.take(n);
                    out.write(data, off, n);
                }
            }

            @Override
            public boolean postProgress(String authenticatedDeviceId, String body) {
                return true;
            }

            @Override
            public void onPeerSeen(String ip, int port) {
            }

            @Override
            public byte[] getPeers(String requesterIp, int requesterServePort) {
                return "{\"peers\":[]}".getBytes(StandardCharsets.UTF_8);
            }
        };
        HttpServer server = new HttpServer(0, identity, handler, maxConnections);
        server.start();
        return server;
    }

    /** Behaviour knobs for a hand-built (non-SeedManager) server. */
    static final class Behaviour {
        long delayPerChunkMs;
        double failProbability;
        boolean corrupt;
        /** Remember devices that announce a serving port and hand them out (peer exchange). */
        boolean peerExchange;
        final AtomicLong chunksOffered = new AtomicLong();
    }

    /** An in-fleet server whose behaviour we control: slow, flaky or serving corrupt data. */
    static HttpServer customServer(InformationMetadata info, File file, String secret, Behaviour behaviour,
                                   int maxConnections, int port) throws IOException {
        Identity identity = new Identity("custom-" + Math.abs(new Random().nextInt()), secret);
        StorageManager storage = StorageManager.forSource(info, file);
        Random random = new Random();
        byte[] manifest;
        try {
            manifest = InformationMetadata.toJsonString(info, identity.signManifest(info))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException(e);
        }
        BitSetMetadata all = new BitSetMetadata(info.getChunkCount());
        all.setAllAvailable();
        java.util.Set<String> seenPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        HttpServer.Handler handler = new HttpServer.Handler() {
            @Override
            public byte[] getMetadata() {
                return manifest;
            }

            @Override
            public byte[] getBitset() {
                try {
                    return BitSetMetadata.toJsonString(all, info.getFileHash()).getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public long getChunkLength(int chunkId) {
                return chunkId >= 0 && chunkId < info.getChunkCount() ? info.getChunkLength(chunkId) : -1;
            }

            @Override
            public void writeChunk(int chunkId, OutputStream out) throws IOException {
                behaviour.chunksOffered.incrementAndGet();
                if (behaviour.delayPerChunkMs > 0) {
                    try {
                        Thread.sleep(behaviour.delayPerChunkMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (behaviour.failProbability > 0 && random.nextDouble() < behaviour.failProbability) {
                    byte[] half = new byte[info.getChunkLength(chunkId) / 2];
                    out.write(half);
                    out.flush();
                    throw new IOException("injected mid-chunk failure");
                }
                if (behaviour.corrupt) {
                    byte[] data = storage.readChunk(chunkId);
                    data[data.length / 2] ^= 0x55;
                    out.write(data);
                    return;
                }
                storage.transferChunk(chunkId, out);
            }

            @Override
            public boolean postProgress(String authenticatedDeviceId, String body) {
                return true;
            }

            @Override
            public void onPeerSeen(String ip, int port) {
                if (behaviour.peerExchange) {
                    seenPeers.add(ip + ":" + port);
                }
            }

            @Override
            public byte[] getPeers(String requesterIp, int requesterServePort) {
                org.json.JSONArray array = new org.json.JSONArray();
                for (String address : seenPeers) {
                    if (!address.equals(requesterIp + ":" + requesterServePort)) {
                        array.put(address);
                    }
                }
                try {
                    return new org.json.JSONObject().put("peers", array).toString()
                            .getBytes(StandardCharsets.UTF_8);
                } catch (org.json.JSONException e) {
                    return null;
                }
            }
        };
        HttpServer server = new HttpServer(port, identity, handler, maxConnections);
        server.start();
        return server;
    }

    static boolean sameContent(File a, File b) throws IOException {
        return a.length() == b.length() && java.util.Arrays.equals(
                SecureManager.sha256(Files.readAllBytes(a.toPath())),
                SecureManager.sha256(Files.readAllBytes(b.toPath())));
    }

    static boolean await(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
