package com.datalogic.largeshareapp.storage;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.NoSuchAlgorithmException;

public class StorageManager {
    private static final String TAG = "StorageManager";
    private final InformationMetadata mMetadata;
    private final Object fileLock = new Object();
    private FileChannel mFileChannel;

    public StorageManager(InformationMetadata metadata) {
        mMetadata = metadata;
        if (!initializeSharedFile()) {
            throw new IllegalStateException("Failed to initialize shared file for metadata: " + metadata.getFileShared());
        }
    }

    private boolean initializeSharedFile() {
        try {
            File fileShared = mMetadata.getFileShared();
            if (fileShared == null) {
                Log.e(TAG, "File shared is null.");
                return false;
            }
            if (!fileShared.exists()) {
                File parentDir = fileShared.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        Log.e(TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
                        return false;
                    }
                }
            }
            RandomAccessFile raf = new RandomAccessFile(fileShared, "rw");
            mFileChannel = raf.getChannel();
            mFileChannel.truncate(mMetadata.getFileSize());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize shared file: " + e.getMessage(), e);
            return false;
        }
    }

    public byte[] readChunk(int index) throws IOException {
            if (index < 0 || index >= mMetadata.getChunkHashes().length
                || mFileChannel == null) {
                Log.e(TAG, "Invalid chunk index or file not initialized.");
                return new byte[0];
            }

            long offset = (long) index * mMetadata.getChunkSize();
            long sizeToRead = Math.min(mMetadata.getChunkSize(), mMetadata.getFileSize() - offset);
            byte[] chunkData = new byte[(int) sizeToRead];
            
            mFileChannel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(chunkData);
            mFileChannel.read(buffer);
            return chunkData;
    }

    public boolean writeChunk(int index, byte[] data) throws IOException, NoSuchAlgorithmException {
        if (index < 0 || data.length == 0) {
            Log.e(TAG, "Invalid chunk index or empty data for writing.");
            return false;
        }

        synchronized (fileLock) {
            if (mFileChannel == null) return false;
            long offset = (long) index * mMetadata.getChunkSize();
            mFileChannel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            int bytesWritten = mFileChannel.write(buffer);
        }
        return true;
    }

    public boolean verifyChunk(int index, byte[] data) {
        String[] expectedHashes = mMetadata.getChunkHashes();
        if (index >= expectedHashes.length) {
            Log.e(TAG, "Chunk index out of bounds for verification: " + index);
            return false;
        }
        String hash = SecureManager.computeSHA256(data);
        String expectedHash = expectedHashes[index];
        if (hash.isEmpty() || expectedHash.isEmpty()
            || !hash.equalsIgnoreCase(expectedHash)) {
            Log.e(TAG, "Chunk hash mismatch for index " + index + ". Expected: " + expectedHash + ", Computed: " + hash);
            return false;
        }
        return true;
    }



    public static String getFileName(Context context, Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        try (cursor) {
            if (cursor == null) {
                Log.w(TAG, "Failed to retrieve file metadata for URI: " + uri);
                return "";
            }
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    return cursor.getString(nameIndex);
                }
            }
        }
        return "";
    }

//    public boolean verifyFullChecksum() {
//        if (possessedChunks == null) return false;
//        for (int i = 0; i < totalChunks; i++) {
//            if (!possessedChunks[i]) return false;
//        }
//
//        try {
//            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
//            byte[] buffer = new byte[8192];
//            synchronized (fileLock) {
//                mRandomAccessFile.seek(0);
//                long bytesRemaining = fileSize;
//                while (bytesRemaining > 0) {
//                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
//                    int read = mRandomAccessFile.read(buffer, 0, toRead);
//                    if (read == -1) break;
//                    fileDigest.update(buffer, 0, read);
//                    bytesRemaining -= read;
//                }
//            }
//            byte[] fileHashBytes = fileDigest.digest();
//            String fileHashHex = bytesToHex(fileHashBytes);
//
//            if (totalChecksum != null && !totalChecksum.isEmpty()) {
//                Log.d(TAG, "Full file checksum check. Expected: " + totalChecksum + ", Computed: " + fileHashHex);
//                return fileHashHex.equalsIgnoreCase(totalChecksum);
//            } else {
//                Log.w(TAG, "totalChecksum is not available in FileMetadata, fallback to segment check.");
//                if (expectedHashes == null) return true;
//
//                // Fallback to checking segment by segment
//                MessageDigest chunkDigest = MessageDigest.getInstance("SHA-256");
//                synchronized (fileLock) {
//                    for (int i = 0; i < totalChunks; i++) {
//                        mRandomAccessFile.seek((long) i * chunkSize);
//                        long bytesToRead = Math.min(chunkSize, fileSize - ((long) i * chunkSize));
//                        chunkDigest.reset();
//                        long readSoFar = 0;
//                        while (readSoFar < bytesToRead) {
//                            int toRead = (int) Math.min(buffer.length, bytesToRead - readSoFar);
//                            int read = mRandomAccessFile.read(buffer, 0, toRead);
//                            if (read == -1) break;
//                            chunkDigest.update(buffer, 0, read);
//                            readSoFar += read;
//                        }
//                        String currentHash = bytesToHex(chunkDigest.digest());
//                        if (expectedHashes[i] != null && !currentHash.equalsIgnoreCase(expectedHashes[i])) {
//                            Log.e(TAG, "Full verification failed at chunk " + i);
//                            return false;
//                        }
//                    }
//                }
//            }
//            return true;
//        } catch (Exception e) {
//            Log.e(TAG, "Full checksum verification failed with error", e);
//            return false;
//        }
//    }

    public void close() {
        synchronized (fileLock) {
            if (mFileChannel != null) {
                try {
                    mFileChannel.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing file channel", e);
                }
                mFileChannel = null;
            }
        }
    }



}
