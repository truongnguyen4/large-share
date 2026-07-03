package com.datalogic.largeshareapp.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.datalogic.largeshareapp.model.InformationMetadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

public class HttpServer {
    private static final String TAG = "HttpServer";

    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final int ERROR_UNSUPPORTED = 303;
    public static final int ERROR_TIMEOUT = 403;
    public static final int ERROR_REQUEST_CORRUPTED = 400;

    private final int EXECUTOR_CAPABILITY = 10;
    private final int REQUEST_TIMEOUT = 5000;
    private final int mPort;

    private ServerSocket mServerSocket;
    private ExecutorService mServerExecutor = Executors.newFixedThreadPool(EXECUTOR_CAPABILITY);;
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private InformationMetadata mMetadata;
    private Supplier<byte[]> mGetMetadataResponse;
    private Supplier<byte[]> mGetBitsetResponse;
    private Function<Integer, byte[]> mGetChunkResponse;

    public interface ServerListener {
        void onProgressReportReceived(String deviceId, String ip, int completed, int total, int failures);
    }

    public HttpServer(int port) {
        this.mPort = port;
    }

    public synchronized void start(InformationMetadata metadata,
                                   Supplier<byte[]> getMetadataResponse,
                                   Supplier<byte[]> getBitsetResponse,
                                   Function<Integer, byte[]> getChunkResponse)
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
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                responseError(os, ERROR_REQUEST_CORRUPTED, "Bad Request");
                return;
            }

            String method = parts[0];
            String path = parts[1];
            String query = "";
            int queryIndex = path.indexOf('?');
            if (queryIndex != -1) {
                query = path.substring(queryIndex + 1);
                path = path.substring(0, queryIndex);
            }

            // Consume headers
            {
                String line;
                while (!(line = reader.readLine()).isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        Integer.parseInt(line.substring(15).trim());
                    }
                }
            }

            switch (method) {
                case GET:{
                    switch (path) {
                        case "/metadata": {
                            response(os, mGetMetadataResponse);
                            break;
                        }
                        case "/bitset": {
                            response(os, mGetBitsetResponse);
                            break;
                        }
                        case "/chunk": {
                            int chunkId = Integer.parseInt(query.replace("id=", ""));
                            response(os, mGetChunkResponse, chunkId);
                            break;
                        }
                        default: {
                            Log.e(TAG, "Unsupported GET path: " + path);
                            responseError(os, ERROR_UNSUPPORTED, "Not Found");
                        }
                    }
                    break;
                }
                case POST:{
                    switch (path) {
                        case "/progress": {
                            Log.d(TAG, "Have not implemented!!!");
                            responseError(os, ERROR_UNSUPPORTED, "Not Found");

                            break;
                        }
                        case "/status": {
                            Log.d(TAG, "Have not implemented!!!");
                            responseError(os, ERROR_UNSUPPORTED, "Not Found");
                            break;
                        }
                        default: {
                            Log.e(TAG, "Unsupported POST path: " + path);
                            responseError(os, ERROR_UNSUPPORTED, "Not Found");
                        }
                    }
                    break;
                }
                default: {
                    Log.e(TAG, "Unsupported HTTP method: " + method);
                    responseError(os, 501, "Not Implemented");
                }
            }
            os.flush();
        } catch (Exception e) {
            Log.e(TAG, "Error closing client socket", e);
        }
    }

    private void response(OutputStream os, Function<Integer, byte[]> getResponse, int chunkId) throws Exception {
        if (mMetadata == null) {
            responseError(os, 500, "Metadata not available");
            return;
        }

        byte[] bytes = getResponse.apply(chunkId);

        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";

        os.write(responseHeaders.getBytes("UTF-8"));
        os.write(bytes);
    }

    private void response(OutputStream os, Supplier<byte[]> getResponse) throws Exception {
        if (mMetadata == null) {
            responseError(os, 500, "Metadata not available");
            return;
        }

        byte[] bytes = getResponse.get();

        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";

        os.write(responseHeaders.getBytes("UTF-8"));
        os.write(bytes);
    }

    private void responseError(OutputStream os, int code, String msg) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n" +
                "Content-Length: " + msg.length() + "\r\n" +
                "Connection: close\r\n\r\n" + msg;
        os.write(resp.getBytes("UTF-8"));
    }

//    private void sendBitmapResponse(OutputStream os) throws Exception {
//        int total = storageManager.getTotalChunks();
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < total; i++) {
//            sb.append(storageManager.isChunkPossessed(i) ? '1' : '0');
//        }
//
//        byte[] bytes = sb.toString().getBytes("UTF-8");
//        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
//                "Content-Type: text/plain\r\n" +
//                "Content-Length: " + bytes.length + "\r\n" +
//                "Connection: close\r\n\r\n";
//
//        os.write(responseHeaders.getBytes("UTF-8"));
//        os.write(bytes);
//    }

//    private void handleChunkRequest(String path, OutputStream os) throws Exception {
//        // Query syntax: /chunk?id=49 or /chunk/random (sends a random chunk)
//        int chunkId = -1;
//        if (path.contains("id=")) {
//            try {
//                String idStr = path.substring(path.indexOf("id=") + 3);
//                if (idStr.contains("&")) {
//                    idStr = idStr.substring(0, idStr.indexOf('&'));
//                }
//                chunkId = Integer.parseInt(idStr);
//            } catch (Exception e) {
//                sendErrorResponse(os, 400, "Invalid Chunk ID parameter");
//                return;
//            }
//        } else if (path.contains("random")) {
//            // Priority constraint: Distributor must send a random chunk to avoid jam
//            // Choose a random chunk from possessed ones
//            int total = storageManager.getTotalChunks();
//            int attempts = 0;
//            while (attempts < 100) {
//                int r = random.nextInt(total);
//                if (storageManager.isChunkPossessed(r)) {
//                    chunkId = r;
//                    break;
//                }
//                attempts++;
//            }
//            if (chunkId == -1) {
//                // fallback to finding the first possessed chunk
//                for (int i = 0; i < total; i++) {
//                    if (storageManager.isChunkPossessed(i)) {
//                        chunkId = i;
//                        break;
//                    }
//                }
//            }
//        }
//
//        if (chunkId == -1 || !storageManager.isChunkPossessed(chunkId)) {
//            sendErrorResponse(os, 404, "Chunk not possessed/available: " + chunkId);
//            return;
//        }
//
//        byte[] chunkData = storageManager.readChunk(chunkId);
//        if (chunkData == null) {
//            sendErrorResponse(os, 500, "Failed to read chunk data");
//            return;
//        }
//
//        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
//                "Content-Type: application/octet-stream\r\n" +
//                "Content-Length: " + chunkData.length + "\r\n" +
//                "X-Chunk-Index: " + chunkId + "\r\n" +
//                "Connection: close\r\n\r\n";
//
//        os.write(responseHeaders.getBytes("UTF-8"));
//        os.write(chunkData);
//    }

//    private void handleProgressPost(BufferedReader reader, int contentLength, OutputStream os, String clientIp)
//            throws Exception {
//        if (contentLength <= 0) {
//            sendErrorResponse(os, 400, "Missing content length for updates");
//            return;
//        }
//
//        char[] bodyChars = new char[contentLength];
//        int read = 0;
//        while (read < contentLength) {
//            int r = reader.read(bodyChars, read, contentLength - read);
//            if (r == -1)
//                break;
//            read += r;
//        }
//
//        String body = new String(bodyChars);
//        try {
//            JSONObject json = new JSONObject(body);
//            String deviceId = json.getString("deviceId");
//            int completed = json.getInt("completed");
//            int total = json.getInt("total");
//            int failures = json.getInt("failures");
//
//            if (listener != null) {
//                mainHandler
//                        .post(() -> listener.onProgressReportReceived(deviceId, clientIp, completed, total, failures));
//            }
//
//            String response = "{\"status\":\"ok\"}";
//            byte[] respBytes = response.getBytes("UTF-8");
//            String responseHeaders = "HTTP/1.1 200 OK\r\n" +
//                    "Content-Type: application/json\r\n" +
//                    "Content-Length: " + respBytes.length + "\r\n" +
//                    "Connection: close\r\n\r\n";
//            os.write(responseHeaders.getBytes("UTF-8"));
//            os.write(respBytes);
//        } catch (Exception e) {
//            sendErrorResponse(os, 400, "Malformed json body");
//        }
//    }


}
