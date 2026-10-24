package com.datalogic.largeshareapp.storage;

import android.util.Log;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.BitSet;

/**
 * Chunk-level file I/O. All reads and writes are positional ({@code pread}/{@code pwrite}) so any
 * number of threads can serve or store chunks concurrently without sharing a file position, and
 * chunks can arrive in any order from any peer.
 *
 * <p>A leecher writes into "&lt;name&gt;.part" and only renames it to its final name after the
 * whole-file SHA-256 matches, so a half-downloaded or corrupt file is never visible as complete.
 * A small ".state" sidecar records the verified chunks so an interrupted download can resume.
 */
public class StorageManager implements Closeable {
    private static final String TAG = "StorageManager";
    private static final int IO_BUFFER = 64 * 1024;

    private final InformationMetadata mMetadata;
    private final boolean mLeech;
    private final File mTargetFile;
    private final File mStateFile;
    private final FileChannel mChannel;
    private volatile File mCurrentFile;
    private volatile boolean mClosed;
    private volatile boolean mFinalized;

    private StorageManager(InformationMetadata metadata, File target, File work, File state,
                           FileChannel channel, boolean leech) {
        mMetadata = metadata;
        mTargetFile = target;
        mCurrentFile = work;
        mStateFile = state;
        mChannel = channel;
        mLeech = leech;
    }

    /** Opens an existing, complete file for serving (the root device, or a finished leecher). */
    public static StorageManager forSource(InformationMetadata metadata, File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Source file missing: " + source);
        }
        if (source.length() != metadata.getFileSize()) {
            throw new IOException("Source file size " + source.length()
                    + " differs from manifest " + metadata.getFileSize());
        }
        FileChannel channel = FileChannel.open(source.toPath(), StandardOpenOption.READ);
        return new StorageManager(metadata, source, source, null, channel, false);
    }

    /** Opens (or resumes) the pre-sized work file for a download. */
    public static StorageManager forLeech(InformationMetadata metadata, File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent);
        }
        File work = new File(parent, target.getName() + ".part");
        File state = new File(parent, target.getName() + ".state");
        boolean reuse = work.isFile() && work.length() == metadata.getFileSize();
        if (!reuse) {
            state.delete();
            if (parent != null && parent.getUsableSpace() < metadata.getFileSize()) {
                throw new IOException("Not enough free space for " + metadata.getFileSize() + " bytes");
            }
            try (RandomAccessFile raf = new RandomAccessFile(work, "rw")) {
                raf.setLength(metadata.getFileSize());
            }
        }
        FileChannel channel = FileChannel.open(work.toPath(),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        return new StorageManager(metadata, target, work, state, channel, true);
    }

    public File getFile() {
        return mCurrentFile;
    }

    public int chunkLength(int index) {
        return mMetadata.getChunkLength(index);
    }

    private void checkIndex(int index) throws IOException {
        if (index < 0 || index >= mMetadata.getChunkCount()) {
            throw new IOException("Chunk index out of range: " + index);
        }
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int n = mChannel.read(buffer, position);
            if (n < 0) {
                throw new EOFException("Unexpected end of file at " + position);
            }
            position += n;
        }
    }

    public byte[] readChunk(int index) throws IOException {
        checkIndex(index);
        byte[] data = new byte[chunkLength(index)];
        readFully(ByteBuffer.wrap(data), (long) index * mMetadata.getChunkSize());
        return data;
    }

    /** Streams one chunk to {@code out} with a small buffer instead of materialising 512 KB. */
    public void transferChunk(int index, OutputStream out) throws IOException {
        checkIndex(index);
        long position = (long) index * mMetadata.getChunkSize();
        int remaining = chunkLength(index);
        byte[] array = new byte[Math.min(IO_BUFFER, remaining)];
        ByteBuffer buffer = ByteBuffer.wrap(array);
        while (remaining > 0) {
            buffer.clear();
            buffer.limit(Math.min(array.length, remaining));
            int want = buffer.remaining();
            readFully(buffer, position);
            out.write(array, 0, want);
            position += want;
            remaining -= want;
        }
    }

    public void writeChunk(int index, byte[] data) throws IOException {
        if (!mLeech) {
            throw new IOException("Storage is read-only");
        }
        checkIndex(index);
        if (data.length != chunkLength(index)) {
            throw new IOException("Chunk " + index + " has " + data.length
                    + " bytes, expected " + chunkLength(index));
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = (long) index * mMetadata.getChunkSize();
        while (buffer.hasRemaining()) {
            position += mChannel.write(buffer, position);
        }
    }

    /** Layer 1: compare the chunk's SHA-256 with the manifest entry. */
    public boolean verifyChunk(int index, byte[] data) {
        if (index < 0 || index >= mMetadata.getChunkCount() || data == null
                || data.length != chunkLength(index)) {
            return false;
        }
        return SecureManager.sha256Hex(data).equalsIgnoreCase(mMetadata.getChunkHashes()[index]);
    }

    /** Layer 2: recompute the SHA-256 of the whole reassembled file and compare with the manifest. */
    public boolean verifyWholeFile() throws IOException {
        MessageDigest digest = SecureManager.newSha256();
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        long position = 0;
        long size = mMetadata.getFileSize();
        while (position < size) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), size - position));
            int want = buffer.remaining();
            readFully(buffer, position);
            digest.update(buffer.array(), 0, want);
            position += want;
        }
        boolean ok = SecureManager.toHex(digest.digest()).equalsIgnoreCase(mMetadata.getFileHash());
        if (!ok) {
            Log.e(TAG, "Whole-file SHA-256 mismatch for " + mCurrentFile);
        }
        return ok;
    }

    /** Re-hashes every chunk on disk and returns the ones that no longer match the manifest. */
    public BitSet findCorruptChunks() throws IOException {
        BitSet corrupt = new BitSet(mMetadata.getChunkCount());
        for (int i = 0; i < mMetadata.getChunkCount(); i++) {
            if (!verifyChunk(i, readChunk(i))) {
                corrupt.set(i);
            }
        }
        return corrupt;
    }

    /** Reads the resume sidecar and returns only the chunks that still verify against the manifest. */
    public BitSet loadResumeState() {
        BitSet verified = new BitSet(mMetadata.getChunkCount());
        if (!mLeech || mStateFile == null || !mStateFile.isFile()) {
            return verified;
        }
        try {
            String text = new String(Files.readAllBytes(mStateFile.toPath()), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(text);
            if (!mMetadata.getFileHash().equalsIgnoreCase(json.optString("fileHash"))) {
                return verified;
            }
            BitSet claimed = SecureManager.convertStringToBitSet(json.optString("available"));
            for (int i = claimed.nextSetBit(0); i >= 0 && i < mMetadata.getChunkCount();
                 i = claimed.nextSetBit(i + 1)) {
                if (verifyChunk(i, readChunk(i))) {
                    verified.set(i);
                }
            }
            Log.d(TAG, "Resuming with " + verified.cardinality() + "/" + mMetadata.getChunkCount()
                    + " verified chunks");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Ignoring unreadable resume state", e);
            verified.clear();
        }
        return verified;
    }

    /** Atomically persists which chunks are verified so a restart can resume. */
    public void persistState(BitSet available) {
        if (!mLeech || mStateFile == null || mClosed || mFinalized) {
            return; // a finished download has nothing left to resume
        }
        try {
            JSONObject json = new JSONObject();
            json.put("fileHash", mMetadata.getFileHash());
            json.put("available", SecureManager.convertBitSetToString(available));
            File tmp = new File(mStateFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            moveReplacing(tmp, mStateFile);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to persist resume state", e);
        }
    }

    /**
     * Flushes the work file to disk and atomically publishes it under its final name. The open
     * channel stays valid across the rename, so the device keeps serving chunks without a gap.
     */
    public void finalizeDownload() throws IOException {
        if (!mLeech) {
            return;
        }
        mChannel.force(true);
        moveReplacing(mCurrentFile, mTargetFile);
        mCurrentFile = mTargetFile;
        mFinalized = true;
        if (mStateFile != null) {
            mStateFile.delete();
        }
        Log.d(TAG, "Download finalized: " + mTargetFile);
    }

    private static void moveReplacing(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        mClosed = true;
        try {
            mChannel.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing file channel", e);
        }
    }
}
