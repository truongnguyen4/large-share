package com.datalogic.largeshareapp.network;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.security.Identity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Talks to one peer at a time. Every request is signed with the fleet MAC, every response must
 * prove the server belongs to the fleet (and, when known, is the device it advertised), and every
 * failure is classified into a {@link PeerException.Kind} so the scheduler can react correctly.
 */
public class HttpClient {
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final int READ_BUFFER = 64 * 1024;

    public static final class BitsetResult {
        public final BitSet bits;
        public final String fileHash;

        BitsetResult(BitSet bits, String fileHash) {
            this.bits = bits;
            this.fileHash = fileHash;
        }
    }

    private final Identity mIdentity;
    private volatile int mAdvertisedPort;

    public HttpClient(Identity identity) {
        mIdentity = identity;
    }

    /**
     * Once this device serves chunks it announces the port on every request, so the peers it talks
     * to can hand its address to others (peer exchange). 0 means "not serving".
     */
    public void setAdvertisedPort(int port) {
        mAdvertisedPort = port;
    }

    /** A group owner's answer to a slot lease. */
    public static final class GroupStatus {
        public final boolean admitted;
        public final int members;
        public final int capacity;

        GroupStatus(boolean admitted, int members, int capacity) {
            this.admitted = admitted;
            this.members = members;
            this.capacity = capacity;
        }
    }

    /** Leases (renews) a member slot of the group owner, or releases it when {@code leave}. */
    public GroupStatus leaseSlot(Peer owner, boolean leave) throws PeerException {
        byte[] body = get(owner, HttpUtils.GetEndpoints.GROUP + (leave ? "?leave=1" : ""), -1, 4096);
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            return new GroupStatus(json.getBoolean("admitted"), json.optInt("members"), json.optInt("capacity"));
        } catch (JSONException e) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "Malformed group status from " + owner, e);
        }
    }

    /** Peer exchange: the other devices a peer has recently seen serving. */
    public List<Peer> fetchPeers(Peer peer) throws PeerException {
        byte[] body = get(peer, HttpUtils.GetEndpoints.PEERS, -1, 256 * 1024);
        List<Peer> found = new ArrayList<>();
        try {
            JSONArray array = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("peers");
            for (int i = 0; i < array.length() && found.size() < 64; i++) {
                String entry = array.getString(i);
                int colon = entry.lastIndexOf(':');
                if (colon <= 0) {
                    continue;
                }
                int port = Integer.parseInt(entry.substring(colon + 1));
                if (port > 0 && port < 65536) {
                    found.add(new Peer(entry.substring(0, colon), port, null, "pex"));
                }
            }
        } catch (JSONException | NumberFormatException e) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "Malformed peer list from " + peer, e);
        }
        return found;
    }

    /** Fetches the manifest and verifies the fleet signature before anything is built from it. */
    public InformationMetadata fetchMetadata(Peer peer) throws PeerException {
        byte[] body = get(peer, HttpUtils.GetEndpoints.METADATA, -1, MAX_JSON_BYTES);
        try {
            InformationMetadata info = InformationMetadata.fromJsonString(
                    new String(body, StandardCharsets.UTF_8));
            if (!mIdentity.verifyManifest(info)) {
                throw new PeerException(PeerException.Kind.IDENTITY,
                        "Manifest signature invalid from " + peer);
            }
            return info;
        } catch (JSONException e) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "Malformed manifest from " + peer, e);
        }
    }

    public BitsetResult fetchBitset(Peer peer) throws PeerException {
        byte[] body = get(peer, HttpUtils.GetEndpoints.BITSET, -1, MAX_JSON_BYTES);
        try {
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            return new BitsetResult(
                    SecureManager.convertStringToBitSet(json.optString(BitSetMetadata.AVAILABLE_CHUNKS_KEY, "")),
                    json.optString(BitSetMetadata.FILE_HASH_KEY, ""));
        } catch (JSONException e) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "Malformed bitset from " + peer, e);
        }
    }

    /** Fetches the raw bytes of one chunk; the length must match what the manifest promises. */
    public byte[] fetchChunk(Peer peer, int chunkId, int expectedLength) throws PeerException {
        return get(peer, HttpUtils.GetEndpoints.CHUNK + "?id=" + chunkId, expectedLength, expectedLength);
    }

    public void reportProgress(Peer root, String deviceId, int completed, int total) throws PeerException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("deviceId", deviceId);
            payload.put("completed", completed);
            payload.put("total", total);
        } catch (JSONException e) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "Cannot encode progress", e);
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        String target = HttpUtils.PostEndpoints.PROGRESS;
        Identity.SignedRequest signed = mIdentity.signRequest(HttpUtils.POST, target);
        HttpURLConnection conn = null;
        boolean clean = false;
        try {
            conn = open(root, HttpUtils.POST, target, signed);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            long length = conn.getContentLengthLong();
            drain(conn);
            checkStatus(conn, root, signed.nonce, code, length);
            clean = true;
        } catch (IOException e) {
            throw networkError(root, e);
        } finally {
            // A fully-read connection goes back to the keep-alive pool; only a broken one is closed.
            if (conn != null && !clean) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------------------------

    private byte[] get(Peer peer, String target, int exactLength, int maxLength) throws PeerException {
        Identity.SignedRequest signed = mIdentity.signRequest(HttpUtils.GET, target);
        HttpURLConnection conn = null;
        boolean clean = false;
        try {
            conn = open(peer, HttpUtils.GET, target, signed);
            int code = conn.getResponseCode();
            long length = conn.getContentLengthLong();
            if (code != HttpURLConnection.HTTP_OK) {
                drain(conn);
                clean = true;
                checkStatus(conn, peer, signed.nonce, code, length);
                throw new PeerException(PeerException.Kind.PROTOCOL, "Unexpected HTTP " + code + " from " + peer);
            }
            checkStatus(conn, peer, signed.nonce, code, length);
            if (length < 0 || length > maxLength || (exactLength >= 0 && length != exactLength)) {
                drain(conn);
                clean = true;
                throw new PeerException(PeerException.Kind.PROTOCOL,
                        "Unexpected length " + length + " from " + peer);
            }
            byte[] data;
            try (InputStream is = conn.getInputStream()) {
                data = readExactly(is, (int) length);
            }
            clean = true;
            return data;
        } catch (IOException e) {
            throw networkError(peer, e);
        } finally {
            // A fully-read connection goes back to the keep-alive pool; only a broken one is closed.
            if (conn != null && !clean) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection open(Peer peer, String method, String target,
                                   Identity.SignedRequest signed) throws IOException {
        URL url = new URL(HttpUtils.buildUrl(peer.ip, peer.port, target));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HttpUtils.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HttpUtils.READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setRequestMethod(method);
        for (Map.Entry<String, String> h : signed.headers.entrySet()) {
            conn.setRequestProperty(h.getKey(), h.getValue());
        }
        int servePort = mAdvertisedPort;
        if (servePort > 0) {
            conn.setRequestProperty(HttpUtils.HDR_SERVE_PORT, Integer.toString(servePort));
        }
        return conn;
    }

    /** Maps an HTTP status (and the server's proof of identity) onto a typed outcome. */
    private void checkStatus(HttpURLConnection conn, Peer peer, String nonce, int code, long length)
            throws PeerException {
        if (code == 503) {
            throw new PeerException(PeerException.Kind.BUSY, peer + " is busy");
        }
        if (code == 401 || code == 403) {
            throw new PeerException(PeerException.Kind.UNAUTHORIZED, peer + " rejected our identity");
        }
        if (code == 404) {
            throw new PeerException(PeerException.Kind.NOT_AVAILABLE, "Not available on " + peer);
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new PeerException(PeerException.Kind.PROTOCOL, "HTTP " + code + " from " + peer);
        }
        String serverId = conn.getHeaderField(Identity.HDR_SRV);
        String serverSig = conn.getHeaderField(Identity.HDR_SRV_SIG);
        if (!mIdentity.verifyResponse(nonce, serverId, code, length, serverSig)) {
            throw new PeerException(PeerException.Kind.IDENTITY,
                    peer + " failed to prove fleet membership");
        }
        if (mIdentity.deviceId().equals(serverId)) {
            throw new PeerException(PeerException.Kind.SELF, peer + " is this device");
        }
        String expected = peer.expectedDeviceId();
        if (expected != null && !expected.equals(serverId)) {
            throw new PeerException(PeerException.Kind.IDENTITY,
                    peer + " answered as " + serverId + " but advertised " + expected);
        }
    }

    private static PeerException networkError(Peer peer, IOException e) {
        String what = e instanceof SocketTimeoutException ? "timed out" : e.getClass().getSimpleName()
                + ": " + e.getMessage();
        return new PeerException(PeerException.Kind.NETWORK, "Network error with " + peer + " (" + what + ")", e);
    }

    private static byte[] readExactly(InputStream is, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = is.read(data, read, Math.min(READ_BUFFER, length - read));
            if (n < 0) {
                throw new IOException("Connection closed after " + read + " of " + length + " bytes");
            }
            read += n;
        }
        return data;
    }

    /** Reads and discards a (small) body so the connection can go back to the keep-alive pool. */
    private static void drain(HttpURLConnection conn) {
        try {
            InputStream es = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (es == null) {
                return;
            }
            try (InputStream stream = es) {
                byte[] sink = new byte[2048];
                int total = 0;
                int n;
                while (total < 64 * 1024 && (n = stream.read(sink)) >= 0) {
                    total += n;
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
