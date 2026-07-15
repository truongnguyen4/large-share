package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class SeedManager {
    private static final String TAG = "SeedManager";
    private final int mPortConnection = 8888;
    private HttpServer mHttpServer;
    private StorageManager mStorageManager;

    public SeedManager(StorageManager storageManager) {
        this.mStorageManager = storageManager;
    }

    public boolean initialize() {
        mHttpServer = new HttpServer(mPortConnection);
        try {
            InformationMetadata metadata = SharedData.getInstance().instanceInformationMetadata;
            mHttpServer.start(metadata,
                                mGetMetadataResponseCallback,
                                mGetBitsetResponseCallback,
                                mGetChunkResponseCallback,
                                mPostProgressResponseCallback);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** Stops serving chunks and releases the underlying HTTP server. */
    public void stop() {
        if (mHttpServer != null) {
            mHttpServer.stop();
            mHttpServer = null;
        }
    }

    private Supplier<byte[]> mGetMetadataResponseCallback = () -> {
        try {
            InformationMetadata metadata = SharedData.getInstance().instanceInformationMetadata;
            if (metadata == null) {
                return new byte[0];
            }
            String metadataJson = InformationMetadata.toJsonString(metadata);
            return metadataJson.getBytes();
        } catch (JSONException e) {
            return new byte[0];
        }
    };

    private Supplier<byte[]> mGetBitsetResponseCallback = () -> {
        try {
            BitSetMetadata bitSetMetadata = SharedData.getInstance().instanceBitSetMetadata;
            if (bitSetMetadata == null) {
                return new byte[0];
            }
            String bitSetJson = BitSetMetadata.toJsonString(bitSetMetadata);
            return bitSetJson.getBytes();
        } catch (JSONException e) {
            return new byte[0];
        }
    };

    private Function<Integer, byte[]> mGetChunkResponseCallback = (chunkIndex) -> {
        try {
            PerformanceTracker.startRecord("responseChunk" + chunkIndex, System.currentTimeMillis());
            byte[] chunkData = mStorageManager.readChunk(chunkIndex);
            if (chunkData == null) {
                Log.e(TAG, "Chunk data is null for index: " + chunkIndex);
                return new byte[0];
            }
            PerformanceTracker.endRecord("responseChunk" + chunkIndex, System.currentTimeMillis());
            return chunkData;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read chunk data for index: " + chunkIndex, e);
            return new byte[0];
        }
    };

    private Consumer<String> mPostProgressResponseCallback = (body) -> {
        try {
            // Parse the JSON body to extract progress information
            JSONObject json = new JSONObject(body);
            String deviceId = json.getString("deviceId");
            int completed = json.getInt("completed");
            int total = json.getInt("total");
            int failures = json.getInt("failures");

            // Handle the progress report (e.g., update UI, log, etc.)
            Log.i(TAG, "Progress report received from device: " + deviceId +
                    ", completed: " + completed + ", total: " + total + ", failures: " + failures);

            // You can add additional logic here to handle the progress report as needed

        } catch (JSONException e) {
            Log.e(TAG, "Malformed progress payload: " + body, e);
        }
    };
}
