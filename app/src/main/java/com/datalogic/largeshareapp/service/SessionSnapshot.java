package com.datalogic.largeshareapp.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable picture of the share session, published by {@link ShareService} to its listeners.
 * The UI renders exclusively from these snapshots; it never reads engine state directly.
 */
public final class SessionSnapshot {

    public enum Role { ROOT, SEEKER }

    public enum Mode { NSD, WIFI_DIRECT }

    public enum Phase {
        IDLE, PREPARING, SHARING, SEARCHING, DOWNLOADING, PAUSED_NETWORK, VERIFYING, COMPLETED, FAILED, LEFT
    }

    /** Summary of the payload prepared for sharing (root side); per-chunk hashes stay in the engine. */
    public static final class FileInfo {
        public final String path;
        public final String name;
        public final long size;
        public final int chunkSize;
        public final int chunkCount;
        public final String fileHash;

        public FileInfo(String path, String name, long size, int chunkSize, int chunkCount, String fileHash) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.chunkSize = chunkSize;
            this.chunkCount = chunkCount;
            this.fileHash = fileHash;
        }

        /** Size of the last chunk, which is shorter unless the file size is a multiple of the chunk size. */
        public long lastChunkSize() {
            return chunkCount == 0 ? 0 : size - (long) (chunkCount - 1) * chunkSize;
        }
    }

    /** An address at which other devices can reach this one. */
    public static final class NetAddress {
        /** e.g. "Wi-Fi", "Wi-Fi Direct", "Ethernet". */
        public final String label;
        public final String ip;

        public NetAddress(String label, String ip) {
            this.label = label;
            this.ip = ip;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof NetAddress)) {
                return false;
            }
            NetAddress a = (NetAddress) o;
            return label.equals(a.label) && ip.equals(a.ip);
        }

        @Override
        public int hashCode() {
            return label.hashCode() * 31 + ip.hashCode();
        }
    }

    /** Progress reported by one downloading device to the root. */
    public static final class PeerProgress {
        public final String deviceId;
        public final int completed;
        public final int total;

        public PeerProgress(String deviceId, int completed, int total) {
            this.deviceId = deviceId;
            this.completed = completed;
            this.total = total;
        }

        public int percent() {
            return total > 0 ? (int) Math.round(completed * 100.0 / total) : 0;
        }

        public boolean isDone() {
            return total > 0 && completed >= total;
        }
    }

    /** True while a session (and therefore the foreground service) is running. */
    public final boolean active;
    public final Role role;
    public final Mode mode;
    public final Phase phase;
    public final String detail;
    /** A one-off note for the user (refused start, ignored input...), or null. */
    public final String message;
    public final String deviceId;
    /** Where peers can reach this device right now (refreshed as networks come and go). */
    public final List<NetAddress> addresses;
    /** TCP port this device serves chunks on. */
    public final int servePort;

    public final boolean preparing;
    public final int preparePercent;
    public final FileInfo file;

    public final int completedChunks;
    public final int totalChunks;
    public final double bytesPerSec;
    public final int activePeers;
    public final int knownPeers;

    /** Root view: progress of every device that reported, keyed by device id. */
    public final Map<String, PeerProgress> peers;

    private SessionSnapshot(Builder b) {
        active = b.active;
        role = b.role;
        mode = b.mode;
        phase = b.phase;
        detail = b.detail;
        message = b.message;
        deviceId = b.deviceId;
        addresses = Collections.unmodifiableList(new ArrayList<>(b.addresses));
        servePort = b.servePort;
        preparing = b.preparing;
        preparePercent = b.preparePercent;
        file = b.file;
        completedChunks = b.completedChunks;
        totalChunks = b.totalChunks;
        bytesPerSec = b.bytesPerSec;
        activePeers = b.activePeers;
        knownPeers = b.knownPeers;
        peers = Collections.unmodifiableMap(new LinkedHashMap<>(b.peers));
    }

    public int percent() {
        return totalChunks > 0 ? (int) Math.round(completedChunks * 100.0 / totalChunks) : 0;
    }

    /** Mutable state kept by the service; each publish freezes it into a snapshot. */
    static final class Builder {
        boolean active;
        Role role = Role.SEEKER;
        Mode mode = Mode.NSD;
        Phase phase = Phase.IDLE;
        String detail = "Idle";
        String message;
        String deviceId = "";
        List<NetAddress> addresses = new ArrayList<>();
        int servePort;
        boolean preparing;
        int preparePercent;
        FileInfo file;
        int completedChunks;
        int totalChunks;
        double bytesPerSec;
        int activePeers;
        int knownPeers;
        final Map<String, PeerProgress> peers = new LinkedHashMap<>();

        SessionSnapshot build() {
            return new SessionSnapshot(this);
        }
    }
}
