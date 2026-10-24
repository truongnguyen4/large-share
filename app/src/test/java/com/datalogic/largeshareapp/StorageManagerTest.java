package com.datalogic.largeshareapp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class StorageManagerTest {
    private static final int CHUNK = 16 * 1024;
    private File dir;
    private File source;
    private InformationMetadata meta;
    private byte[] content;

    @Before
    public void setUp() throws Exception {
        dir = TestFiles.tempDir("storage");
        source = TestFiles.randomFile(dir, "src.bin", CHUNK * 7L + 500, 11); // 8 chunks, last short
        meta = SecureManager.createFileMetadata(source, CHUNK);
        content = Files.readAllBytes(source.toPath());
    }

    @After
    public void tearDown() {
        TestFiles.deleteRecursively(dir);
    }

    private byte[] chunkOf(int i) {
        int len = meta.getChunkLength(i);
        byte[] out = new byte[len];
        System.arraycopy(content, i * CHUNK, out, 0, len);
        return out;
    }

    @Test
    public void chunksArrivingInAnyOrderReassembleTheExactFile() throws Exception {
        File target = new File(new File(dir, "recv"), "out.bin");
        try (StorageManager sm = StorageManager.forLeech(meta, target)) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < meta.getChunkCount(); i++) {
                order.add(i);
            }
            Collections.shuffle(order, new java.util.Random(5));
            for (int i : order) {
                assertTrue(sm.verifyChunk(i, chunkOf(i)));
                sm.writeChunk(i, chunkOf(i));
            }
            assertTrue("Layer 2 whole-file check", sm.verifyWholeFile());
            assertFalse("not published before finalize", target.exists());
            sm.finalizeDownload();
        }
        assertTrue(target.exists());
        assertFalse(new File(target.getPath() + ".part").exists());
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void corruptChunkIsRejectedAndWrongLengthIsRefused() throws Exception {
        try (StorageManager sm = StorageManager.forLeech(meta, new File(dir, "x.bin"))) {
            byte[] bad = chunkOf(2);
            bad[10] ^= 0x01;
            assertFalse(sm.verifyChunk(2, bad));
            assertFalse(sm.verifyChunk(2, new byte[5]));
            try {
                sm.writeChunk(2, new byte[5]);
                fail("short chunk written");
            } catch (IOException expected) {
                // good
            }
        }
    }

    @Test
    public void wholeFileCheckCatchesCorruptionAndPinpointsTheChunk() throws Exception {
        File target = new File(dir, "y.bin");
        try (StorageManager sm = StorageManager.forLeech(meta, target)) {
            for (int i = 0; i < meta.getChunkCount(); i++) {
                sm.writeChunk(i, i == 4 ? flipped(chunkOf(i)) : chunkOf(i));
            }
            assertFalse(sm.verifyWholeFile());
            BitSet corrupt = sm.findCorruptChunks();
            assertEquals(1, corrupt.cardinality());
            assertTrue(corrupt.get(4));
            sm.writeChunk(4, chunkOf(4)); // re-fetch only that chunk
            assertTrue(sm.verifyWholeFile());
        }
    }

    private static byte[] flipped(byte[] data) {
        data[0] ^= 0x7f;
        return data;
    }

    @Test
    public void interruptedDownloadResumesAndDropsAnyChunkThatNoLongerVerifies() throws Exception {
        File target = new File(dir, "r.bin");
        BitSet done = new BitSet();
        try (StorageManager sm = StorageManager.forLeech(meta, target)) {
            for (int i : new int[]{0, 1, 5}) {
                sm.writeChunk(i, chunkOf(i));
                done.set(i);
            }
            sm.persistState(done);
        }
        // simulate disk damage to chunk 1 while the app was closed
        try (RandomAccessFile raf = new RandomAccessFile(new File(dir, "r.bin.part"), "rw")) {
            raf.seek((long) CHUNK + 3);
            raf.write(0xAB);
        }
        try (StorageManager sm = StorageManager.forLeech(meta, target)) {
            BitSet resumed = sm.loadResumeState();
            assertTrue(resumed.get(0));
            assertFalse("damaged chunk must not be trusted", resumed.get(1));
            assertTrue(resumed.get(5));
            assertEquals(2, resumed.cardinality());
        }
    }

    @Test
    public void resumeStateForADifferentPayloadIsIgnored() throws Exception {
        File target = new File(dir, "z.bin");
        try (StorageManager sm = StorageManager.forLeech(meta, target)) {
            sm.writeChunk(0, chunkOf(0));
            BitSet one = new BitSet();
            one.set(0);
            sm.persistState(one);
        }
        File other = TestFiles.randomFile(dir, "other.bin", CHUNK * 7L + 500, 99);
        InformationMetadata otherMeta = SecureManager.createFileMetadata(other, CHUNK);
        try (StorageManager sm = StorageManager.forLeech(otherMeta, target)) {
            assertTrue(sm.loadResumeState().isEmpty());
        }
    }

    @Test
    public void manyThreadsCanServeChunksConcurrentlyWithoutCorruption() throws Exception {
        try (StorageManager sm = StorageManager.forSource(meta, source)) {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final int seed = t;
                results.add(pool.submit(() -> {
                    java.util.Random random = new java.util.Random(seed);
                    for (int n = 0; n < 400; n++) {
                        int i = random.nextInt(meta.getChunkCount());
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        sm.transferChunk(i, out);
                        if (!java.util.Arrays.equals(out.toByteArray(), chunkOf(i))) {
                            return false;
                        }
                        if (!java.util.Arrays.equals(sm.readChunk(i), chunkOf(i))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue("concurrent read returned wrong bytes", f.get(60, TimeUnit.SECONDS));
            }
            pool.shutdownNow();
        }
    }

    @Test
    public void sourceThatChangedSinceTheManifestWasBuiltIsRefused() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(source, "rw")) {
            raf.setLength(source.length() - 10);
        }
        try {
            StorageManager.forSource(meta, source).close();
            fail("changed source accepted");
        } catch (IOException expected) {
            // good
        }
    }

    @Test
    public void outOfRangeChunkIndexesAreRejected() throws Exception {
        try (StorageManager sm = StorageManager.forSource(meta, source)) {
            try {
                sm.readChunk(meta.getChunkCount());
                fail();
            } catch (IOException expected) {
                // good
            }
            try {
                sm.readChunk(-1);
                fail();
            } catch (IOException expected) {
                // good
            }
        }
    }
}
