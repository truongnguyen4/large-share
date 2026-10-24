package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;

import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

public class ManifestAndHashTest {
    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = TestFiles.tempDir("manifest");
    }

    @After
    public void tearDown() {
        TestFiles.deleteRecursively(dir);
    }

    @Test
    public void chunkSizeIsExactly512KB() {
        assertEquals(524_288, SecureManager.CHUNK_SIZE_512KB);
    }

    @Test
    public void singlePassProducesPerChunkAndWholeFileHashes() throws Exception {
        int chunk = SecureManager.CHUNK_SIZE_512KB;
        long size = chunk * 3L + 1234; // 4 chunks, last one short
        File file = TestFiles.randomFile(dir, "payload.bin", size, 1);
        InformationMetadata meta = SecureManager.createFileMetadata(file, chunk);

        assertNotNull(meta);
        assertEquals(4, meta.getChunkCount());
        assertEquals(size, meta.getFileSize());
        assertEquals(chunk, meta.getChunkLength(0));
        assertEquals(1234, meta.getChunkLength(3));

        byte[] all = Files.readAllBytes(file.toPath());
        // Layer 2: whole-file hash is a plain SHA-256 of the file (not a hash of a hash).
        assertEquals(SecureManager.toHex(MessageDigest.getInstance("SHA-256").digest(all)), meta.getFileHash());
        // Layer 1: each chunk hash covers exactly that chunk.
        for (int i = 0; i < meta.getChunkCount(); i++) {
            int len = meta.getChunkLength(i);
            assertEquals(SecureManager.sha256Hex(all, i * chunk, len), meta.getChunkHashes()[i]);
        }
    }

    @Test
    public void manifestSurvivesJsonRoundTrip() throws Exception {
        File file = TestFiles.randomFile(dir, "a.bin", 200_000, 2);
        InformationMetadata meta = SecureManager.createFileMetadata(file, 64 * 1024);
        String json = InformationMetadata.toJsonString(meta, "sig123");
        InformationMetadata parsed = InformationMetadata.fromJsonString(json);

        assertEquals(meta.getFileName(), parsed.getFileName());
        assertEquals(meta.getFileHash(), parsed.getFileHash());
        assertEquals(meta.getChunkCount(), parsed.getChunkCount());
        assertEquals("sig123", parsed.getSignature());
        assertEquals(meta.canonicalString(), parsed.canonicalString());
    }

    @Test
    public void hostileManifestsAreRejected() throws Exception {
        File file = TestFiles.randomFile(dir, "b.bin", 100_000, 3);
        InformationMetadata meta = SecureManager.createFileMetadata(file, 32 * 1024);
        String good = InformationMetadata.toJsonString(meta, null);
        InformationMetadata.fromJsonString(good); // sanity

        // chunk count that does not match size / chunkSize
        expectRejected(good.replace("\"fileSize\":100000", "\"fileSize\":900000000"));
        // absurd chunk size
        expectRejected(good.replace("\"chunkSize\":32768", "\"chunkSize\":1"));
        // non-hex hash
        expectRejected(good.replaceFirst("[0-9a-f]{64}", "zzzz"));
        // negative size
        expectRejected(good.replace("\"fileSize\":100000", "\"fileSize\":-5"));
    }

    private void expectRejected(String json) {
        try {
            InformationMetadata.fromJsonString(json);
            fail("hostile manifest was accepted: " + json.substring(0, Math.min(80, json.length())));
        } catch (JSONException expected) {
            // good
        }
    }

    @Test
    public void remoteFileNameCannotEscapeTheReceiveDirectory() {
        assertEquals("passwd", InformationMetadata.sanitizeFileName("../../etc/passwd"));
        assertEquals("evil.bin", InformationMetadata.sanitizeFileName("C:\\Windows\\evil.bin"));
        assertEquals("shared.bin", InformationMetadata.sanitizeFileName(".."));
        assertEquals("shared.bin", InformationMetadata.sanitizeFileName(""));
        assertEquals("shared.bin", InformationMetadata.sanitizeFileName(null));
        assertEquals("fw_1.0.zip", InformationMetadata.sanitizeFileName("fw:1.0.zip".replace(':', '_')));
    }

    @Test
    public void unreadableOrEmptyFilesYieldNoManifest() throws Exception {
        assertNull(SecureManager.createFileMetadata(new File(dir, "missing.bin"), 4096));
        File empty = new File(dir, "empty.bin");
        assertTrue(empty.createNewFile());
        assertNull(SecureManager.createFileMetadata(empty, 4096));
    }

    @Test
    public void bitsetEncodingRoundTrips() {
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(0);
        bits.set(7);
        bits.set(300);
        assertEquals(bits, SecureManager.convertStringToBitSet(SecureManager.convertBitSetToString(bits)));
        assertTrue(SecureManager.convertStringToBitSet("%%%not-base64%%%").isEmpty());
        assertFalse(SecureManager.convertStringToBitSet(null).get(0));
    }
}
