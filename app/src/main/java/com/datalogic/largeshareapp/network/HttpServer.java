package com.datalogic.largeshareapp.network;

import android.util.Log;

import com.datalogic.largeshareapp.security.Identity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal embedded HTTP/1.1 server with persistent connections.
 *
 * <p>Built for many simultaneous leechers on constrained hardware:
 * <ul>
 *   <li>admission control: at most {@code maxConnections} connections are served at once; extra
 *       clients immediately get HTTP 503 and move on to another peer instead of queueing;</li>
 *   <li>bounded request parsing (line length, header count, body size, total request time) so a
 *       malformed or slow-loris client cannot pin a thread or exhaust memory;</li>
 *       <li>every request must carry a valid fleet MAC, every response is MAC'd back (mutual
 *       authentication), and clients that repeatedly fail authentication are temporarily banned;</li>
 *   <li>a reaper closes connections whose response write has stalled.</li>
 * </ul>
 */
public class HttpServer {
    private static final String TAG = "HttpServer";

    private static final int MAX_LINE = 2048;
    private static final int MAX_HEADERS = 40;
    private static final int MAX_BODY = 4096;
    private static final long MAX_REQUEST_READ_MS = 10_000L;
    private static final int MAX_REQUESTS_PER_CONNECTION = 2000;
    private static final long WRITE_STALL_LIMIT_MS = 30_000L;
    private static final int AUTH_FAIL_LIMIT = 5;
    private static final long AUTH_FAIL_WINDOW_MS = 60_000L;
    private static final long BAN_MS = 60_000L;

    /** What the server serves. Implementations must be thread-safe. */
    public interface Handler {
        /** Signed manifest JSON, or null while this node does not have it yet. */
        byte[] getMetadata();

        /** Bitset JSON of the chunks this node holds, or null while it has no session. */
        byte[] getBitset();

        /** Length of the chunk if this node holds it, otherwise -1. */
        long getChunkLength(int chunkId);

        /** Streams a chunk previously reported by {@link #getChunkLength}. */
        void writeChunk(int chunkId, OutputStream out) throws IOException;

        /** A progress report from an authenticated device. Returns false if it is malformed. */
        boolean postProgress(String authenticatedDeviceId, String body);

        /** An authenticated device told us it serves on {@code ip:port}: remember it for peer exchange. */
        void onPeerSeen(String ip, int port);

        /** JSON list of other peers, for peer exchange; never includes the requester itself. */
        byte[] getPeers(String requesterIp, int requesterServePort);

        /**
         * Group owner slot lease for the authenticated device (Wi-Fi Direct swarm). Null when this
         * node does not own a group.
         */
        default byte[] getGroupStatus(String deviceId, boolean leave) {
            return null;
        }
    }

    private static final class Connection {
        final Socket socket;
        volatile long writeStartedAt;

        Connection(Socket socket) {
            this.socket = socket;
        }
    }

    private static final class FailureRecord {
        int count;
        long windowStart;
        long bannedUntil;
    }

    private final int mPort;
    private final Identity mIdentity;
    private final Handler mHandler;
    private final int mMaxConnections;
    private final Semaphore mSlots;
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final Set<Connection> mConnections = ConcurrentHashMap.newKeySet();
    private final Map<String, FailureRecord> mFailures = new ConcurrentHashMap<>();

    private ServerSocket mServerSocket;
    private ThreadPoolExecutor mWorkers;
    private ThreadPoolExecutor mRejectors;
    private Thread mReaper;

    public final AtomicLong bytesServed = new AtomicLong();
    public final AtomicLong chunksServed = new AtomicLong();
    public final AtomicLong rejectedBusy = new AtomicLong();
    public final AtomicLong rejectedAuth = new AtomicLong();
    public final AtomicInteger activeConnections = new AtomicInteger();

    public HttpServer(int port, Identity identity, Handler handler, int maxConnections) {
        mPort = port;
        mIdentity = identity;
        mHandler = handler;
        mMaxConnections = Math.max(1, maxConnections);
        mSlots = new Semaphore(mMaxConnections);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public synchronized void start() throws IOException {
        if (mIsRunning.get()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(mPort), 128);
        mServerSocket = socket;

        mWorkers = new ThreadPoolExecutor(mMaxConnections, mMaxConnections, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), daemonFactory("http-worker-"));
        mWorkers.allowCoreThreadTimeOut(true);
        mRejectors = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), daemonFactory("http-reject-"));
        mRejectors.allowCoreThreadTimeOut(true);
        mIsRunning.set(true);

        Thread acceptor = new Thread(this::acceptLoop, "HttpServer-Listener");
        acceptor.setDaemon(true);
        acceptor.start();
        mReaper = new Thread(this::reapLoop, "HttpServer-Reaper");
        mReaper.setDaemon(true);
        mReaper.start();
        Log.d(TAG, "HTTP server started on port " + getLocalPort() + " (max " + mMaxConnections
                + " connections)");
    }

    public int getLocalPort() {
        ServerSocket socket = mServerSocket;
        return socket != null ? socket.getLocalPort() : mPort;
    }

    public synchronized void stop() {
        if (!mIsRunning.getAndSet(false)) {
            return;
        }
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing server socket", e);
        }
        mServerSocket = null;
        for (Connection c : mConnections) {
            closeQuietly(c.socket);
        }
        mConnections.clear();
        if (mWorkers != null) {
            mWorkers.shutdownNow();
        }
        if (mRejectors != null) {
            mRejectors.shutdownNow();
        }
        if (mReaper != null) {
            mReaper.interrupt();
        }
        Log.d(TAG, "HTTP server stopped. served=" + chunksServed.get() + " chunks, "
                + bytesServed.get() + " bytes, busyRejects=" + rejectedBusy.get()
                + ", authRejects=" + rejectedAuth.get());
    }

    private void acceptLoop() {
        while (mIsRunning.get()) {
            Socket client;
            try {
                client = mServerSocket.accept();
            } catch (IOException e) {
                if (mIsRunning.get()) {
                    Log.e(TAG, "Error accepting client connection", e);
                }
                continue;
            } catch (NullPointerException e) {
                return; // server socket was cleared by stop()
            }

            if (isBanned(remoteIp(client))) {
                rejectedAuth.incrementAndGet();
                closeQuietly(client);
                continue;
            }
            if (!mSlots.tryAcquire()) {
                rejectBusy(client);
                continue;
            }
            try {
                mWorkers.execute(() -> {
                    Connection conn = new Connection(client);
                    mConnections.add(conn);
                    activeConnections.incrementAndGet();
                    try {
                        handleClient(conn);
                    } finally {
                        activeConnections.decrementAndGet();
                        mConnections.remove(conn);
                        closeQuietly(client);
                        mSlots.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                mSlots.release();
                closeQuietly(client);
            }
        }
    }

    /** Politely refuses an excess client without occupying a worker thread. */
    private void rejectBusy(Socket client) {
        rejectedBusy.incrementAndGet();
        try {
            mRejectors.execute(() -> {
                try {
                    client.setSoTimeout(300);
                    OutputStream os = client.getOutputStream();
                    os.write(("HTTP/1.1 503 Busy\r\nRetry-After: 2\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    client.shutdownOutput();
                    // Drain the request so closing does not RST away our answer.
                    InputStream is = client.getInputStream();
                    byte[] sink = new byte[1024];
                    is.read(sink);
                } catch (IOException ignored) {
                    // best effort
                } finally {
                    closeQuietly(client);
                }
            });
        } catch (RejectedExecutionException e) {
            closeQuietly(client);
        }
    }

    private void reapLoop() {
        while (mIsRunning.get()) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            Iterator<Connection> it = mConnections.iterator();
            while (it.hasNext()) {
                Connection c = it.next();
                long started = c.writeStartedAt;
                if (started > 0 && now - started > WRITE_STALL_LIMIT_MS) {
                    Log.w(TAG, "Closing connection with stalled write: " + c.socket.getRemoteSocketAddress());
                    closeQuietly(c.socket);
                }
            }
            mFailures.values().removeIf(r -> r.bannedUntil < now && now - r.windowStart > AUTH_FAIL_WINDOW_MS);
        }
    }

    // ---------------------------------------------------------------- request handling

    private void handleClient(Connection conn) {
        Socket socket = conn.socket;
        try {
            socket.setSoTimeout(HttpUtils.IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            String clientIp = remoteIp(socket);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            for (int served = 0; mIsRunning.get() && served < MAX_REQUESTS_PER_CONNECTION; served++) {
                boolean keepAlive;
                try {
                    keepAlive = handleRequest(conn, in, out, clientIp);
                } catch (SocketTimeoutException e) {
                    break; // idle persistent connection
                }
                if (!keepAlive) {
                    break;
                }
            }
        } catch (IOException e) {
            if (mIsRunning.get()) {
                Log.d(TAG, "Client connection ended: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error handling client", e);
        }
    }

    private boolean handleRequest(Connection conn, InputStream in, OutputStream out, String clientIp)
            throws IOException {
        // The first byte may take up to the idle timeout; the rest of the request must arrive fast.
        String requestLine = readLine(in, 0);
        if (requestLine == null) {
            return false; // peer closed the connection
        }
        long deadline = System.currentTimeMillis() + MAX_REQUEST_READ_MS;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            writeSimple(out, 400, "Bad Request", null, false);
            return false;
        }
        String method = parts[0];
        String target = parts[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 0; ; i++) {
            String line = readLine(in, deadline);
            if (line == null) {
                return false;
            }
            if (line.isEmpty()) {
                break;
            }
            if (i >= MAX_HEADERS) {
                writeSimple(out, 431, "Too Many Headers", null, false);
                return false;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }

        boolean keepAlive = !"close".equalsIgnoreCase(headers.get("connection"));
        int contentLength = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl);
            } catch (NumberFormatException e) {
                writeSimple(out, 400, "Bad Content-Length", null, false);
                return false;
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY) {
            writeSimple(out, 413, "Payload Too Large", null, false);
            return false;
        }
        byte[] body = new byte[contentLength];
        readFully(in, body, deadline);

        // ---- authentication: nothing is served to a device that cannot prove fleet membership
        boolean isPost = HttpUtils.POST.equals(method);
        String authId = headers.get(Identity.HDR_ID.toLowerCase());
        String nonce = headers.get(Identity.HDR_NONCE.toLowerCase());
        Identity.Verdict verdict = mIdentity.verifyRequest(authId,
                headers.get(Identity.HDR_TS.toLowerCase()), nonce,
                headers.get(Identity.HDR_SIG.toLowerCase()), method, target, isPost);
        if (verdict != Identity.Verdict.OK) {
            rejectedAuth.incrementAndGet();
            recordAuthFailure(clientIp);
            Log.w(TAG, "Rejected " + method + " " + target + " from " + clientIp + ": " + verdict);
            writeSimple(out, 401, "Unauthorized: " + verdict, nonce, false);
            return false;
        }

        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        // An authenticated device that serves chunks tells us where, so it can be shared with others.
        int announcedPort = 0;
        String servePort = headers.get(HttpUtils.HDR_SERVE_PORT.toLowerCase());
        if (servePort != null) {
            try {
                int port = Integer.parseInt(servePort);
                if (port > 0 && port < 65536) {
                    announcedPort = port;
                    mHandler.onPeerSeen(clientIp, port);
                }
            } catch (NumberFormatException ignored) {
                // malformed header: ignore
            }
        }

        if (HttpUtils.GET.equals(method)) {
            switch (path) {
                case HttpUtils.GetEndpoints.GROUP:
                    return respondBytes(out, mHandler.getGroupStatus(authId, query.contains("leave=1")), nonce, keepAlive);
                case HttpUtils.GetEndpoints.PEERS:
                    return respondBytes(out, mHandler.getPeers(clientIp, announcedPort), nonce, keepAlive);
                case HttpUtils.GetEndpoints.METADATA:
                    return respondBytes(out, mHandler.getMetadata(), nonce, keepAlive);
                case HttpUtils.GetEndpoints.BITSET:
                    return respondBytes(out, mHandler.getBitset(), nonce, keepAlive);
                case HttpUtils.GetEndpoints.CHUNK:
                    return respondChunk(conn, out, query, nonce, keepAlive);
                default:
                    writeSimple(out, 404, "Not Found", nonce, keepAlive);
                    return keepAlive;
            }
        }
        if (isPost && HttpUtils.PostEndpoints.PROGRESS.equals(path)) {
            boolean ok = mHandler.postProgress(authId, new String(body, StandardCharsets.UTF_8));
            writeSimple(out, ok ? 200 : 400, ok ? "OK" : "Bad Request", nonce, keepAlive);
            return keepAlive;
        }
        writeSimple(out, 404, "Not Found", nonce, keepAlive);
        return keepAlive;
    }

    private boolean respondBytes(OutputStream out, byte[] bytes, String nonce, boolean keepAlive)
            throws IOException {
        if (bytes == null) {
            writeSimple(out, 503, "Not ready", nonce, keepAlive);
            return keepAlive;
        }
        writeHeader(out, 200, "OK", "application/json", bytes.length, nonce, keepAlive);
        out.write(bytes);
        out.flush();
        return keepAlive;
    }

    private boolean respondChunk(Connection conn, OutputStream out, String query, String nonce,
                                 boolean keepAlive) throws IOException {
        int chunkId;
        try {
            chunkId = Integer.parseInt(query.replace("id=", ""));
        } catch (NumberFormatException e) {
            writeSimple(out, 400, "Bad chunk id", nonce, keepAlive);
            return keepAlive;
        }
        long length = mHandler.getChunkLength(chunkId);
        if (length < 0) {
            writeSimple(out, 404, "Chunk not available", nonce, keepAlive);
            return keepAlive;
        }
        conn.writeStartedAt = System.currentTimeMillis();
        try {
            writeHeader(out, 200, "OK", "application/octet-stream", length, nonce, keepAlive);
            mHandler.writeChunk(chunkId, out);
            out.flush();
        } finally {
            conn.writeStartedAt = 0;
        }
        bytesServed.addAndGet(length);
        chunksServed.incrementAndGet();
        return keepAlive;
    }

    private void writeHeader(OutputStream out, int status, String reason, String contentType,
                             long length, String requestNonce, boolean keepAlive) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        if (requestNonce != null) {
            sb.append(Identity.HDR_SRV).append(": ").append(mIdentity.deviceId()).append("\r\n");
            sb.append(Identity.HDR_SRV_SIG).append(": ")
                    .append(mIdentity.signResponse(requestNonce, status, length)).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeSimple(OutputStream out, int status, String message, String requestNonce,
                             boolean keepAlive) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeader(out, status, message.split(":")[0], "text/plain", body.length, requestNonce, keepAlive);
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- bounded reads

    /** Reads one CRLF-terminated line of at most MAX_LINE bytes; null on clean end of stream. */
    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toString("ISO-8859-1");
            }
            if (b == '\n') {
                String line = buf.toString("ISO-8859-1");
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            buf.write(b);
            if (buf.size() > MAX_LINE) {
                throw new IOException("Request line too long");
            }
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                throw new IOException("Request took too long");
            }
        }
    }

    private static void readFully(InputStream in, byte[] dest, long deadline) throws IOException {
        int read = 0;
        while (read < dest.length) {
            int n = in.read(dest, read, dest.length - read);
            if (n < 0) {
                throw new IOException("Unexpected end of request body");
            }
            read += n;
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Request took too long");
            }
        }
    }

    // ---------------------------------------------------------------- abuse protection

    private static String remoteIp(Socket socket) {
        return socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "";
    }

    private boolean isBanned(String ip) {
        FailureRecord r = mFailures.get(ip);
        return r != null && r.bannedUntil > System.currentTimeMillis();
    }

    private void recordAuthFailure(String ip) {
        long now = System.currentTimeMillis();
        mFailures.compute(ip, (k, r) -> {
            if (r == null || now - r.windowStart > AUTH_FAIL_WINDOW_MS) {
                r = new FailureRecord();
                r.windowStart = now;
            }
            r.count++;
            if (r.count >= AUTH_FAIL_LIMIT) {
                r.bannedUntil = now + BAN_MS;
                Log.w(TAG, "Banning " + ip + " for " + (BAN_MS / 1000) + "s after repeated auth failures");
            }
            return r;
        });
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
