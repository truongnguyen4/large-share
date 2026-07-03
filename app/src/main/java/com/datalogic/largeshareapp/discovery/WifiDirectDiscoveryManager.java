package com.datalogic.largeshareapp.discovery;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SuppressLint("MissingPermission")
public class WifiDirectDiscoveryManager {
    private static final String TAG = "WifiDirectManager";
    private static final int MAX_DEVICES_PER_GROUP = 6;

    private final Context context;
    private final WifiP2pManager p2pManager;
    private final WifiP2pManager.Channel channel;
    private final WifiDirectListener listener;

    private BroadcastReceiver receiver;
    private boolean isReceiverRegistered = false;
    private boolean isDiscovering = false;

    private List<WifiP2pDevice> discoveredPeers = new ArrayList<>();
    private WifiP2pInfo connectionInfo;
    private WifiP2pGroup activeGroup;

    public interface WifiDirectListener {
        void onPeersChanged(List<WifiP2pDevice> peers);
        void onConnectionChanged(WifiP2pInfo info, WifiP2pGroup group);
    }

    public WifiDirectDiscoveryManager(Context context, WifiDirectListener listener) {
        this.context = context;
        this.listener = listener;
        this.p2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel = p2pManager.initialize(context, Looper.getMainLooper(), null);
    }

    public void registerReceiver() {
        if (isReceiverRegistered) return;

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    Log.d(TAG, "WiFi P2P peers changed.");
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    Log.d(TAG, "WiFi P2P connection changed. Connected status: " + (networkInfo != null && networkInfo.isConnected()));
                    
                    if (networkInfo != null && networkInfo.isConnected()) {
                        requestConnectionInfo();
                    } else {
                        connectionInfo = null;
                        activeGroup = null;
                        if (listener != null) {
                            listener.onConnectionChanged(null, null);
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        context.registerReceiver(receiver, filter);
        isReceiverRegistered = true;
    }

    public void unregisterReceiver() {
        if (isReceiverRegistered && receiver != null) {
            context.unregisterReceiver(receiver);
            isReceiverRegistered = false;
        }
    }

    public void startPeerDiscovery() {
        if (isDiscovering) return;
        registerReceiver();
        p2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                isDiscovering = true;
                Log.d(TAG, "WiFi Direct Peer discovery started successful.");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "WiFi Direct Peer discovery start failed: " + reason);
            }
        });
    }

    public void stopPeerDiscovery() {
        if (!isDiscovering) return;
        p2pManager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                isDiscovering = false;
                Log.d(TAG, "WiFi Direct Peer discovery stopped.");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "WiFi Direct Peer discovery stop failed: " + reason);
            }
        });
    }

    public void createGroup() {
        p2pManager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "WiFi Direct Group created successfully.");
                requestConnectionInfo();
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "WiFi Direct Group creation failed: " + reason);
            }
        });
    }

    public void removeGroup() {
        p2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "WiFi Direct Group removed successfully.");
                connectionInfo = null;
                activeGroup = null;
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "WiFi Direct Group removal failed: " + reason);
            }
        });
    }

    public void connect(WifiP2pDevice device) {
        if (device == null) return;
        
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        
        // Root device would set groupOwnerIntent to 15 (always GO)
        // Seeker would set to 0 (always Client)
        config.groupOwnerIntent = 0;

        p2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connection initiation successful to: " + device.deviceName);
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Connection failed to: " + device.deviceName + " with reason: " + reason);
            }
        });
    }

    public void disconnect() {
        p2pManager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Cancelled any pending WiFi direct connection attempts.");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed cancelling pending WiFi direct connections: " + reason);
            }
        });
        removeGroup();
    }

    private void requestPeers() {
        p2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peerList) {
                Collection<WifiP2pDevice> peers = peerList.getDeviceList();
                discoveredPeers = new ArrayList<>(peers);
                Log.d(TAG, "Discovered peers count: " + discoveredPeers.size());
                if (listener != null) {
                    listener.onPeersChanged(discoveredPeers);
                }
            }
        });
    }

    private void requestConnectionInfo() {
        p2pManager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                connectionInfo = info;
                Log.d(TAG, "Connection info available. Group Formed: " + info.groupFormed + ", Is GO: " + info.isGroupOwner);
                
                if (info.groupFormed) {
                    p2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                        @Override
                        public void onGroupInfoAvailable(WifiP2pGroup group) {
                            activeGroup = group;
                            if (group != null) {
                                Log.d(TAG, "Group direct info retrieved. Members connected count: " + group.getClientList().size());
                            }
                            if (listener != null) {
                                listener.onConnectionChanged(info, group);
                            }
                        }
                    });
                } else {
                    if (listener != null) {
                        listener.onConnectionChanged(info, null);
                    }
                }
            }
        });
    }

    public List<WifiP2pDevice> getDiscoveredPeers() {
        return discoveredPeers;
    }

    public WifiP2pInfo getConnectionInfo() {
        return connectionInfo;
    }

    public WifiP2pGroup getActiveGroup() {
        return activeGroup;
    }

    public void cleanUp() {
        unregisterReceiver();
        stopPeerDiscovery();
        removeGroup();
    }
}
