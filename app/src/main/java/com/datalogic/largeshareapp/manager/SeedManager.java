package com.datalogic.largeshareapp.manager;
import android.util.Log;

import java.util.Set;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.SharedData;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class SeedManager {
    private static final String TAG = "SeedManager";
    private HttpServer mHttpServer;
    private StorageManager mStorageManager;
    private final int mPortConnection = 8888;
    private final Set<PeersTrackingListener> mPeersTrackingListeners = new CopyOnWriteArraySet<>();

    private final Supplier<byte[]> mGetMetadataResponseCallback = () -> {
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

    private final Supplier<byte[]> mGetBitsetResponseCallback = () -> {
        try {
            BitSetMetadata bitSetMetadata = SharedData.getInstance().instanceBitSetMetadata;
            return BitSetMetadata.toJsonString(bitSetMetadata).getBytes();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize bitset", e);
            return "{}".getBytes();
        }
    };

    private final Function<Integer, byte[]> mGetChunkResponseCallback = (chunkIndex) -> {
        try {
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

    private final Consumer<String> mPostProgressResponseCallback = (body) -> {
        try {
            JSONObject json = new JSONObject(body);
            String deviceId = json.getString("deviceId");
            int completed = json.getInt("completed");
            int total = json.getInt("total");

            notifyToPeersTrackingListener(listener -> listener.onProgressUpdated(deviceId, completed, total));
        } catch (JSONException e) {
            Log.e(TAG, "Malformed progress payload: " + body, e);
        }
    };

    public SeedManager(StorageManager storageManager) {
        this.mStorageManager = storageManager;
    }

    public boolean initialize() {
        mHttpServer = new HttpServer(mPortConnection);
        try {
            mHttpServer.start(mGetMetadataResponseCallback,
                                mGetBitsetResponseCallback,
                                mGetChunkResponseCallback,
                                mPostProgressResponseCallback);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server: " + e.getMessage());
            return false;
        }
        return true;
    }

    public void stop() {
        if (mHttpServer != null) {
            mHttpServer.stop();
            mHttpServer = null;
        }
    }

    public interface PeersTrackingListener {
        void onProgressUpdated(String deviceId, int completed, int total);
    }

    public void addPeersTrackingListener(PeersTrackingListener listener) {
        if (listener == null) {
            Log.w(TAG, "ProgressListener is null");
            return;
        }
        this.mPeersTrackingListeners.add(listener);
    }

    public void removePeersTrackingListener(PeersTrackingListener listener) {
        if (listener == null) {
            Log.w(TAG, "ProgressListener is null");
            return;
        }
        this.mPeersTrackingListeners.remove(listener);
    }

    private interface PeersTrackingListenerConsumer {
        void accept(PeersTrackingListener listener);
    }

    private void notifyToPeersTrackingListener(PeersTrackingListenerConsumer consumer) {
        Set<PeersTrackingListener> listeners = new CopyOnWriteArraySet<>(mPeersTrackingListeners);
        for (PeersTrackingListener listener : listeners) {
            consumer.accept(listener);
        }
    }
}
