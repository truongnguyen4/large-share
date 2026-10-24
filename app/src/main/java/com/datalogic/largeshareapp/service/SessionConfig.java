package com.datalogic.largeshareapp.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the UI asks the service to start. */
public final class SessionConfig {
    public final SessionSnapshot.Role role;
    public final SessionSnapshot.Mode mode;
    public final String fleetKey;
    /** "host[:port]" entries for networks where multicast discovery is blocked (seeker only). */
    public final List<String> staticPeers;
    /** Root only: share this file. When null the file prepared with prepareFile() is used. */
    public final String filePath;
    /** Wi-Fi Direct swarm: member slots of each group this device owns (0 = default). */
    public final int groupCapacity;

    public SessionConfig(SessionSnapshot.Role role, SessionSnapshot.Mode mode, String fleetKey,
                         List<String> staticPeers, String filePath) {
        this(role, mode, fleetKey, staticPeers, filePath, 0);
    }

    public SessionConfig(SessionSnapshot.Role role, SessionSnapshot.Mode mode, String fleetKey,
                         List<String> staticPeers, String filePath, int groupCapacity) {
        this.role = role;
        this.mode = mode;
        this.fleetKey = fleetKey;
        this.staticPeers = staticPeers == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(staticPeers));
        this.filePath = filePath;
        this.groupCapacity = Math.max(0, groupCapacity);
    }

    SessionConfig withoutFilePath() {
        return new SessionConfig(role, mode, fleetKey, staticPeers, null, groupCapacity);
    }

    /** Splits free text such as "10.0.0.5:8888, 10.0.0.6" into entries. */
    public static List<String> parsePeers(String text) {
        List<String> peers = new ArrayList<>();
        if (text != null) {
            for (String p : text.split("[,;\\s]+")) {
                if (!p.isEmpty()) {
                    peers.add(p);
                }
            }
        }
        return peers;
    }
}
