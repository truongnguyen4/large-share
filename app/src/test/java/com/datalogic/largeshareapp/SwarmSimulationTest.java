package com.datalogic.largeshareapp;

import static com.datalogic.largeshareapp.SwarmHarness.SECRET;
import static com.datalogic.largeshareapp.SwarmHarness.await;
import static com.datalogic.largeshareapp.SwarmHarness.customServer;
import static com.datalogic.largeshareapp.SwarmHarness.fastConfig;
import static com.datalogic.largeshareapp.SwarmHarness.sameContent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.datalogic.largeshareapp.SwarmHarness.Behaviour;
import com.datalogic.largeshareapp.SwarmHarness.Node;
import com.datalogic.largeshareapp.SwarmHarness.Swarm;
import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.SeekManager;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.network.PeerException;
import com.datalogic.largeshareapp.security.Identity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end behaviour of the swarm with real sockets and the production download/serve code:
 * concurrency, fault tolerance, data integrity and identity.
 */
public class SwarmSimulationTest {
    private static final int CHUNK = 32 * 1024;
    private static final long FILE_SIZE = CHUNK * 192L + 777; // 193 chunks, ~6 MB

    private File dir;
    private File file;
    private InformationMetadata info;
    private final List<Node> nodes = new ArrayList<>();
    private final List<HttpServer> servers = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        dir = TestFiles.tempDir("swarm");
        file = TestFiles.randomFile(new File(dir, "root"), "firmware.bin", FILE_SIZE, 77);
        info = SecureManager.createFileMetadata(file, CHUNK);
    }

    @After
    public void tearDown() {
        for (Node n : nodes) {
            n.stop();
        }
        for (HttpServer s : servers) {
            s.stop();
        }
        TestFiles.deleteRecursively(dir);
    }

    private Node leecher(Swarm swarm, String name, List<Peer> known) {
        Node n = new Node(name, swarm, new File(dir, "recv-" + name), SECRET, 6);
        synchronized (swarm) {
            swarm.leechers.add(n);
        }
        nodes.add(n);
        n.start(known, fastConfig());
        return n;
    }

    private void assertAllCorrect(List<Node> group) throws Exception {
        for (Node n : group) {
            assertNull(n.name + " failed: " + n.failure, n.failure);
            assertTrue(n.name + " did not complete (" + n.progress.get() + "/" + info.getChunkCount() + ")",
                    n.completed.getCount() == 0);
            assertTrue(n.name + " has wrong content", sameContent(file, n.downloaded(info)));
        }
    }

    // ---------------------------------------------------------------- high concurrency

    @Test(timeout = 120_000)
    public void fleetOf24DevicesAllGetTheExactFileWhileTheRootServesOnlyAFewConnections() throws Exception {
        Swarm swarm = new Swarm();
        // The root accepts at most 4 simultaneous connections: everyone else must be served by peers.
        Node root = SwarmHarness.root(swarm, file, info, 4);
        nodes.add(root);

        List<Node> fleet = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            fleet.add(leecher(swarm, "d" + i, Collections.singletonList(root.asPeer())));
        }
        for (Node n : fleet) {
            assertTrue(n.name + " timed out at " + n.progress.get() + "/" + info.getChunkCount(),
                    await(n.completed, 100));
        }
        assertAllCorrect(fleet);

        long rootBytes = root.seed.getServer().bytesServed.get();
        long clientServerBytes = 24L * FILE_SIZE;
        double rootShare = 100.0 * rootBytes / clientServerBytes;
        System.out.printf("[swarm] 24 devices x %d KB: root served %d KB (%.1f%% of the %d KB a client/server "
                        + "model would send), busy rejections at root: %d%n",
                FILE_SIZE / 1024, rootBytes / 1024, rootShare, clientServerBytes / 1024,
                root.seed.getServer().rejectedBusy.get());
        assertTrue("peers must carry most of the load, root share was " + rootShare + "%", rootShare < 60.0);
    }

    @Test(timeout = 120_000)
    public void devicesThatKnowOnlyTheRootFindEachOtherThroughPeerExchange() throws Exception {
        Swarm swarm = new Swarm();
        swarm.announceViaDiscovery = false; // multicast blocked: no NSD, only the root's address is known
        // A deliberately slow root (like a busy uplink) that takes part in peer exchange.
        Behaviour slowRoot = new Behaviour();
        slowRoot.delayPerChunkMs = 10;
        slowRoot.peerExchange = true;
        HttpServer rootServer = customServer(info, file, SECRET, slowRoot, 3, 0);
        servers.add(rootServer);
        Peer rootPeer = new Peer("127.0.0.1", rootServer.getLocalPort(), "root", "sim");

        List<Node> fleet = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            fleet.add(leecher(swarm, "d" + i, Collections.singletonList(rootPeer)));
        }
        for (Node n : fleet) {
            assertTrue(n.name + " stuck at " + n.progress.get(), await(n.completed, 100));
        }
        assertAllCorrect(fleet);

        long rootBytes = rootServer.bytesServed.get();
        double rootShare = 100.0 * rootBytes / (12L * FILE_SIZE);
        System.out.printf("[pex] 12 devices that only knew the root: root served %.1f%% of the client/server volume%n",
                rootShare);
        assertTrue("peer exchange must let devices serve each other, root share " + rootShare + "%",
                rootShare < 75.0);
    }

    // ---------------------------------------------------------------- fault tolerance

    @Test(timeout = 120_000)
    public void swarmSurvivesTheRootDyingOnceAnyDeviceHoldsTheWholeFile() throws Exception {
        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 4);
        nodes.add(root);

        List<Node> fleet = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fleet.add(leecher(swarm, "d" + i, Collections.singletonList(root.asPeer())));
        }
        // wait for the first device to finish, then kill the root
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && fleet.stream().noneMatch(n -> n.completed.getCount() == 0)) {
            Thread.sleep(20);
        }
        root.seed.stop();
        for (Node n : fleet) {
            assertTrue(n.name + " stuck at " + n.progress.get(), await(n.completed, 60));
        }
        assertAllCorrect(fleet);
    }

    @Test(timeout = 120_000)
    public void flakyPeerThatDropsMidChunkDoesNotBreakTheDownload() throws Exception {
        Behaviour flaky = new Behaviour();
        flaky.failProbability = 0.4;
        HttpServer server = customServer(info, file, SECRET, flaky, 8, 0);
        servers.add(server);

        Swarm swarm = new Swarm();
        Peer flakyPeer = new Peer("127.0.0.1", server.getLocalPort(), "flaky", "sim");
        List<Node> fleet = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fleet.add(leecher(swarm, "d" + i, Collections.singletonList(flakyPeer)));
        }
        for (Node n : fleet) {
            assertTrue(n.name + " stuck at " + n.progress.get(), await(n.completed, 100));
        }
        assertAllCorrect(fleet);
        assertTrue(flaky.chunksOffered.get() > info.getChunkCount()); // many were retried
    }

    @Test(timeout = 120_000)
    public void rootThatRestartsOnANewAddressIsRediscoveredAndTheDownloadResumes() throws Exception {
        Behaviour slow = new Behaviour();
        slow.delayPerChunkMs = 15;
        HttpServer first = customServer(info, file, SECRET, slow, 8, 0);
        servers.add(first);

        Swarm swarm = new Swarm();
        Peer rootV1 = new Peer("127.0.0.1", first.getLocalPort(), "root", "sim");
        Node leech = leecher(swarm, "d0", Collections.singletonList(rootV1));

        while (leech.progress.get() < 40) {
            Thread.sleep(10);
        }
        first.stop(); // root crashes / loses its address
        int atCrash = leech.progress.get();
        Thread.sleep(2500); // leecher keeps retrying with back-off, makes no progress
        assertTrue("no progress while the root is down", leech.progress.get() <= atCrash + 1);

        HttpServer second = customServer(info, file, SECRET, slow, 8, 0);
        servers.add(second);
        // NSD would report the same service name at its new address
        leech.registry.upsert(new Peer("127.0.0.1", second.getLocalPort(), "root", "sim"));

        assertTrue("download did not resume at " + leech.progress.get(), await(leech.completed, 60));
        assertAllCorrect(Collections.singletonList(leech));
    }

    @Test(timeout = 120_000)
    public void pausingForANetworkLossFreezesProgressAndResumingFinishesTheDownload() throws Exception {
        Behaviour slow = new Behaviour();
        slow.delayPerChunkMs = 15;
        HttpServer server = customServer(info, file, SECRET, slow, 8, 0);
        servers.add(server);

        Swarm swarm = new Swarm();
        Node leech = leecher(swarm, "d0", Collections.singletonList(
                new Peer("127.0.0.1", server.getLocalPort(), "root", "sim")));
        while (leech.progress.get() < 30) {
            Thread.sleep(10);
        }
        leech.seek.pause();
        Thread.sleep(600); // let in-flight chunks land
        int frozen = leech.progress.get();
        Thread.sleep(1500);
        assertEquals("progress must freeze while paused", frozen, leech.progress.get());
        assertEquals(SeekManager.State.PAUSED, leech.seek.getState());

        leech.seek.resume();
        assertTrue("did not finish after resume", await(leech.completed, 60));
        assertAllCorrect(Collections.singletonList(leech));
    }

    @Test(timeout = 120_000)
    public void interruptedDownloadResumesWithoutRefetchingVerifiedChunks() throws Exception {
        Behaviour slow = new Behaviour();
        slow.delayPerChunkMs = 8;
        HttpServer server = customServer(info, file, SECRET, slow, 8, 0);
        servers.add(server);
        Peer root = new Peer("127.0.0.1", server.getLocalPort(), "root", "sim");

        File recv = new File(dir, "recv-resume");
        Swarm swarm = new Swarm();
        Node first = new Node("resume", swarm, recv, SECRET, 6);
        swarm.leechers.add(first);
        first.start(Collections.singletonList(root), fastConfig());
        while (first.progress.get() < 60) {
            Thread.sleep(10);
        }
        first.stop(); // app killed mid-download (state is persisted on stop)
        int before = first.progress.get();
        assertTrue(new File(recv, "firmware.bin.part").exists());
        assertFalse(new File(recv, "firmware.bin").exists());

        long offeredBefore = slow.chunksOffered.get();
        Node second = new Node("resume", new Swarm(), recv, SECRET, 6);
        second.swarm.leechers.add(second);
        second.start(Collections.singletonList(root), fastConfig());
        assertTrue(await(second.completed, 60));
        nodes.add(second);

        long fetchedAgain = slow.chunksOffered.get() - offeredBefore;
        System.out.printf("[resume] had %d chunks, second run fetched %d of %d%n", before, fetchedAgain,
                info.getChunkCount());
        assertTrue("must not refetch everything: " + fetchedAgain,
                fetchedAgain < info.getChunkCount() - before / 2);
        assertAllCorrect(Collections.singletonList(second));
    }

    // ---------------------------------------------------------------- data consistency

    @Test(timeout = 120_000)
    public void peerServingCorruptChunksIsRejectedAndNeverPollutesTheFile() throws Exception {
        Behaviour evil = new Behaviour();
        evil.corrupt = true;
        HttpServer evilServer = customServer(info, file, SECRET, evil, 8, 0);
        servers.add(evilServer);

        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 8);
        nodes.add(root);
        Peer evilPeer = new Peer("127.0.0.1", evilServer.getLocalPort(), "evil", "sim");

        List<Node> fleet = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fleet.add(leecher(swarm, "d" + i, java.util.Arrays.asList(evilPeer, root.asPeer())));
        }
        for (Node n : fleet) {
            assertTrue(n.name + " stuck", await(n.completed, 100));
        }
        assertAllCorrect(fleet);
        assertTrue("the corrupt peer must have been tried", evil.chunksOffered.get() > 0);
    }

    // ---------------------------------------------------------------- identity

    @Test(timeout = 60_000)
    public void deviceWithoutTheFleetKeyCannotReadOrReport() throws Exception {
        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 8);
        nodes.add(root);
        Peer rootPeer = root.asPeer();

        HttpClient outsider = new HttpClient(new Identity("intruder", "not-the-fleet-key"));
        for (int i = 0; i < 3; i++) {
            try {
                outsider.fetchMetadata(rootPeer);
                fail("outsider read the manifest");
            } catch (PeerException e) {
                assertEquals(PeerException.Kind.UNAUTHORIZED, e.kind);
            }
        }
        try {
            outsider.reportProgress(rootPeer, "intruder", 1, 2);
            fail("outsider reported progress");
        } catch (PeerException e) {
            assertEquals(PeerException.Kind.UNAUTHORIZED, e.kind);
        }
        assertTrue(root.seed.getServer().rejectedAuth.get() >= 4);

        // the intruder's failures do not stop a genuine fleet member from downloading
        Node member = leecher(swarm, "member", Collections.singletonList(rootPeer));
        assertTrue(await(member.completed, 50));
        assertAllCorrect(Collections.singletonList(member));
    }

    @Test(timeout = 60_000)
    public void serverThatDoesNotKnowTheFleetKeyIsRefusedByTheClient() throws Exception {
        Behaviour behaviour = new Behaviour();
        HttpServer impostor = customServer(info, file, "attacker-secret", behaviour, 8, 0);
        servers.add(impostor);
        // The impostor cannot authenticate the request at all.
        HttpClient client = new HttpClient(new Identity("honest", SECRET));
        try {
            client.fetchMetadata(new Peer("127.0.0.1", impostor.getLocalPort(), "impostor", "sim"));
            fail("accepted an impostor");
        } catch (PeerException e) {
            assertEquals(PeerException.Kind.UNAUTHORIZED, e.kind);
        }
    }

    @Test(timeout = 60_000)
    public void serverThatSkipsAuthenticationCannotFakeAValidAnswer() throws Exception {
        // A rogue server that happily answers 200 to anyone but cannot sign its response.
        java.net.ServerSocket rogue = new java.net.ServerSocket(0);
        Thread t = new Thread(() -> {
            try (java.net.Socket s = rogue.accept()) {
                java.io.InputStream in = s.getInputStream();
                int state = 0;
                while (state < 4) { // read up to the blank line
                    int b = in.read();
                    if (b < 0) {
                        break;
                    }
                    state = (b == '\r' || b == '\n') ? state + 1 : 0;
                }
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                        + "Connection: close\r\n\r\n{}").getBytes());
            } catch (java.io.IOException ignored) {
                // test ends
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            new HttpClient(new Identity("honest", SECRET)).fetchMetadata(
                    new Peer("127.0.0.1", rogue.getLocalPort(), "rogue", "sim"));
            fail("accepted an unsigned answer");
        } catch (PeerException e) {
            assertEquals(PeerException.Kind.IDENTITY, e.kind);
        } finally {
            rogue.close();
        }
    }

    @Test(timeout = 60_000)
    public void deviceThatAdvertisesOneIdentityButAnswersAsAnotherIsRejected() throws Exception {
        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 8);
        nodes.add(root);
        // NSD said this service is device "someone-else"; the server proves it is "root".
        Peer spoof = new Peer("127.0.0.1", root.asPeer().port, Peer.NAME_PREFIX_SHARE + "someone-else", "sim");
        try {
            new HttpClient(new Identity("honest", SECRET)).fetchBitset(spoof);
            fail("accepted a mismatching identity");
        } catch (PeerException e) {
            assertEquals(PeerException.Kind.IDENTITY, e.kind);
        }
        // the genuine advertisement is accepted
        Peer genuine = new Peer("127.0.0.1", root.asPeer().port, Peer.NAME_PREFIX_SHARE + "root", "sim");
        assertEquals(info.getFileHash(), new HttpClient(new Identity("honest", SECRET)).fetchBitset(genuine).fileHash);
    }

    @Test(timeout = 60_000)
    public void progressReportsAreBoundToTheAuthenticatedDevice() throws Exception {
        Swarm swarm = new Swarm();
        Node root = SwarmHarness.root(swarm, file, info, 8);
        nodes.add(root);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        root.seed.addPeersTrackingListener((id, done, total) -> seen.add(id + ":" + done + "/" + total));

        HttpClient alice = new HttpClient(new Identity("alice", SECRET));
        alice.reportProgress(root.asPeer(), "alice", 5, 10);
        try {
            alice.reportProgress(root.asPeer(), "bob", 10, 10); // alice may not report as bob
            fail("spoofed progress accepted");
        } catch (PeerException e) {
            assertEquals(PeerException.Kind.PROTOCOL, e.kind);
        }
        assertEquals(Collections.singletonList("alice:5/10"), seen);
    }

    // ---------------------------------------------------------------- admission control

    @Test(timeout = 60_000)
    public void serverAtCapacityAnswersBusyInsteadOfQueueing() throws Exception {
        Behaviour slow = new Behaviour();
        slow.delayPerChunkMs = 400;
        HttpServer server = customServer(info, file, SECRET, slow, 2, 0);
        servers.add(server);
        Peer peer = new Peer("127.0.0.1", server.getLocalPort(), "slow", "sim");

        AtomicInteger busy = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    new HttpClient(new Identity("c" + id, SECRET)).fetchChunk(peer, id, info.getChunkLength(id));
                    ok.incrementAndGet();
                } catch (PeerException e) {
                    if (e.kind == PeerException.Kind.BUSY) {
                        busy.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(await(done, 30));
        System.out.printf("[admission] 8 simultaneous clients, capacity 2: ok=%d busy=%d%n", ok.get(), busy.get());
        assertTrue("some clients must be turned away quickly", busy.get() >= 1);
        assertTrue("some clients must be served", ok.get() >= 1);
    }
}
