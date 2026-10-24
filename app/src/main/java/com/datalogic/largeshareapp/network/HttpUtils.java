package com.datalogic.largeshareapp.network;

public class HttpUtils {
    public static int CONNECT_TIMEOUT_MS = 4000;
    public static int READ_TIMEOUT_MS = 8000;
    /** How long a server keeps an idle persistent connection open before releasing its thread. */
    public static int IDLE_TIMEOUT_MS = 5000;
    public static final String GET = "GET";
    public static final String POST = "POST";

    public static final class GetEndpoints {
        public static final String METADATA = "/metadata";
        public static final String BITSET = "/bitset";
        public static final String CHUNK = "/chunk";
        /** Peer exchange: addresses of other devices this node has recently served. */
        public static final String PEERS = "/peers";
        /** Wi-Fi Direct swarm: lease (or release with ?leave=1) a member slot of the group owner. */
        public static final String GROUP = "/group";
    }

    /** Request header a serving device sends so others can learn the port it serves on. */
    public static final String HDR_SERVE_PORT = "X-LS-Port";

    public static final class PostEndpoints {
        public static final String PROGRESS = "/progress";
    }

    public static String buildUrl(String ip, int port, String endpoint) {
        // IPv6 literals need brackets, and a zone id ("%wlan0") must be percent-encoded in a URL.
        String host = ip != null && ip.indexOf(':') >= 0 && !ip.startsWith("[")
                ? "[" + ip.replace("%", "%25") + "]" : ip;
        return "http://" + host + ":" + port + endpoint;
    }
}
