package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.network.HttpServer;
import com.datalogic.largeshareapp.security.Identity;
import com.datalogic.largeshareapp.storage.StorageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Serves whatever chunks this node holds. A leecher starts serving as soon as it has the manifest,
 * so every downloader is also a source (the swarm fans out laterally). Only chunks that are
 * verified and on disk are ever offered.
 */
public class SeedManager {
    private static final String TAG = "SeedManager";
    public static final int DEFAULT_MAX_CONNECTIONS = 24;

    private final SwarmSession mSession;
    private final Identity mIdentity;
    private final int mPort;
    private final int mMaxConnections;
    private final Set<PeersTrackingListener> mPeersTrackingListeners = new CopyOnWriteArraySet<>();
    private volatile HttpServer mHttpServer;

    private static final int MAX_SEEN_PEERS = 512;
    private static final int MAX_PEERS_PER_RESPONSE = 32;
    private static final long SEEN_PEER_TTL_MS = 5 * 60_000L;
    /** Devices that told us they serve chunks too ("ip:port" to last-seen time), for peer exchange. */
    private final Map<String, Long> mSeenPeers = new ConcurrentHashMap<>();

    /** Serialised signed manifest, built once because it can be hundreds of kilobytes. */
    private volatile byte[] mManifestBytes;
    private volatile InformationMetadata mManifestFor;

    private final HttpServer.Handler mHandler = new HttpServer.Handler() {
        @Override
        public byte[] getMetadata() {
            InformationMetadata info = mSession.info();
            if (info == null) {
                return null;
            }
            byte[] cached = mManifestBytes;
            if (cached != null && mManifestFor == info) {
                return cached;
            }
            try {
                byte[] bytes = InformationMetadata.toJsonString(info, mIdentity.signManifest(info))
                        .getBytes(StandardCharsets.UTF_8);
                mManifestBytes = bytes;
                mManifestFor = info;
                return bytes;
            } catch (JSONException e) {
                Log.e(TAG, "Failed to serialize manifest", e);
                return null;
            }
        }

        @Override
        public byte[] getBitset() {
            BitSetMetadata bits = mSession.bits();
            InformationMetadata info = mSession.info();
            if (bits == null || info == null) {
                return null;
            }
            try {
                return BitSetMetadata.toJsonString(bits, info.getFileHash()).getBytes(StandardCharsets.UTF_8);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to serialize bitset", e);
                return null;
            }
        }

        @Override
        public long getChunkLength(int chunkId) {
            BitSetMetadata bits = mSession.bits();
            InformationMetadata info = mSession.info();
            if (bits == null || info == null || !bits.isAvailable(chunkId)) {
                return -1;
            }
            return info.getChunkLength(chunkId);
        }

        @Override
        public void writeChunk(int chunkId, OutputStream out) throws IOException {
            StorageManager storage = mSession.storage();
            if (storage == null) {
                throw new IOException("Storage closed");
            }
            storage.transferChunk(chunkId, out);
        }

        @Override
        public void onPeerSeen(String ip, int port) {
            long now = System.currentTimeMillis();
            if (mSeenPeers.size() >= MAX_SEEN_PEERS) {
                mSeenPeers.values().removeIf(t -> now - t > SEEN_PEER_TTL_MS);
                if (mSeenPeers.size() >= MAX_SEEN_PEERS) {
                    return; // bounded memory: a flood of announcements cannot grow it
                }
            }
            mSeenPeers.put(ip + ":" + port, now);
        }

        @Override
        public byte[] getPeers(String requesterIp, int requesterServePort) {
            long now = System.currentTimeMillis();
            List<String> candidates = new ArrayList<>();
            for (Map.Entry<String, Long> e : mSeenPeers.entrySet()) {
                String address = e.getKey();
                if (now - e.getValue() <= SEEN_PEER_TTL_MS
                        && !address.equals(requesterIp + ":" + requesterServePort)) {
                    candidates.add(address);
                }
            }
            Collections.shuffle(candidates);
            JSONArray array = new JSONArray();
            for (int i = 0; i < candidates.size() && i < MAX_PEERS_PER_RESPONSE; i++) {
                array.put(candidates.get(i));
            }
            try {
                return new JSONObject().put("peers", array).toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException e) {
                return null;
            }
        }

        @Override
        public byte[] getGroupStatus(String deviceId, boolean leave) {
            GroupStatusProvider provider = mGroupStatusProvider;
            return provider == null ? null : provider.status(deviceId, leave);
        }

        @Override
        public boolean postProgress(String authenticatedDeviceId, String body) {
            try {
                JSONObject json = new JSONObject(body);
                String deviceId = json.getString("deviceId");
                // A device may only report its own progress: the id is bound to the fleet MAC.
                if (!deviceId.equals(authenticatedDeviceId)) {
                    Log.w(TAG, "Progress for " + deviceId + " rejected: authenticated as " + authenticatedDeviceId);
                    return false;
                }
                int completed = json.getInt("completed");
                int total = json.getInt("total");
                if (total <= 0 || completed < 0 || completed > total) {
                    return false;
                }
                notifyToPeersTrackingListener(l -> l.onProgressUpdated(deviceId, completed, total));
                return true;
            } catch (JSONException e) {
                Log.e(TAG, "Malformed progress payload", e);
                return false;
            }
        }
    };

    public SeedManager(SwarmSession session, Identity identity, int port, int maxConnections) {
        mSession = session;
        mIdentity = identity;
        mPort = port;
        mMaxConnections = maxConnections;
    }

    public boolean initialize() {
        HttpServer server = new HttpServer(mPort, mIdentity, mHandler, mMaxConnections);
        try {
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server: " + e.getMessage());
            return false;
        }
        mHttpServer = server;
        return true;
    }

    public int getPort() {
        HttpServer server = mHttpServer;
        return server != null ? server.getLocalPort() : mPort;
    }

    public HttpServer getServer() {
        return mHttpServer;
    }

    public void stop() {
        HttpServer server = mHttpServer;
        mHttpServer = null;
        if (server != null) {
            server.stop();
        }
    }

    public interface PeersTrackingListener {
        void onProgressUpdated(String deviceId, int completed, int total);
    }

    /** Answers member slot leases while this device owns a Wi-Fi Direct group. */
    public interface GroupStatusProvider {
        /** JSON status for the device, or null when no group is owned. */
        byte[] status(String deviceId, boolean leave);
    }

    private volatile GroupStatusProvider mGroupStatusProvider;

    public void setGroupStatusProvider(GroupStatusProvider provider) {
        mGroupStatusProvider = provider;
    }

    public void addPeersTrackingListener(PeersTrackingListener listener) {
        if (listener != null) {
            mPeersTrackingListeners.add(listener);
        }
    }

    public void removePeersTrackingListener(PeersTrackingListener listener) {
        if (listener != null) {
            mPeersTrackingListeners.remove(listener);
        }
    }

    private interface PeersTrackingListenerConsumer {
        void accept(PeersTrackingListener listener);
    }

    private void notifyToPeersTrackingListener(PeersTrackingListenerConsumer consumer) {
        for (PeersTrackingListener listener : mPeersTrackingListeners) {
            try {
                consumer.accept(listener);
            } catch (RuntimeException e) {
                Log.e(TAG, "PeersTrackingListener failed", e);
            }
        }
    }
}
