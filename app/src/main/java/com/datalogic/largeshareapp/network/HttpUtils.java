package com.datalogic.largeshareapp.network;

public class HttpUtils {
    public static int READ_TIMEOUT_MS = 5000;
    public static int CONNECT_TIMEOUT_MS = 5000;
    public static int REQUEST_TIMEOUT_MS = 5000;
    public static final String GET = "GET";
    public static final String POST = "POST";

    static final class GetEndpoints {
        public static final String METADATA = "/metadata";
        public static final String BITSET = "/bitset";
        public static final String CHUNK = "/chunk";
    }

    static final class PostEndpoints {
        public static final String PROGRESS = "/progress";
        public static final String STATUS = "/status";
    }

    public static String buildUrl(String ip, int port, String endpoint) {
        return "http://" + ip + ":" + port + endpoint;
    }
}
