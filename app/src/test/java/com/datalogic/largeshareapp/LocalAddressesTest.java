package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.datalogic.largeshareapp.network.LocalAddresses;
import com.datalogic.largeshareapp.service.SessionSnapshot;

import org.junit.Test;

public class LocalAddressesTest {

    @Test
    public void interfacesPeersCanReachAreLabelled() {
        assertEquals("Wi-Fi", LocalAddresses.labelFor("wlan0"));
        assertEquals("Wi-Fi Direct", LocalAddresses.labelFor("p2p-p2p0-37"));
        assertEquals("Wi-Fi Direct", LocalAddresses.labelFor("p2p0"));
        assertEquals("Ethernet", LocalAddresses.labelFor("eth0"));
        assertEquals("Hotspot", LocalAddresses.labelFor("swlan0"));
        assertEquals("Hotspot", LocalAddresses.labelFor("ap0"));
        assertEquals("USB", LocalAddresses.labelFor("rndis0"));
    }

    @Test
    public void mobileDataVpnAndLoopbackAreHidden() {
        assertNull(LocalAddresses.labelFor("rmnet_data0"));
        assertNull(LocalAddresses.labelFor("ccmni1"));
        assertNull(LocalAddresses.labelFor("tun0"));
        assertNull(LocalAddresses.labelFor("lo"));
        assertNull(LocalAddresses.labelFor("dummy0"));
        assertNull(LocalAddresses.labelFor(null));
    }

    @Test
    public void currentAddressesAreIpv4AndNeverLoopback() {
        for (LocalAddresses.Address a : LocalAddresses.current()) {
            assertTrue(a.ip, a.ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
            assertTrue(a.ip, !a.ip.startsWith("127."));
        }
    }

    @Test
    public void fileSummaryKnowsTheShortLastChunk() {
        int chunk = 512 * 1024;
        SessionSnapshot.FileInfo exact = new SessionSnapshot.FileInfo("/x/a.bin", "a.bin", 1000L * chunk, chunk, 1000, "h");
        assertEquals(chunk, exact.lastChunkSize());
        SessionSnapshot.FileInfo odd = new SessionSnapshot.FileInfo("/x/b.bin", "b.bin", 2L * chunk + 100, chunk, 3, "h");
        assertEquals(100, odd.lastChunkSize());
    }
}
