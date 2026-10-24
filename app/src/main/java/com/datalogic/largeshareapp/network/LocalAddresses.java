package com.datalogic.largeshareapp.network;

import androidx.annotation.NonNull;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * IPv4 addresses at which other devices of the local network can reach this one (Wi-Fi, Wi-Fi
 * Direct, Ethernet, hotspot...). Mobile data, VPN and virtual interfaces are left out: peers
 * cannot use them.
 */
public final class LocalAddresses {

    public static final class Address {
        /** Kind of network for people, e.g. "Wi-Fi" or "Wi-Fi Direct". */
        public final String label;
        public final String ip;

        public Address(String label, String ip) {
            this.label = label;
            this.ip = ip;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Address)) {
                return false;
            }
            Address a = (Address) o;
            return label.equals(a.label) && ip.equals(a.ip);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, ip);
        }

        @NonNull
        @Override
        public String toString() {
            return ip + " (" + label + ")";
        }
    }

    private static final String[] ORDER = {"Wi-Fi", "Wi-Fi Direct", "Ethernet", "Hotspot", "USB", "Bluetooth"};

    private LocalAddresses() {
    }

    /** Readable kind of an interface, or null when peers cannot reach us through it. */
    public static String labelFor(String interfaceName) {
        if (interfaceName == null) {
            return null;
        }
        String n = interfaceName.toLowerCase(Locale.US);
        if (n.startsWith("p2p")) {
            return "Wi-Fi Direct";
        }
        if (n.startsWith("wlan")) {
            return "Wi-Fi";
        }
        if (n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("ap")) {
            return "Hotspot";
        }
        if (n.startsWith("eth")) {
            return "Ethernet";
        }
        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) {
            return "USB";
        }
        if (n.startsWith("bt-pan")) {
            return "Bluetooth";
        }
        return null; // rmnet/ccmni (mobile data), tun (VPN), dummy, lo...
    }

    /** Current addresses, Wi-Fi first. Cheap enough to poll every few seconds. */
    public static List<Address> current() {
        List<Address> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return out;
            }
            for (NetworkInterface nif : Collections.list(interfaces)) {
                String label = labelFor(nif.getName());
                if (label == null || !nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(new Address(label, a.getHostAddress()));
                    }
                }
            }
        } catch (SocketException e) {
            return out; // no interface information: show nothing rather than fail
        }
        out.sort((a, b) -> {
            int c = Integer.compare(rank(a.label), rank(b.label));
            return c != 0 ? c : a.ip.compareTo(b.ip);
        });
        return out;
    }

    private static int rank(String label) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(label)) {
                return i;
            }
        }
        return ORDER.length;
    }
}
