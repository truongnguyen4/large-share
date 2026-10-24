package com.datalogic.largeshareapp;

import static org.junit.Assume.assumeTrue;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.SeedManager;
import com.datalogic.largeshareapp.manager.SwarmSession;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.security.Identity;
import com.datalogic.largeshareapp.security.IdentityStore;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Not a real test: when LS_ROOT_FILE is set it hosts a root on this PC (port LS_ROOT_PORT, default
 * 8888) for LS_ROOT_SECONDS so a real device can leech from it. Skipped otherwise. Used to put a
 * second node on the network when only one phone is available.
 */
public class PcRootHostTest {
    @Test
    public void hostRootForDevices() throws Exception {
        String path = System.getenv("LS_ROOT_FILE");
        assumeTrue("LS_ROOT_FILE not set", path != null);
        int port = Integer.parseInt(System.getenv().getOrDefault("LS_ROOT_PORT", "8888"));
        long seconds = Long.parseLong(System.getenv().getOrDefault("LS_ROOT_SECONDS", "300"));
        String secret = System.getenv().getOrDefault("LS_ROOT_SECRET", IdentityStore.DEFAULT_FLEET_SECRET);
        File log = new File(System.getenv().getOrDefault("LS_ROOT_LOG", "pc-root.log"));

        File file = new File(path);
        InformationMetadata info = SecureManager.createFileMetadata(file, SecureManager.CHUNK_SIZE_512KB);
        Identity identity = new Identity("pc-root", secret);
        SwarmSession session = new SwarmSession();
        BitSetMetadata bits = new BitSetMetadata(info.getChunkCount());
        bits.setAllAvailable();
        session.attach(info, bits, StorageManager.forSource(info, file));
        SeedManager seed = new SeedManager(session, identity, port, 24);
        seed.addPeersTrackingListener((id, done, total) -> append(log, "progress " + id + " " + done + "/" + total));
        if (!seed.initialize()) {
            throw new IllegalStateException("cannot bind port " + port);
        }
        append(log, "ROOT READY port=" + seed.getPort() + " chunks=" + info.getChunkCount()
                + " sha256=" + info.getFileHash());
        long end = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(2000);
            append(log, "served chunks=" + seed.getServer().chunksServed.get()
                    + " bytes=" + seed.getServer().bytesServed.get()
                    + " active=" + seed.getServer().activeConnections.get()
                    + " busy=" + seed.getServer().rejectedBusy.get()
                    + " authRejects=" + seed.getServer().rejectedAuth.get());
        }
        seed.stop();
        session.close();
    }

    private static void append(File log, String line) {
        try {
            Files.write(log.toPath(), (System.currentTimeMillis() + " " + line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // diagnostics only
        }
    }
}
