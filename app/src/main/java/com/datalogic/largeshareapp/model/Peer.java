package com.datalogic.largeshareapp.model;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * An immutable description of a remote device that can serve (or sink) data. Scheduling state such
 * as back-off and failure counts lives in {@link com.datalogic.largeshareapp.manager.PeerRegistry}.
 */
public final class Peer {
    public static final String NAME_PREFIX_SHARE = "LargeShare_";

    public final String ip;
    public final int port;
    public final String serviceName;
    public final String serviceType;
    /** First characters of the manifest file hash advertised by the peer (NSD TXT "h"), or null. */
    public final String swarmTag;

    public Peer(String ip, int port) {
        this(ip, port, null, null, null);
    }

    public Peer(String ip, int port, String serviceName, String serviceType) {
        this(ip, port, serviceName, serviceType, null);
    }

    public Peer(String ip, int port, String serviceName, String serviceType, String swarmTag) {
        this.ip = ip;
        this.port = port;
        this.serviceName = serviceName;
        this.serviceType = serviceType;
        this.swarmTag = swarmTag;
    }

    /** Stable key used to de-duplicate peers: the advertised service name, else the address. */
    public String key() {
        return serviceName != null ? serviceName : ip + ":" + port;
    }

    /**
     * The device id the peer claims through its NSD service name ("LargeShare_&lt;id&gt;"), used to
     * cross-check the identity the peer proves over HTTP. Null when it cannot be derived reliably
     * (for example when NSD appended a conflict suffix).
     */
    public String expectedDeviceId() {
        if (serviceName == null || !serviceName.startsWith(NAME_PREFIX_SHARE)) {
            return null;
        }
        String id = serviceName.substring(NAME_PREFIX_SHARE.length());
        if (id.isEmpty() || id.indexOf(' ') >= 0 || id.indexOf('(') >= 0) {
            return null;
        }
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Peer)) return false;
        Peer other = (Peer) o;
        return port == other.port && Objects.equals(ip, other.ip)
                && Objects.equals(serviceName, other.serviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port, serviceName);
    }

    @NonNull
    @Override
    public String toString() {
        return "Peer{" + (serviceName != null ? serviceName + "@" : "") + ip + ":" + port + '}';
    }
}
