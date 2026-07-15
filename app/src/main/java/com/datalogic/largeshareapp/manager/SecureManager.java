package com.datalogic.largeshareapp.manager;

import android.util.Base64;
import android.util.Log;

import com.datalogic.largeshareapp.model.InformationMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

public class SecureManager {
    private static final String TAG = "SecureManager";
    public static final int CHUNK_SIZE_524KB = 524 * 1024;
    public static String computeSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "SHA-256 computation failed", e);
            return "";
        }
    }

    public static InformationMetadata createFileMetadata(File fileShared, int chunkSize) {
        if (fileShared == null) {
            Log.e(TAG, "Invalid file URI or size");
            return null;
        }
        Log.d(TAG, "Creating file metadata for: " + fileShared + ", size: " + fileShared.length() + ", chunkSize: " + chunkSize);

        if (chunkSize <= 0) {
            chunkSize = CHUNK_SIZE_524KB;
        }

        int chunks = (int) Math.ceil((double) fileShared.length() / chunkSize);
        String[] chunkHashes = new String[chunks];
        try {
            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
            InputStream is = new FileInputStream(fileShared);

            byte[] buffer = new byte[chunkSize];
            int index = 0;
            
            while (index < chunks) {
                int bytesRead = 0;
                while (bytesRead < chunkSize) {
                    int read = is.read(buffer, bytesRead, chunkSize - bytesRead);
                    if (read == -1) {
                        break;
                    }
                    bytesRead += read;
                }
                
                if (bytesRead == 0) {
                    break;
                }
                
                // Trim trailing zero space if this is the last chunk
                byte[] chunkData;
                if (bytesRead == chunkSize) {
                    chunkData = buffer;
                } else {
                    chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                }
                
                // Update file-level digest with the final chunk bytes to calculate total checksum
                fileDigest.update(chunkData, 0, bytesRead);
                
                String chunkHash = computeSHA256(chunkData);
                chunkHashes[index++] = chunkHash;
            }
            is.close();

            // Correct array size if actual chunk count read differs from estimated size (e.g. truncated)
            if (index < chunks) {
                String[] trimmedHashes = new String[index];
                System.arraycopy(chunkHashes, 0, trimmedHashes, 0, index);
                chunkHashes = trimmedHashes;
            }

            byte[] fileHashBytes = fileDigest.digest();
            String fileHash = SecureManager.computeSHA256(fileHashBytes);
            return new InformationMetadata(fileShared, fileShared.length(), chunkSize, chunkHashes, fileHash);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute metadata and total checksum from content provider stream", e);
            return null;
        }
    }

    public static String convertBitSetToString(BitSet bitSet) {
        if (bitSet == null) {
            Log.e(TAG, "bitSet is null");
            return "";
        }
        byte[] bitSetArray = bitSet.toByteArray();
        return Base64.encodeToString(bitSetArray, Base64.NO_WRAP);
    }

    public static BitSet convertStringToBitSet(String bitmaskStr) {
        if (bitmaskStr == null) {
            Log.e(TAG, "bitmaskStr is null");
            return new BitSet();
        }
        byte[] bitmaskArray = Base64.decode(bitmaskStr, Base64.NO_WRAP);
        return BitSet.valueOf(bitmaskArray);
    }

    public static BitSet findAvailableBits(BitSet current, BitSet reference) {
        if (current == null || reference == null) {
            Log.e(TAG, "One or both bitmasks are null");
            return new BitSet();
        }

        BitSet result = (BitSet) current.clone();
        // XOR operation to find differences between current and reference
        result.xor(reference);
        // AND operation to find available pieces in reference that are not in current
        result.and(reference);
        return result;
    }
}
