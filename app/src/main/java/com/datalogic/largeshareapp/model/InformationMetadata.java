package com.datalogic.largeshareapp.model;

import androidx.annotation.NonNull;

import com.datalogic.largeshareapp.manager.SecureManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * The authoritative manifest of a shared payload: size, fixed chunk size, ordered per-chunk SHA-256
 * hashes and the whole-file SHA-256. Manifests received from the network are untrusted, so parsing
 * validates every field before it is used to size a file or index an array.
 */
public class InformationMetadata {
    private static final String FILE_NAME_KEY = "fileName";
    private static final String FILE_SIZE_KEY = "fileSize";
    private static final String FILE_HASH_KEY = "fileHash";
    private static final String CHUNK_SIZE_KEY = "chunkSize";
    private static final String CHUNK_HASHES_KEY = "chunkHashes";
    private static final String SIGNATURE_KEY = "sig";

    public static final int MIN_CHUNK_SIZE = 4 * 1024;
    public static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;
    public static final int MAX_CHUNKS = 400_000;
    private static final int HASH_HEX_LENGTH = 64;
    private static final int MAX_FILE_NAME_LENGTH = 128;

    private final String fileName;
    private final long fileSize;
    private final String fileHash;
    private final int chunkSize;
    private final String[] chunkHashes;
    /** Where the payload lives on the root device. Local only, never serialized. */
    private File sourceFile;
    /** HMAC over the canonical manifest, present on manifests received from the network. */
    private String signature;

    public InformationMetadata(String fileName, long fileSize, int chunkSize,
                               String[] chunkHashes, String fileHash) {
        this.fileName = sanitizeFileName(fileName);
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.chunkSize = chunkSize;
        this.chunkHashes = chunkHashes == null ? new String[0] : chunkHashes;
    }

    /** Keeps only a plain file name: a remote peer must never choose a directory to write into. */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "shared.bin";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < MAX_FILE_NAME_LENGTH; i++) {
            char c = base.charAt(i);
            sb.append(c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' ? '_' : c);
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
            return "shared.bin";
        }
        return clean;
    }

    public static InformationMetadata fromJsonString(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        long fileSize = json.getLong(FILE_SIZE_KEY);
        int chunkSize = json.getInt(CHUNK_SIZE_KEY);
        String fileHash = json.getString(FILE_HASH_KEY);
        JSONArray array = json.getJSONArray(CHUNK_HASHES_KEY);

        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new JSONException("Invalid chunk size: " + chunkSize);
        }
        if (fileSize <= 0) {
            throw new JSONException("Invalid file size: " + fileSize);
        }
        long expectedChunks = (fileSize + chunkSize - 1) / chunkSize;
        if (expectedChunks > MAX_CHUNKS || array.length() != expectedChunks) {
            throw new JSONException("Chunk count " + array.length() + " does not match size "
                    + fileSize + " / " + chunkSize);
        }
        if (!isHexHash(fileHash)) {
            throw new JSONException("Invalid file hash");
        }
        String[] chunkHashes = new String[array.length()];
        for (int i = 0; i < chunkHashes.length; i++) {
            chunkHashes[i] = array.getString(i);
            if (!isHexHash(chunkHashes[i])) {
                throw new JSONException("Invalid chunk hash at " + i);
            }
        }
        InformationMetadata meta = new InformationMetadata(json.optString(FILE_NAME_KEY, null),
                fileSize, chunkSize, chunkHashes, fileHash);
        meta.signature = json.optString(SIGNATURE_KEY, null);
        return meta;
    }

    public static String toJsonString(InformationMetadata informationMetadata, String signature)
            throws JSONException {
        if (informationMetadata == null) {
            return "{}";
        }
        JSONArray chunkHashesArray = new JSONArray();
        for (String hash : informationMetadata.getChunkHashes()) {
            chunkHashesArray.put(hash);
        }
        JSONObject json = new JSONObject();
        json.put(FILE_NAME_KEY, informationMetadata.getFileName());
        json.put(FILE_SIZE_KEY, informationMetadata.getFileSize());
        json.put(FILE_HASH_KEY, informationMetadata.getFileHash());
        json.put(CHUNK_SIZE_KEY, informationMetadata.getChunkSize());
        json.put(CHUNK_HASHES_KEY, chunkHashesArray);
        if (signature != null) {
            json.put(SIGNATURE_KEY, signature);
        }
        return json.toString();
    }

    private static boolean isHexHash(String s) {
        if (s == null || s.length() != HASH_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Deterministic text the manifest signature is computed over (independent of JSON ordering). */
    public String canonicalString() {
        return fileName + "\n" + fileSize + "\n" + chunkSize + "\n" + fileHash + "\n"
                + SecureManager.sha256Hex(String.join(",", chunkHashes).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    public String getFileName() {
        return fileName;
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public InformationMetadata withSourceFile(File file) {
        this.sourceFile = file;
        return this;
    }

    public String getSignature() {
        return signature;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFileHash() {
        return fileHash;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public String[] getChunkHashes() {
        return chunkHashes;
    }

    public int getChunkCount() {
        return chunkHashes.length;
    }

    /** Length in bytes of one chunk; the final chunk is usually shorter than the others. */
    public int getChunkLength(int index) {
        long offset = (long) index * chunkSize;
        return (int) Math.min(chunkSize, fileSize - offset);
    }

    /** First characters of the file hash, advertised in discovery to keep unrelated swarms apart. */
    public String swarmTag() {
        return fileHash.length() >= 16 ? fileHash.substring(0, 16) : fileHash;
    }

    @NonNull
    @Override
    public String toString() {
        return "InformationMetadata{" + FILE_NAME_KEY + "=" + fileName
                + ", " + FILE_SIZE_KEY + "=" + fileSize
                + ", " + FILE_HASH_KEY + "=" + fileHash
                + ", " + CHUNK_SIZE_KEY + "=" + chunkSize
                + ", chunks=" + chunkHashes.length + '}';
    }
}
