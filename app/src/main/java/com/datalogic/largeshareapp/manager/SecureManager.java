package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.InformationMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.BitSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hashing, HMAC and manifest-building helpers. */
public final class SecureManager {
    private static final String TAG = "SecureManager";
    /** Fixed chunk size from the invention disclosure: 512 KB = 524,288 bytes. */
    public static final int CHUNK_SIZE_512KB = 512 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SecureManager() {}

    public interface HashProgress {
        void onProgress(int chunksDone, int totalChunks);
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return sha256Hex(data, 0, data.length);
    }

    public static String sha256Hex(byte[] data, int offset, int length) {
        MessageDigest digest = newSha256();
        digest.update(data, offset, length);
        return toHex(digest.digest());
    }

    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    public static String hmacSha256Hex(byte[] key, String text) {
        return toHex(hmacSha256(key, text.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static InformationMetadata createFileMetadata(File fileShared, int chunkSize) {
        return createFileMetadata(fileShared, chunkSize, null);
    }

    /**
     * Partitions the file into fixed-size chunks in a single streaming pass, computing the
     * per-chunk SHA-256 hashes and the whole-file SHA-256 at the same time.
     */
    public static InformationMetadata createFileMetadata(File fileShared, int chunkSize,
                                                         HashProgress progress) {
        if (fileShared == null || !fileShared.isFile() || fileShared.length() <= 0) {
            Log.e(TAG, "Invalid file to share: " + fileShared);
            return null;
        }
        if (chunkSize <= 0) {
            chunkSize = CHUNK_SIZE_512KB;
        }
        long size = fileShared.length();
        long chunkCountLong = (size + chunkSize - 1) / chunkSize;
        if (chunkCountLong > InformationMetadata.MAX_CHUNKS) {
            Log.e(TAG, "File too large for chunk size: " + size);
            return null;
        }
        int chunks = (int) chunkCountLong;
        Log.d(TAG, "Creating metadata for " + fileShared + ", size=" + size + ", chunks=" + chunks);

        String[] chunkHashes = new String[chunks];
        MessageDigest fileDigest = newSha256();
        MessageDigest chunkDigest = newSha256();
        byte[] buffer = new byte[chunkSize];
        try (InputStream in = new FileInputStream(fileShared)) {
            for (int i = 0; i < chunks; i++) {
                int want = (int) Math.min(chunkSize, size - (long) i * chunkSize);
                int got = 0;
                while (got < want) {
                    int read = in.read(buffer, got, want - got);
                    if (read < 0) {
                        throw new IOException("File shrank while hashing at chunk " + i);
                    }
                    got += read;
                }
                fileDigest.update(buffer, 0, want);
                chunkDigest.update(buffer, 0, want);
                chunkHashes[i] = toHex(chunkDigest.digest());
                if (progress != null) {
                    progress.onProgress(i + 1, chunks);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to compute metadata for " + fileShared, e);
            return null;
        }
        if (fileShared.length() != size) {
            Log.e(TAG, "File changed while hashing: " + fileShared);
            return null;
        }
        return new InformationMetadata(fileShared.getName(), size, chunkSize, chunkHashes,
                toHex(fileDigest.digest())).withSourceFile(fileShared);
    }

    public static String convertBitSetToString(BitSet bitSet) {
        if (bitSet == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(bitSet.toByteArray());
    }

    public static BitSet convertStringToBitSet(String bitmaskStr) {
        if (bitmaskStr == null || bitmaskStr.isEmpty()) {
            return new BitSet();
        }
        try {
            return BitSet.valueOf(Base64.getDecoder().decode(bitmaskStr));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Malformed bitset payload", e);
            return new BitSet();
        }
    }
}
