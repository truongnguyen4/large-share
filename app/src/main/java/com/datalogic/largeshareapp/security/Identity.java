package com.datalogic.largeshareapp.security;

import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.model.InformationMetadata;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device identity and fleet-membership proof.
 *
 * <p>Every device holds the fleet secret (provisioned by the operator, e.g. through MDM). It uses
 * it to prove "I am device X and I belong to this fleet" on every request, and to prove the same
 * on every response, so authentication is mutual and costs no extra round trip. The root also
 * signs the manifest, so a rogue peer cannot hand out a poisoned chunk-hash table.
 *
 * <p>Request MAC: HMAC(key, id \n ts \n nonce \n method \n target). The timestamp bounds replay of
 * captured requests and a nonce cache rejects replays of state-changing (POST) requests inside the
 * window. Response MAC: HMAC(key, "resp" \n requestNonce \n serverId \n status \n length).
 */
public final class Identity {
    public static final String HDR_ID = "X-LS-Id";
    public static final String HDR_TS = "X-LS-Ts";
    public static final String HDR_NONCE = "X-LS-Nonce";
    public static final String HDR_SIG = "X-LS-Sig";
    public static final String HDR_SRV = "X-LS-Srv";
    public static final String HDR_SRV_SIG = "X-LS-SrvSig";

    public static final long MAX_SKEW_MS = 5 * 60_000L;
    private static final int MAX_NONCES = 20_000;

    public enum Verdict { OK, MISSING, BAD_SIGNATURE, EXPIRED, REPLAY }

    private final String deviceId;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public Identity(String deviceId, String fleetSecret) {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId required");
        }
        this.deviceId = deviceId;
        this.key = SecureManager.sha256((fleetSecret == null ? "" : fleetSecret)
                .getBytes(StandardCharsets.UTF_8));
    }

    public String deviceId() {
        return deviceId;
    }

    /** A request that has been signed: the headers to send and the nonce to expect echoed back. */
    public static final class SignedRequest {
        public final Map<String, String> headers;
        public final String nonce;

        SignedRequest(Map<String, String> headers, String nonce) {
            this.headers = headers;
            this.nonce = nonce;
        }
    }

    public SignedRequest signRequest(String method, String target) {
        return signRequest(method, target, System.currentTimeMillis());
    }

    public SignedRequest signRequest(String method, String target, long nowMs) {
        String ts = Long.toString(nowMs / 1000L);
        byte[] nonceBytes = new byte[12];
        random.nextBytes(nonceBytes);
        String nonce = SecureManager.toHex(nonceBytes);
        Map<String, String> headers = new HashMap<>();
        headers.put(HDR_ID, deviceId);
        headers.put(HDR_TS, ts);
        headers.put(HDR_NONCE, nonce);
        headers.put(HDR_SIG, mac(deviceId, ts, nonce, method, target));
        return new SignedRequest(headers, nonce);
    }

    /** Verifies the headers of an incoming request. Header values may be null when absent. */
    public Verdict verifyRequest(String id, String ts, String nonce, String sig,
                                 String method, String target, boolean checkReplay) {
        return verifyRequest(id, ts, nonce, sig, method, target, checkReplay, System.currentTimeMillis());
    }

    public Verdict verifyRequest(String id, String ts, String nonce, String sig,
                                 String method, String target, boolean checkReplay, long nowMs) {
        if (id == null || ts == null || nonce == null || sig == null || id.isEmpty()) {
            return Verdict.MISSING;
        }
        // Signature first: nothing about the request is trusted (not even the timestamp) before it.
        if (!SecureManager.constantTimeEquals(sig, mac(id, ts, nonce, method, target))) {
            return Verdict.BAD_SIGNATURE;
        }
        long tsMs;
        try {
            tsMs = Long.parseLong(ts) * 1000L;
        } catch (NumberFormatException e) {
            return Verdict.BAD_SIGNATURE;
        }
        if (Math.abs(nowMs - tsMs) > MAX_SKEW_MS) {
            return Verdict.EXPIRED;
        }
        if (checkReplay && !rememberNonce(id + ":" + nonce, nowMs)) {
            return Verdict.REPLAY;
        }
        return Verdict.OK;
    }

    private boolean rememberNonce(String key, long nowMs) {
        if (seenNonces.size() > MAX_NONCES) {
            Iterator<Map.Entry<String, Long>> it = seenNonces.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue() > MAX_SKEW_MS) {
                    it.remove();
                }
            }
        }
        return seenNonces.putIfAbsent(key, nowMs) == null;
    }

    public String signResponse(String requestNonce, int status, long length) {
        return SecureManager.hmacSha256Hex(key,
                "resp\n" + requestNonce + "\n" + deviceId + "\n" + status + "\n" + length);
    }

    public boolean verifyResponse(String requestNonce, String serverId, int status, long length,
                                  String sig) {
        if (serverId == null || sig == null) {
            return false;
        }
        String expected = SecureManager.hmacSha256Hex(key,
                "resp\n" + requestNonce + "\n" + serverId + "\n" + status + "\n" + length);
        return SecureManager.constantTimeEquals(sig, expected);
    }

    public String signManifest(InformationMetadata info) {
        return SecureManager.hmacSha256Hex(key, "manifest\n" + info.canonicalString());
    }

    public boolean verifyManifest(InformationMetadata info) {
        return info != null && SecureManager.constantTimeEquals(info.getSignature(), signManifest(info));
    }

    private String mac(String id, String ts, String nonce, String method, String target) {
        return SecureManager.hmacSha256Hex(key,
                id + "\n" + ts + "\n" + nonce + "\n" + method + "\n" + target);
    }
}
