package com.datalogic.largeshareapp.discovery;

import androidx.annotation.NonNull;

import com.datalogic.largeshareapp.manager.SecureManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A Wi-Fi Direct group of the swarm as future members see it in an ordinary Wi-Fi scan. A group
 * owner beacons like an access point, so its SSID is the advertisement:
 * {@code DIRECT-LS-<fleet tag>-<generation>-<group id>}. The fleet tag keeps other fleets'
 * groups apart; the passphrase is derived from the fleet secret and the group id and never
 * appears on the air, so seeing the SSID does not let anyone join.
 */
public final class GroupAdvert {
    private static final String PREFIX = "DIRECT-LS-";
    public static final String ROOT_GROUP_ID = "root";

    public final String groupId;
    public final int generation;
    /** Signal strength from the scan (dBm), or Integer.MIN_VALUE when unknown. */
    public final int rssi;
    /** Channel the group was seen on (MHz), or 0 when unknown. */
    public final int frequencyMhz;

    public GroupAdvert(String groupId, int generation, int rssi) {
        this(groupId, generation, rssi, 0);
    }

    public GroupAdvert(String groupId, int generation, int rssi, int frequencyMhz) {
        this.groupId = groupId;
        this.generation = generation;
        this.rssi = rssi;
        this.frequencyMhz = frequencyMhz;
    }

    /** 4 hex characters identifying the fleet in SSIDs (16 bits of a hash of the secret). */
    public static String fleetTag(String fleetSecret) {
        return SecureManager.sha256Hex(("fleet-tag:" + fleetSecret).getBytes(StandardCharsets.UTF_8)).substring(0, 4);
    }

    /** WPA2 passphrase of a group: derived from the fleet secret, never sent over the air. */
    public static String passphraseFor(String fleetSecret, String groupId) {
        String seed = groupId == null || groupId.isEmpty() ? fleetSecret : fleetSecret + ":" + groupId;
        return SecureManager.sha256Hex(("wfd-pass:" + seed).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    public static String ssidFor(String fleetTag, int generation, String groupId) {
        return PREFIX + fleetTag + "-" + generation + "-" + groupId;
    }

    public String ssid(String fleetTag) {
        return ssidFor(fleetTag, generation, groupId);
    }

    /** Parses a scanned SSID; returns null if it is not a group of this fleet. */
    public static GroupAdvert fromSsid(String ssid, String fleetTag, int rssi) {
        return fromSsid(ssid, fleetTag, rssi, 0);
    }

    public static GroupAdvert fromSsid(String ssid, String fleetTag, int rssi, int frequencyMhz) {
        if (ssid == null) {
            return null;
        }
        String s = ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1
                ? ssid.substring(1, ssid.length() - 1) : ssid;
        String head = PREFIX + fleetTag + "-";
        if (!s.startsWith(head)) {
            return null;
        }
        String rest = s.substring(head.length());
        int dash = rest.indexOf('-');
        if (dash <= 0 || dash == rest.length() - 1) {
            return null;
        }
        try {
            int generation = Integer.parseInt(rest.substring(0, dash));
            String groupId = rest.substring(dash + 1);
            if (generation < 0 || groupId.isEmpty()) {
                return null;
            }
            return new GroupAdvert(groupId, generation, rssi, frequencyMhz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Picks the group to try: not ours, not cooling down (recently full or unreachable). The
     * youngest generation wins so load moves away from the root as the tree grows; then the
     * strongest signal (5 dB buckets); then a random one, so a crowd of members spreads out.
     */
    public static GroupAdvert choose(Collection<GroupAdvert> adverts, String ownGroupId,
                                     Set<String> coolingDown, Random random) {
        return choose(adverts, ownGroupId, coolingDown, 0, random);
    }

    /**
     * Same, but when this device is also on an access point ({@code ownFrequencyMhz} > 0), groups
     * on that same channel come first: many radios cannot join a group on another channel while
     * they stay on the access point.
     */
    public static GroupAdvert choose(Collection<GroupAdvert> adverts, String ownGroupId,
                                     Set<String> coolingDown, int ownFrequencyMhz, Random random) {
        List<GroupAdvert> usable = new ArrayList<>();
        for (GroupAdvert a : adverts) {
            if (!a.groupId.equals(ownGroupId) && (coolingDown == null || !coolingDown.contains(a.groupId))) {
                usable.add(a);
            }
        }
        GroupAdvert best = null;
        int ties = 0;
        for (GroupAdvert a : usable) {
            int cmp = best == null ? 1 : compare(a, best, ownFrequencyMhz);
            if (cmp > 0) {
                best = a;
                ties = 1;
            } else if (cmp == 0 && random.nextInt(++ties) == 0) {
                best = a; // reservoir sampling among equally good groups
            }
        }
        return best;
    }

    private static int compare(GroupAdvert a, GroupAdvert b, int ownFrequencyMhz) {
        if (ownFrequencyMhz > 0) {
            boolean aSame = a.frequencyMhz == ownFrequencyMhz;
            boolean bSame = b.frequencyMhz == ownFrequencyMhz;
            if (aSame != bSame) {
                return aSame ? 1 : -1;
            }
        }
        if (a.generation != b.generation) {
            return Integer.compare(a.generation, b.generation);
        }
        return Integer.compare(bucket(a.rssi), bucket(b.rssi));
    }

    private static int bucket(int rssi) {
        return rssi == Integer.MIN_VALUE ? Integer.MIN_VALUE : Math.floorDiv(rssi, 5);
    }

    @NonNull
    @Override
    public String toString() {
        return "group '" + groupId + "' gen " + generation
                + (rssi == Integer.MIN_VALUE ? "" : " (" + rssi + " dBm" + (frequencyMhz > 0 ? " @" + frequencyMhz + " MHz" : "") + ")");
    }
}
