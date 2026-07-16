package com.datalogic.largeshareapp.network;

import android.util.Log;

import com.datalogic.largeshareapp.manager.PerformanceTracker;
import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.BitSetMetadata;
import com.datalogic.largeshareapp.model.InformationMetadata;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

public class HttpClient {
    private static final String TAG = "HttpClient";

    public static InformationMetadata fetchInformationMetadata(String ip, int port) throws Exception {
        String urlString = HttpUtils.buildUrl(ip, port, HttpUtils.GetEndpoints.METADATA);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HttpUtils.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HttpUtils.READ_TIMEOUT_MS);
        conn.setRequestMethod(HttpUtils.GET);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            return null;
        }

        InputStream is = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        
        is.close();
        conn.disconnect();
        
        String json = baos.toString(StandardCharsets.UTF_8);
        return InformationMetadata.fromJsonString(json);
    }

    public static BitSet fetchBitSet(String ip, int port) throws Exception {
        String urlString = HttpUtils.buildUrl(ip, port, HttpUtils.GetEndpoints.BITSET);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HttpUtils.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HttpUtils.READ_TIMEOUT_MS);
        conn.setRequestMethod(HttpUtils.GET);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP bitset error: " + code);
        }

        InputStream is = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }

        is.close();
        conn.disconnect();

        String json = baos.toString(StandardCharsets.UTF_8).trim();
        if (json.isEmpty()) {
            return new BitSet();
        }
        JSONObject jsonObject = new JSONObject(json);
        String bitSetStr = jsonObject.optString(BitSetMetadata.AVAILABLE_CHUNKS_KEY, "");
        return SecureManager.convertStringToBitSet(bitSetStr);
    }

    /**
     * Fetch raw binary bytes of a specific chunk.
     */
    public static byte[] fetchChunk(String ip, int port, int chunkId) throws Exception {
        String urlString = HttpUtils.buildUrl(ip, port, HttpUtils.GetEndpoints.CHUNK + "?id=" + chunkId);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HttpUtils.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HttpUtils.READ_TIMEOUT_MS);
        conn.setRequestMethod(HttpUtils.GET);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            drainErrorStream(conn);
            throw new Exception("HTTP chunk error " + code + " for chunk " + chunkId);
        }

        int contentLength = conn.getContentLength();
        InputStream is = conn.getInputStream();

        // Use an sized stream to avoid unnecessary growing allocation
        ByteArrayOutputStream baos = contentLength > 0
                ? new ByteArrayOutputStream(contentLength)
                : new ByteArrayOutputStream(SecureManager.CHUNK_SIZE_524KB);

        byte[] buffer = new byte[SecureManager.CHUNK_SIZE_524KB];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        PerformanceTracker.endRecord("readChunkResponse-" + chunkId, System.currentTimeMillis());

        is.close();
        return baos.toByteArray();
    }

    /**
     * Drains and closes the error stream so the underlying connection can be
     * reused by the keep-alive pool instead of being discarded.
     */
    private static void drainErrorStream(HttpURLConnection conn) {
        InputStream es = conn.getErrorStream();
        try (es) {
            if (es == null) {
                return;
            }
            byte[] buffer = new byte[2048];
            while (es.read(buffer) != -1) {
                // discard
            }
        } catch (Exception ignored) {
            // best-effort drain
        }
    }

    /**
     * Send progress status reporting to Root device.
     */
    public static void reportProgress(String ip, int port, String deviceId, int completed, int total) throws Exception {
        String urlString = HttpUtils.buildUrl(ip, port, HttpUtils.PostEndpoints.PROGRESS);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HttpUtils.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HttpUtils.READ_TIMEOUT_MS);
        conn.setRequestMethod(HttpUtils.POST);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("deviceId", deviceId);
        payload.put("completed", completed);
        payload.put("total", total);

        byte[] postBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(postBytes);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "Failed posting progress update to Root: " + code);
        }
        conn.disconnect();
    }
}
