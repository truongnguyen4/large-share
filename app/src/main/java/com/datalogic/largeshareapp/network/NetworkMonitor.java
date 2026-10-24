package com.datalogic.largeshareapp.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;

/**
 * Watches the default network so the app can survive Wi-Fi loss and access-point roaming. Short
 * flaps are debounced; when the network returns (possibly with a new IP address after roaming) the
 * listener is told whether the address changed, which means discovery and registration must be
 * redone because the old advertisement points at a dead address.
 */
public class NetworkMonitor {
    private static final String TAG = "NetworkMonitor";
    private static final long LOST_DEBOUNCE_MS = 1500;
    private static final long AVAILABLE_DEBOUNCE_MS = 800;

    public interface Listener {
        void onNetworkLost();

        void onNetworkAvailable(boolean addressChanged);
    }

    private final ConnectivityManager mConnectivity;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback mCallback;
    private volatile boolean mConnected = true;
    private volatile boolean mLostReported;
    private volatile String mAddress;
    private Network mCurrent;

    public NetworkMonitor(Context context, Listener listener) {
        mConnectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mListener = listener;
    }

    public synchronized void start() {
        if (mCallback != null || mConnectivity == null) {
            return;
        }
        mCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mCurrent = network;
                mConnected = true;
                mHandler.postDelayed(MonitorRunnable.available(NetworkMonitor.this, network), AVAILABLE_DEBOUNCE_MS);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                if (network.equals(mCurrent)) {
                    mHandler.postDelayed(MonitorRunnable.available(NetworkMonitor.this, network), AVAILABLE_DEBOUNCE_MS);
                }
            }

            @Override
            public void onLost(Network network) {
                if (!network.equals(mCurrent)) {
                    return;
                }
                mCurrent = null;
                mConnected = false;
                mHandler.postDelayed(() -> {
                    if (!mConnected && !mLostReported) {
                        mLostReported = true;
                        Log.w(TAG, "Network lost");
                        mListener.onNetworkLost();
                    }
                }, LOST_DEBOUNCE_MS);
            }
        };
        try {
            mConnectivity.registerDefaultNetworkCallback(mCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot register network callback", e);
            mCallback = null;
        }
    }

    public synchronized void stop() {
        mHandler.removeCallbacksAndMessages(null);
        if (mCallback != null && mConnectivity != null) {
            try {
                mConnectivity.unregisterNetworkCallback(mCallback);
            } catch (RuntimeException ignored) {
                // already unregistered
            }
        }
        mCallback = null;
    }

    private void handleAvailable(Network network) {
        if (!network.equals(mCurrent)) {
            return;
        }
        String address = currentAddress(network);
        boolean changed = mAddress != null && address != null && !address.equals(mAddress);
        boolean recovered = mLostReported;
        if (address != null) {
            mAddress = address;
        }
        mLostReported = false;
        if (changed || recovered) {
            Log.i(TAG, "Network available again (recovered=" + recovered + ", addressChanged=" + changed
                    + ", address=" + address + ")");
            mListener.onNetworkAvailable(changed);
        }
    }

    private String currentAddress(Network network) {
        LinkProperties lp = mConnectivity.getLinkProperties(network);
        if (lp == null) {
            return null;
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) {
                return la.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private static final class MonitorRunnable {
        static Runnable available(NetworkMonitor monitor, Network network) {
            return () -> monitor.handleAvailable(network);
        }
    }
}
