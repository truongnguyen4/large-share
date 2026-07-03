package com.datalogic.largeshareapp.model;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.Serializable;

public class InformationMetadata implements Serializable {
    private final File fileShared; // File object representing the shared file
    private final long fileSize; // Size of the file in bytes
    private final String fileHash; // SHA-256 for the entire file
    private final int chunkSize; // Size of each chunk in bytes
    private final String[] chunkHashes; // SHA-256 hashes for each chunk


    public InformationMetadata(File fileShared, long fileSize, int chunkSize,
                               String[] chunkHashes, String fileHash) {
        this.fileShared = fileShared;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.chunkSize = chunkSize;
        this.chunkHashes = chunkHashes;
    }

    public static InformationMetadata fromJsonString(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        File fileShared = new File(json.getString("fileShared"));
        long fileSize = json.getLong("fileSize");
        String fileHash = json.optString("fileHash", "");
        int chunkSize = json.getInt("chunkSize");
        String[] chunkHashes;
        {
            JSONArray array = json.getJSONArray("chunkHashes");
            chunkHashes = new String[array.length()];
            for (int i = 0; i < array.length(); i++) {
                chunkHashes[i] = array.getString(i);
            }
        }
        return new InformationMetadata(fileShared, fileSize, chunkSize, chunkHashes, fileHash);
    }

    public static String toJsonString(InformationMetadata informationMetadata) throws JSONException {
        if (informationMetadata == null) {
            return "{}";
        }
        JSONObject json = new JSONObject();
        JSONArray chunkHashesArray;
        {
            chunkHashesArray = new JSONArray();
            for (String hash : informationMetadata.getChunkHashes()) {
                chunkHashesArray.put(hash);
            }
        }
        json.put("fileShared", informationMetadata.getFileShared().getAbsolutePath());
        json.put("fileSize", informationMetadata.getFileSize());
        json.put("fileHash", informationMetadata.getFileHash());
        json.put("chunkSize", informationMetadata.getChunkSize());
        json.put("chunkHashes", chunkHashesArray);
        return json.toString();
    }

    public File getFileShared() {
        return fileShared;
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
        if (chunkHashes == null) {
            return new String[0];
        }
        return chunkHashes;
    }

    @NonNull
    @Override
    public String toString() {
        return "InformationMetadata{" +
                "fileShared=" + fileShared +
                ", fileSize=" + fileSize +
                ", fileHash='" + fileHash + '\'' +
                ", chunkSize=" + chunkSize +
                ", chunkHashes=" + (chunkHashes != null ? String.join(", ", chunkHashes) : "null") +
                '}';
    }
}
