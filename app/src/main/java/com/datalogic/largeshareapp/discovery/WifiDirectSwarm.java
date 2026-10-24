package com.datalogic.largeshareapp.discovery;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Self-organising Wi-Fi Direct distribution without any infrastructure ("graduation" model).
 *
 * <ol>
 *   <li>Every device that wants the payload starts as a future member and looks for a group of
 *       the fleet in an ordinary Wi-Fi scan (a group owner beacons like an access point).</li>
 *   <li>The root owns the first group (generation 0). An owner admits up to {@code capacity}
 *       members; slots are leased over HTTP by the caller (see SlotLeases), because Android cannot
 *       cap the clients of a group.</li>
 *   <li>A member that has the whole, verified payload leaves its group and creates its own group
 *       one generation deeper to share further; the slot it frees lets another device in.</li>
 * </ol>
 *
 * A device is only ever in one group, as member or owner, which is all one Wi-Fi radio allows,
 * and the station interface is never touched.
 * The passphrase of a group is derived from the fleet secret and the group id, so joining needs no
 * user interaction while outsiders who see the SSID still cannot join.
 */
@SuppressLint("MissingPermission")
public class WifiDirectSwarm {
    private static final String TAG = "WifiDirectSwarm";

    private static final long SCAN_WINDOW_MS = 6_000L;
    private static final long RETRY_IDLE_MS = 3_000L;
    private static final long JOIN_TIMEOUT_MS = 15_000L;
    private static final long COOL_DOWN_MS = 30_000L;
    private static final long ROOT_FALLBACK_EVERY_MS = 45_000L;
    private static final long SCAN_RESULT_MAX_AGE_MS = 60_000L;
    /** Apps may start 4 Wi-Fi scans per 2 minutes; in between, the latest system results are used. */
    private static final long MIN_SCAN_INTERVAL_MS = 30_000L;
    private static final long CONNECT_FAILED_COOL_DOWN_MS = 5_000L;
    private static final long DEAD_GROUP_COOL_DOWN_MS = 120_000L;
    private static final long FULL_GROUP_COOL_DOWN_MS = 8_000L;
    private static final long FULL_GROUP_MAX_COOL_DOWN_MS = 60_000L;
    private static final long KNOWN_GROUP_TTL_MS = 90_000L;

    public enum Role { IDLE, SEEKING, JOINING, MEMBER, GRADUATING, OWNER }

    public interface Listener {
        /** Joined a group as member; the owner serves the payload at {@code ownerAddress}. */
        void onJoinedGroup(String ownerAddress, GroupAdvert group);

        /** No longer a member (group lost, slot refused, or left to graduate). */
        void onLeftGroup();

        /** Our own group is up. */
        void onOwnerReady(String groupId, int generation);

        void onMembersChanged(int members);

        /** Human-readable progress of the search for a group. */
        void onSearching(String detail);
    }

    private final Context mContext;
    private final WifiP2pManager mManager;
    private final WifiP2pManager.Channel mChannel;
    private final WifiManager mWifi;
    private final Listener mListener;
    private final String mFleetSecret;
    private final String mFleetTag;
    private final String mOwnGroupId;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();
    private final Map<String, Long> mCoolingDown = new HashMap<>();
    /** Slot refusals per group, for the back-off. */
    private final Map<String, Integer> mRefusals = new HashMap<>();
    /**
     * Groups seen recently (scan or membership) and when. Apps may only scan a few times per
     * minutes, so a member refused a slot rejoins a known group by name instead of waiting for
     * the next scan.
     */
    private final Map<String, GroupAdvert> mKnown = new HashMap<>();
    private final Map<String, Long> mKnownAt = new HashMap<>();

    private BroadcastReceiver mReceiver;
    private boolean mActive;
    private Role mRole = Role.IDLE;
    private GroupAdvert mCurrentGroup;
    private int mMembers = -1;
    private boolean mOwnerReady;
    private long mLastRootFallback;
    private long mLastScanStart;

    /** @param ownGroupId id of the group this device creates when it becomes an owner */
    public WifiDirectSwarm(Context context, String fleetSecret, String ownGroupId, Listener listener) {
        mContext = context.getApplicationContext();
        mManager = (WifiP2pManager) mContext.getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager != null ? mManager.initialize(mContext, Looper.getMainLooper(), null) : null;
        mWifi = mContext.getSystemService(WifiManager.class);
        mFleetSecret = fleetSecret;
        mFleetTag = GroupAdvert.fleetTag(fleetSecret);
        mOwnGroupId = ownGroupId;
        mListener = listener;
    }

    public boolean isSupported() {
        return mManager != null && mChannel != null && mWifi != null;
    }

    public Role getRole() {
        return mRole;
    }

    /** The group we own or belong to, or null. */
    public GroupAdvert getCurrentGroup() {
        return mCurrentGroup;
    }

    private WifiP2pConfig.Builder credentials(GroupAdvert group) {
        return new WifiP2pConfig.Builder()
                .setNetworkName(group.ssid(mFleetTag))
                .setPassphrase(GroupAdvert.passphraseFor(mFleetSecret, group.groupId))
                .enablePersistentMode(false);
    }

    // ------------------------------------------------------------------ owner

    /** The root owns the well-known generation-0 group from the start. */
    public void startAsRoot() {
        start();
        becomeOwner(new GroupAdvert(GroupAdvert.ROOT_GROUP_ID, 0, Integer.MIN_VALUE));
    }

    private void becomeOwner(GroupAdvert group) {
        mRole = Role.OWNER;
        mMembers = -1;
        mOwnerReady = false;
        mCurrentGroup = group;
        mManager.removeGroup(mChannel, after(() -> createOwnGroup(stationFrequency())));
    }

    /**
     * Creates our group. The channel is left to the driver: with no infrastructure it uses a
     * social channel, and if the device is on an access point it stays on that channel, so it never
     * has to time-share its radio between two channels. Members find groups by a Wi-Fi scan, which
     * covers every channel.
     */
    private void createOwnGroup(int preferredFrequencyMhz) {
        if (!mActive || mRole != Role.OWNER) {
            return;
        }
        WifiP2pConfig.Builder builder = credentials(mCurrentGroup);
        if (preferredFrequencyMhz > 0) {
            // On an access point: share its channel so neither we nor members on the same AP have to
            // split the radio between two channels.
            builder.setGroupOperatingFrequency(preferredFrequencyMhz);
        }
        mManager.createGroup(mChannel, builder.build(), new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup accepted for " + mCurrentGroup.ssid(mFleetTag)
                        + (preferredFrequencyMhz > 0 ? " on " + preferredFrequencyMhz + " MHz" : ""));
                pollConnectionInfo();
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "createGroup failed (" + reason + "), retrying"
                        + (preferredFrequencyMhz > 0 ? " on a channel the driver picks" : ""));
                mHandler.postDelayed(() -> createOwnGroup(0), 3_000);
            }
        });
    }

    // ------------------------------------------------------------------ future member

    /** A device that wants the payload: find a group of the fleet and join it. */
    public void startAsSeeker() {
        start();
        // A group this device owned in a previous run (app killed) survives the process: tear it
        // down so members are not stuck with an owner that serves nothing.
        mRole = Role.SEEKING;
        mManager.removeGroup(mChannel, after(() -> seek(0)));
    }

    private void seek(long delayMs) {
        mRole = Role.SEEKING;
        mCurrentGroup = null;
        mHandler.removeCallbacks(mScan);
        mHandler.postDelayed(mScan, delayMs);
    }

    private final Runnable mScan = this::scan;

    private void scan() {
        if (!mActive || mRole != Role.SEEKING) {
            return;
        }
        mListener.onSearching("Scanning for a group of the fleet");
        // A Wi-Fi Direct search left running (e.g. by a failed join) makes some chips abort every
        // Wi-Fi scan, so stop it first.
        mManager.stopPeerDiscovery(mChannel, after(() -> {
            boolean started = false;
            long now = System.currentTimeMillis();
            if (now - mLastScanStart >= MIN_SCAN_INTERVAL_MS) {
                mLastScanStart = now;
                started = mWifi.startScan();
            }
            Log.d(TAG, started ? "Wi-Fi scan started" : "Using the latest scan results");
            mHandler.removeCallbacks(mEvaluate);
            mHandler.postDelayed(mEvaluate, started ? SCAN_WINDOW_MS : 500);
        }));
    }

    private final Runnable mEvaluate = this::evaluateScan;

    private void evaluateScan() {
        if (!mActive || mRole != Role.SEEKING) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = mCoolingDown.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
        Map<String, GroupAdvert> found = new HashMap<>();
        List<ScanResult> results = mWifi.getScanResults();
        long nowBoot = android.os.SystemClock.elapsedRealtime();
        for (ScanResult r : results) {
            if (nowBoot - r.timestamp / 1000 > SCAN_RESULT_MAX_AGE_MS) {
                continue;
            }
            GroupAdvert advert = GroupAdvert.fromSsid(r.getWifiSsid() != null ? r.getWifiSsid().toString() : null,
                    mFleetTag, r.level, r.frequency);
            if (advert != null) {
                GroupAdvert known = found.get(advert.groupId);
                if (known == null || advert.rssi > known.rssi) {
                    found.put(advert.groupId, advert);
                }
            }
        }
        for (GroupAdvert advert : found.values()) {
            remember(advert);
        }
        String scanned = found.values().toString();
        for (Iterator<Map.Entry<String, Long>> k = mKnownAt.entrySet().iterator(); k.hasNext(); ) {
            Map.Entry<String, Long> e = k.next();
            if (now - e.getValue() > KNOWN_GROUP_TTL_MS) {
                mKnown.remove(e.getKey());
                k.remove();
            } else if (!found.containsKey(e.getKey())) {
                found.put(e.getKey(), mKnown.get(e.getKey())); // seen recently, scan just throttled
            }
        }
        Log.d(TAG, "Scan found " + scanned
                + (mKnown.isEmpty() ? "" : ", known " + mKnown.keySet())
                + (mCoolingDown.isEmpty() ? "" : ", cooling down " + mCoolingDown.keySet()));
        GroupAdvert choice = GroupAdvert.choose(found.values(), mOwnGroupId, mCoolingDown.keySet(),
                stationFrequency(), mRandom);
        if (choice != null) {
            join(choice, false);
        } else if (!found.containsKey(GroupAdvert.ROOT_GROUP_ID) && now - mLastRootFallback > ROOT_FALLBACK_EVERY_MS
                && !mCoolingDown.containsKey(GroupAdvert.ROOT_GROUP_ID)) {
            // Nothing visible yet (scan throttled or not finished): the root group's name is known.
            // On an access point the root created its group on the AP channel: use it as the hint the
            // supplicant needs to find a group on a channel it does not probe (e.g. DFS).
            mLastRootFallback = now;
            join(new GroupAdvert(GroupAdvert.ROOT_GROUP_ID, 0, Integer.MIN_VALUE, stationFrequency()), true);
        } else {
            mListener.onSearching(found.isEmpty() ? "No group found yet"
                    : "Every visible group is full or unreachable, waiting for a free slot");
            seek(RETRY_IDLE_MS);
        }
    }

    private void join(GroupAdvert group, boolean fallback) {
        mRole = Role.JOINING;
        mCurrentGroup = group;
        mListener.onSearching("Joining " + group);
        Log.i(TAG, "Joining " + group + (fallback ? " by its well-known name" : ""));
        WifiP2pConfig.Builder config = credentials(group);
        if (group.frequencyMhz > 0) {
            // Tell the supplicant where to look: its own search may skip DFS channels.
            config.setGroupOperatingFrequency(group.frequencyMhz);
        }
        mManager.connect(mChannel, config.build(), new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                pollConnectionInfo();
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "connect to " + group + " failed: " + reason);
                failJoin(group, CONNECT_FAILED_COOL_DOWN_MS);
            }
        });
        mHandler.removeCallbacks(mJoinTimeout);
        mHandler.postDelayed(mJoinTimeout, JOIN_TIMEOUT_MS);
    }

    private final Runnable mJoinTimeout = () -> {
        if (mRole == Role.JOINING && mCurrentGroup != null) {
            Log.w(TAG, "Joining " + mCurrentGroup + " timed out");
            failJoin(mCurrentGroup, COOL_DOWN_MS);
        }
    };

    private void failJoin(GroupAdvert group, long coolDownMs) {
        if (mRole != Role.JOINING) {
            return;
        }
        mHandler.removeCallbacks(mJoinTimeout);
        coolDown(group.groupId, coolDownMs);
        forget(group.groupId);
        mManager.cancelConnect(mChannel, null);
        mManager.removeGroup(mChannel, null);
        seek(1_500 + mRandom.nextInt(1_500));
    }

    private void coolDown(String groupId, long ms) {
        mCoolingDown.put(groupId, System.currentTimeMillis() + ms);
    }

    private void remember(GroupAdvert group) {
        GroupAdvert known = mKnown.get(group.groupId);
        if (group.frequencyMhz <= 0 && known != null && known.frequencyMhz > 0) {
            group = new GroupAdvert(group.groupId, group.generation, group.rssi, known.frequencyMhz);
        }
        mKnown.put(group.groupId, group);
        mKnownAt.put(group.groupId, System.currentTimeMillis());
    }

    /** The group could not be reached: do not rejoin it from memory, only if a scan shows it again. */
    private void forget(String groupId) {
        mKnown.remove(groupId);
        mKnownAt.remove(groupId);
    }

    /**
     * The owner refused us a slot: leave, avoid that group for a while and look elsewhere. Slots
     * turn over within seconds (a member graduates as soon as it has the payload), so the first
     * retry comes soon; repeated refusals back off exponentially, with jitter so a refused crowd
     * does not return all at once.
     */
    public void leaveFullGroup() {
        if (!mActive || mRole != Role.MEMBER || mCurrentGroup == null) {
            return;
        }
        Integer previous = mRefusals.get(mCurrentGroup.groupId);
        int refusals = previous == null ? 1 : previous + 1;
        mRefusals.put(mCurrentGroup.groupId, refusals);
        long backoff = Math.min(FULL_GROUP_MAX_COOL_DOWN_MS, FULL_GROUP_COOL_DOWN_MS << Math.min(refusals - 1, 4));
        leaveGroup("is full (refusal " + refusals + ")", backoff * (80 + mRandom.nextInt(41)) / 100);
    }

    /**
     * The owner stopped answering although its group is still up (its app died or hung): leave and
     * avoid the group long enough for its beacon to disappear.
     */
    public void leaveDeadGroup() {
        GroupAdvert dead = mCurrentGroup;
        leaveGroup("has a silent owner", DEAD_GROUP_COOL_DOWN_MS);
        if (dead != null) {
            forget(dead.groupId);
        }
    }

    private void leaveGroup(String reason, long coolDownMs) {
        if (!mActive || mRole != Role.MEMBER || mCurrentGroup == null) {
            return;
        }
        Log.i(TAG, mCurrentGroup + " " + reason + ", leaving it");
        coolDown(mCurrentGroup.groupId, coolDownMs);
        mRole = Role.SEEKING;
        mManager.removeGroup(mChannel, after(() -> {
            mListener.onLeftGroup();
            seek(1_000 + mRandom.nextInt(2_000));
        }));
    }

    /**
     * The member has the whole, verified payload: leave the group (freeing a slot) and create our
     * own group one generation deeper.
     */
    public void graduate() {
        if (!mActive || mRole != Role.MEMBER) {
            return;
        }
        int nextGeneration = (mCurrentGroup != null ? mCurrentGroup.generation : 0) + 1;
        Log.i(TAG, "Graduating: leaving " + mCurrentGroup + " to own generation " + nextGeneration);
        mRole = Role.GRADUATING;
        mManager.removeGroup(mChannel, after(() -> {
            mListener.onLeftGroup();
            becomeOwner(new GroupAdvert(mOwnGroupId, nextGeneration, Integer.MIN_VALUE));
        }));
    }

    /** Channel of the access point this device is connected to, or 0 (no infrastructure). */
    @SuppressWarnings("deprecation")
    private int stationFrequency() {
        android.net.wifi.WifiInfo info = mWifi.getConnectionInfo();
        return info != null && info.getNetworkId() != -1 && info.getFrequency() > 0 ? info.getFrequency() : 0;
    }

    // ------------------------------------------------------------------ connection tracking

    private void pollConnectionInfo() {
        mHandler.postDelayed(() -> mManager.requestConnectionInfo(mChannel, this::onConnectionInfo), 1_500);
    }

    private void onConnectionInfo(WifiP2pInfo info) {
        if (!mActive || info == null) {
            return;
        }
        boolean formed = info.groupFormed && info.groupOwnerAddress != null;
        switch (mRole) {
            case JOINING:
                if (formed && !info.isGroupOwner) {
                    mHandler.removeCallbacks(mJoinTimeout);
                    mRole = Role.MEMBER;
                    String owner = info.groupOwnerAddress.getHostAddress();
                    Log.i(TAG, "Member of " + mCurrentGroup + ", owner at " + owner);
                    remember(mCurrentGroup);
                    GroupAdvert joined = mCurrentGroup;
                    mManager.requestGroupInfo(mChannel, g -> {
                        // A group joined by name has no scanned channel: learn it for a later rejoin.
                        if (g != null && g.getFrequency() > 0 && joined.frequencyMhz != g.getFrequency()) {
                            remember(new GroupAdvert(joined.groupId, joined.generation, joined.rssi, g.getFrequency()));
                        }
                    });
                    mListener.onJoinedGroup(owner, mCurrentGroup);
                } else if (!formed) {
                    pollConnectionInfo(); // still forming; the timeout bounds this
                }
                break;
            case MEMBER:
                if (!formed) {
                    Log.w(TAG, "Lost " + mCurrentGroup + ", looking for another group");
                    mListener.onLeftGroup();
                    seek(1_000);
                }
                break;
            case OWNER:
                if (formed && info.isGroupOwner) {
                    if (!mOwnerReady) {
                        mOwnerReady = true;
                        Log.i(TAG, "Owner of " + mCurrentGroup.ssid(mFleetTag) + " at "
                                + info.groupOwnerAddress.getHostAddress());
                        mListener.onOwnerReady(mCurrentGroup.groupId, mCurrentGroup.generation);
                    }
                    mManager.requestGroupInfo(mChannel, this::onGroupInfo);
                } else if (!formed && mOwnerReady) {
                    Log.w(TAG, "Own group dropped, recreating");
                    becomeOwner(mCurrentGroup);
                } else if (!formed) {
                    pollConnectionInfo();
                }
                break;
            default:
                break;
        }
    }

    private void onGroupInfo(WifiP2pGroup group) {
        if (mRole != Role.OWNER || group == null) {
            return;
        }
        int members = group.getClientList().size();
        if (members != mMembers) {
            mMembers = members;
            Log.i(TAG, "Group '" + mCurrentGroup.groupId + "' has " + members + " Wi-Fi Direct client(s)");
            mListener.onMembersChanged(members);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void start() {
        mActive = true;
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) {
                        mManager.requestConnectionInfo(mChannel, WifiDirectSwarm.this::onConnectionInfo);
                    } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())
                            && mRole == Role.SEEKING && mHandler.hasCallbacks(mEvaluate)) {
                        mHandler.removeCallbacks(mEvaluate);
                        mHandler.post(mEvaluate); // fresh results: decide now
                    }
                }
            };
            IntentFilter filter = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            mContext.registerReceiver(mReceiver, filter);
        }
    }

    public void stop() {
        mActive = false;
        mRole = Role.IDLE;
        mHandler.removeCallbacksAndMessages(null);
        if (mReceiver != null) {
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
            mReceiver = null;
        }
        if (isSupported()) {
            mManager.cancelConnect(mChannel, null);
            mManager.removeGroup(mChannel, null);
        }
    }

    /** Runs {@code next} whether the framework call succeeded or not (cleanup calls may "fail"). */
    private WifiP2pManager.ActionListener after(Runnable next) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (mActive) {
                    next.run();
                }
            }

            @Override
            public void onFailure(int reason) {
                if (mActive) {
                    next.run();
                }
            }
        };
    }
}
