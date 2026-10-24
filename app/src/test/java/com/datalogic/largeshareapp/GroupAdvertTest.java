package com.datalogic.largeshareapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.datalogic.largeshareapp.discovery.GroupAdvert;
import com.datalogic.largeshareapp.manager.SlotLeases;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class GroupAdvertTest {
    private static final String TAG = GroupAdvert.fleetTag("fleet-secret");

    @Test
    public void ssidCarriesFleetGenerationAndGroup() {
        String ssid = GroupAdvert.ssidFor(TAG, 2, "1549aeaa");
        assertTrue(ssid.startsWith("DIRECT-LS-"));
        assertTrue("SSID must fit in 32 bytes: " + ssid, ssid.length() <= 32);
        GroupAdvert a = GroupAdvert.fromSsid("\"" + ssid + "\"", TAG, -50);
        assertEquals("1549aeaa", a.groupId);
        assertEquals(2, a.generation);
        assertEquals(-50, a.rssi);
    }

    @Test
    public void foreignOrMalformedNetworksAreIgnored() {
        assertNull(GroupAdvert.fromSsid("Nha Tro 18 D3 PLB_5G", TAG, -40));
        assertNull(GroupAdvert.fromSsid(GroupAdvert.ssidFor(GroupAdvert.fleetTag("other fleet"), 0, "root"), TAG, -40));
        assertNull(GroupAdvert.fromSsid("DIRECT-LS-" + TAG + "-x-g1", TAG, -40));
        assertNull(GroupAdvert.fromSsid("DIRECT-LS-" + TAG + "-1-", TAG, -40));
        assertNull(GroupAdvert.fromSsid(null, TAG, -40));
    }

    @Test
    public void youngestGenerationWinsToOffloadTheRoot() {
        GroupAdvert root = new GroupAdvert(GroupAdvert.ROOT_GROUP_ID, 0, -30);
        GroupAdvert gen1 = new GroupAdvert("g1", 1, -70);
        GroupAdvert gen2 = new GroupAdvert("g2", 2, -80);
        assertEquals("g2", GroupAdvert.choose(Arrays.asList(root, gen1, gen2), "me", null, new Random(1)).groupId);
    }

    @Test
    public void strongerSignalWinsWithinAGeneration() {
        GroupAdvert far = new GroupAdvert("far", 1, -85);
        GroupAdvert near = new GroupAdvert("near", 1, -45);
        assertEquals("near", GroupAdvert.choose(Arrays.asList(far, near), "me", null, new Random(1)).groupId);
    }

    @Test
    public void ownAndCoolingDownGroupsAreSkipped() {
        GroupAdvert own = new GroupAdvert("me", 3, -30);
        GroupAdvert cooling = new GroupAdvert("busy", 2, -30);
        GroupAdvert ok = new GroupAdvert("ok", 1, -60);
        Set<String> cool = new HashSet<>(Collections.singletonList("busy"));
        assertEquals("ok", GroupAdvert.choose(Arrays.asList(own, cooling, ok), "me", cool, new Random(1)).groupId);
        assertNull(GroupAdvert.choose(Arrays.asList(own, cooling), "me", cool, new Random(1)));
    }

    @Test
    public void equallyGoodGroupsShareTheCrowd() {
        GroupAdvert a = new GroupAdvert("a", 1, -52); // same 5 dB bucket as b
        GroupAdvert b = new GroupAdvert("b", 1, -54);
        Random random = new Random(7);
        int picksA = 0;
        for (int i = 0; i < 1000; i++) {
            if ("a".equals(GroupAdvert.choose(Arrays.asList(a, b), "me", null, random).groupId)) {
                picksA++;
            }
        }
        assertTrue("picks should be spread, got " + picksA, picksA > 350 && picksA < 650);
    }

    @Test
    public void onAnAccessPointGroupsOnTheSameChannelComeFirst() {
        GroupAdvert young = new GroupAdvert("young", 2, -40, 5180);
        GroupAdvert sameChannel = new GroupAdvert("same", 0, -60, 5540);
        assertEquals("same", GroupAdvert.choose(Arrays.asList(young, sameChannel), "me", null, 5540, new Random(1)).groupId);
        // without infrastructure the channel does not matter: youngest generation wins
        assertEquals("young", GroupAdvert.choose(Arrays.asList(young, sameChannel), "me", null, 0, new Random(1)).groupId);
    }

    @Test
    public void slotsGoToTheFirstDevicesAndFreeUpWhenLeasesLapse() {
        SlotLeases leases = new SlotLeases(1, 15_000);
        assertTrue(leases.admit("moto", 0));
        assertFalse("group is full", leases.admit("memor", 1_000));
        assertTrue("holder renews", leases.admit("moto", 10_000));
        assertFalse(leases.admit("memor", 20_000));
        // moto graduated and stopped renewing: its lease lapses after 15 s
        assertTrue(leases.admit("memor", 26_000));
        assertEquals(1, leases.holders(26_000));
    }

    @Test
    public void releasedSlotIsFreeImmediately() {
        SlotLeases leases = new SlotLeases(2, 15_000);
        assertTrue(leases.admit("a", 0));
        assertTrue(leases.admit("b", 0));
        assertFalse(leases.admit("c", 0));
        leases.release("a");
        assertTrue(leases.admit("c", 1));
    }
}
