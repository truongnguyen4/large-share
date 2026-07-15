package com.datalogic.largeshareapp.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.datalogic.largeshareapp.model.InformationMetadata;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class HttpServer {
    private static final String TAG = "HttpServer";

    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final int RESPONSE_ERROR_UNSUPPORTED = 303;
    public static final int RESPONSE_ERROR_TIMEOUT = 403;
    public static final int RESPONSE_ERROR_REQUEST_CORRUPTED = 400;
    public static final int RESPONSE_SUCCESS = 200;

    private final int EXECUTOR_CAPABILITY = 10;
    private final int REQUEST_TIMEOUT = 5000;
    private final int mPort;

    private ServerSocket mServerSocket;
    private ExecutorService mServerExecutor = Executors.newFixedThreadPool(EXECUTOR_CAPABILITY);;
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);

    private InformationMetadata mMetadata;
    private Supplier<byte[]> mGetMetadataResponse;
    private Supplier<byte[]> mGetBitsetResponse;
    private Function<Integer, byte[]> mGetChunkResponse;
    private Consumer<String> mPostProgressResponse;
    private volatile ServerListener mServerListener;

    public interface ServerListener {
        void onProgressReportReceived(String deviceId, String ip, int completed, int total, int failures);
    }

    public HttpServer(int port) {
        this.mPort = port;
    }

    public void setServerListener(ServerListener listener) {
        this.mServerListener = listener;
    }

    public synchronized void start(InformationMetadata metadata,
                                   Supplier<byte[]> getMetadataResponse,
                                   Supplier<byte[]> getBitsetResponse,
                                   Function<Integer, byte[]> getChunkResponse,
                                   Consumer<String> postProgressResponse)
            throws IOException, IllegalArgumentException {
        if (metadata == null || getMetadataResponse == null
            || getBitsetResponse == null || getChunkResponse == null) {
            throw new IllegalArgumentException("Metadata and response suppliers must not be null");
        }

        if (mIsRunning.get()) {
            return;
        }

        this.mMetadata = metadata;
        this.mGetMetadataResponse = getMetadataResponse;
        this.mGetBitsetResponse = getBitsetResponse;
        this.mGetChunkResponse = getChunkResponse;
        this.mPostProgressResponse = postProgressResponse;

        // Limiting thread pool to avoid overwhelming CPU/network
        mServerSocket = new ServerSocket(mPort);
        mIsRunning.set(true);

        Runnable loop = () -> {
            while (mIsRunning.get()) {
                try {
                    Socket clientSocket = mServerSocket.accept();
                    if (mServerExecutor == null || mServerExecutor.isShutdown()) {
                        clientSocket.close();
                    }
                    mServerExecutor.submit(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (mIsRunning.get()) {
                        Log.e(TAG, "Error accepting client connection", e);
                    }
                }
            }
        };
        new Thread(loop, "HttpServer-Listener").start();
        Log.d(TAG, "HTTP Server started on port " + mPort);
    }

    public synchronized void stop() {
        if (!mIsRunning.get()) {
            return;
        }

        mIsRunning.set(false);

        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            mServerSocket = null;
        }

        if (mServerExecutor != null) {
            mServerExecutor.shutdownNow();
            mServerExecutor = null;
        }

        Log.d(TAG, "HTTP Server stopped.");
    }

    private void handleClient(Socket socket) {
        try (socket) {
            socket.setSoTimeout(REQUEST_TIMEOUT);
            socket.setTcpNoDelay(true);
            String clientIp = socket.getInetAddress() != null
                    ? socket.getInetAddress().getHostAddress() : "";
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            // Reuse the same reader/stream so one TCP connection can serve many
            // requests (HTTP keep-alive) instead of a fresh connection per chunk.
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            while (mIsRunning.get()) {
                boolean keepAlive;
                try {
                    keepAlive = handleRequest(reader, os, clientIp);
                    os.flush();
                } catch (SocketTimeoutException e) {
                    // Idle persistent connection: no further request arrived in time.
                    break;
                }
                if (!keepAlive) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling client socket", e);
        }
    }

    private boolean handleRequest(BufferedReader reader, OutputStream os, String clientIp) throws Exception {
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return false; // peer closed the connection
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            responseCode(os, RESPONSE_ERROR_REQUEST_CORRUPTED, "Bad Request", false);
            return false;
        }

        String method = parts[0];
        String path = parts[1];
        String query = "";
        int queryIndex = path.indexOf('?');
        if (queryIndex != -1) {
            query = path.substring(queryIndex + 1);
            path = path.substring(0, queryIndex);
        }

        // Consume headers, capturing the body length and the peer's keep-alive preference.
        int contentLength = 0;
        boolean keepAlive = true;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Malformed Content-Length header: " + line);
                }
            } else if (lower.startsWith("connection:")) {
                keepAlive = !lower.contains("close");
            }
        }

        switch (method) {
            case GET:{
                switch (path) {
                    case "/metadata": {
                        response(os, mGetMetadataResponse, keepAlive);
                        break;
                    }
                    case "/bitset": {
                        response(os, mGetBitsetResponse, keepAlive);
                        break;
                    }
                    case "/chunk": {
                        int chunkId = Integer.parseInt(query.replace("id=", ""));
                        response(os, () -> mGetChunkResponse.apply(chunkId), keepAlive);
                        break;
                    }
                    default: {
                        Log.e(TAG, "Unsupported GET path: " + path);
                        responseCode(os, RESPONSE_ERROR_UNSUPPORTED, "Not Found", false);
                        return false;
                    }
                }
                break;
            }
            case POST:{
                String body = preprocessPostRequest(reader, contentLength);
                switch (path) {
                    case "/progress": {
                        mPostProgressResponse.accept(body);
                        responseCode(os, RESPONSE_SUCCESS, "OK", keepAlive);
                        break;
                    }
                    case "/status": {
                        Log.d(TAG, "Have not implemented!!!");
                        responseCode(os, RESPONSE_ERROR_UNSUPPORTED, "Not Found", false);
                        return false;
                    }
                    default: {
                        Log.e(TAG, "Unsupported POST path: " + path);
                        responseCode(os, RESPONSE_ERROR_UNSUPPORTED, "Not Found", false);
                        return false;
                    }
                }
                break;
            }
            default: {
                Log.e(TAG, "Unsupported HTTP method: " + method);
                responseCode(os, 501, "Not Implemented", false);
                return false;
            }
        }
        return keepAlive;
    }

    private String preprocessPostRequest(BufferedReader reader, int contentLength) throws IOException {
        if (contentLength <= 0) {
            return "";
        }

        // Read exactly contentLength characters of the body.
        char[] bodyChars = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = reader.read(bodyChars, read, contentLength - read);
            if (r == -1) {
                break;
            }
            read += r;
        }
        return new String(bodyChars, 0, read);
    }

    private void response(OutputStream os, Supplier<byte[]> getResponse, boolean keepAlive) throws Exception {
        if (mMetadata == null) {
            responseCode(os, 500, "Metadata not available", false);
            return;
        }

        byte[] bytes = getResponse.get();

        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";

        os.write(responseHeaders.getBytes("UTF-8"));
        os.write(bytes);
    }

    private void responseCode(OutputStream os, int code, String msg, boolean keepAlive) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n" +
                "Content-Length: " + msg.length() + "\r\n" +
                "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n" + msg;
        os.write(resp.getBytes("UTF-8"));
    }

}
