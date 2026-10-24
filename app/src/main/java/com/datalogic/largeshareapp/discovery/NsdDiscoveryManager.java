package com.datalogic.largeshareapp.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Android NSD (DNS-SD) discovery with the multicast-storm mitigations of the disclosure and the
 * resilience a roaming fleet needs:
 * <ul>
 *   <li>role isolation through two service types (chunk sharing vs. root progress tracker);</li>
 *   <li>duplicate-discovery suppression: one active discovery per type, repeated "found" events for
 *       a peer that is already resolved or queued are ignored, and our own advertisement is filtered;</li>
 *   <li>a serial resolve queue (NSD allows one resolve at a time; parallel calls silently lose
 *       peers with FAILURE_ALREADY_ACTIVE) with retries;</li>
 *   <li>self-healing: failed discovery/registration is retried with back-off, and {@link #restart()}
 *       redoes everything after a network change (new IP address).</li>
 * </ul>
 */
public class NsdDiscoveryManager {
    private static final String TAG = "NsdDiscoveryManager";
    public static final String SERVICE_TYPE_SHARE = "_largeshare._tcp";
    public static final String SERVICE_TYPE_PROGRESS = "_largeprogress._tcp";

    public static final String SERVICE_NAME_PREFIX_SHARE = "LargeShare_";
    public static final String SERVICE_NAME_PREFIX_PROGRESS = "LargeProgress_";

    private static final int MAX_RESOLVE_ATTEMPTS = 4;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;

    public interface DiscoveryListener {
        void onPeerFound(String ip, int port, String serviceName, String serviceType,
                         Map<String, String> attributes);

        void onPeerLost(String serviceName, String serviceType);
    }

    private static final class Registration {
        final int port;
        final String type;
        final String prefix;
        final String uniqueId;
        final Map<String, String> attributes;
        NsdManager.RegistrationListener listener;
        boolean restartPending;
        int failures;

        Registration(int port, String type, String prefix, String uniqueId, Map<String, String> attributes) {
            this.port = port;
            this.type = type;
            this.prefix = prefix;
            this.uniqueId = uniqueId;
            this.attributes = attributes;
        }
    }

    private static final class ResolveRequest {
        final NsdServiceInfo info;
        final String type;
        final String key;
        int attempts;

        ResolveRequest(NsdServiceInfo info, String type, String key) {
            this.info = info;
            this.type = type;
            this.key = key;
        }
    }

    private final NsdManager nsdManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<DiscoveryListener> listeners = new CopyOnWriteArraySet<>();

    private final Map<String, Registration> registrations = new HashMap<>();
    private final Map<String, String> registeredNames = new HashMap<>();
    private final Map<String, NsdManager.DiscoveryListener> discoveryListeners = new HashMap<>();
    private final Set<String> wantedDiscovery = new HashSet<>();
    private final Map<String, Integer> discoveryFailures = new HashMap<>();

    private final ArrayDeque<ResolveRequest> resolveQueue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    private final Map<String, String> resolvedPeers = new HashMap<>();
    private boolean resolving;
    private boolean closed;

    public NsdDiscoveryManager(Context context) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void addDiscoveryListener(DiscoveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ------------------------------------------------------------------ registration

    /** Advertises a service. The registration is remembered so it can be redone after roaming. */
    public synchronized void registerService(int port, String serviceType, String serviceNamePrefix,
                                             String uniqueId, Map<String, String> attributes) {
        closed = false;
        Registration old = registrations.remove(serviceType);
        if (old != null) {
            stopRegistration(old);
        }
        Registration reg = new Registration(port, serviceType, serviceNamePrefix, uniqueId, attributes);
        registrations.put(serviceType, reg);
        doRegister(reg);
    }

    private void doRegister(Registration reg) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(reg.prefix + reg.uniqueId);
        serviceInfo.setServiceType(reg.type);
        serviceInfo.setPort(reg.port);
        if (reg.attributes != null) {
            for (Map.Entry<String, String> a : reg.attributes.entrySet()) {
                serviceInfo.setAttribute(a.getKey(), a.getValue());
            }
        }

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo sInfo, int errorCode) {
                Log.e(TAG, "NSD registration failed for " + reg.type + ": " + errorCode);
                synchronized (NsdDiscoveryManager.this) {
                    if (closed || registrations.get(reg.type) != reg) {
                        return;
                    }
                    reg.failures++;
                }
                handler.postDelayed(() -> {
                    synchronized (NsdDiscoveryManager.this) {
                        if (!closed && registrations.get(reg.type) == reg) {
                            doRegister(reg);
                        }
                    }
                }, retryDelay(reg.failures));
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo sInfo, int errorCode) {
                Log.e(TAG, "NSD unregistration failed for " + reg.type + ": " + errorCode);
                synchronized (NsdDiscoveryManager.this) {
                    resumeRestart(reg);
                }
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo sInfo) {
                synchronized (NsdDiscoveryManager.this) {
                    reg.failures = 0;
                    registeredNames.put(reg.type, sInfo.getServiceName());
                }
                Log.d(TAG, "NSD service " + reg.type + " registered as " + sInfo.getServiceName()
                        + " on port " + reg.port);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo sInfo) {
                synchronized (NsdDiscoveryManager.this) {
                    registeredNames.remove(reg.type);
                    resumeRestart(reg);
                }
                Log.d(TAG, "NSD service " + reg.type + " unregistered: " + sInfo.getServiceName());
            }
        };
        reg.listener = listener;
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error registering NSD service of type " + reg.type, e);
        }
    }

    /** Called when the previous registration is gone: publish the fresh one with the new address. */
    private void resumeRestart(Registration reg) {
        if (reg.restartPending && !closed && registrations.get(reg.type) == reg) {
            reg.restartPending = false;
            doRegister(reg);
        }
    }

    private void stopRegistration(Registration reg) {
        NsdManager.RegistrationListener listener = reg.listener;
        reg.listener = null;
        reg.restartPending = false;
        if (listener != null) {
            try {
                nsdManager.unregisterService(listener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Error unregistering NSD service type " + reg.type + ": " + e.getMessage());
            }
        }
        registeredNames.remove(reg.type);
    }

    public synchronized void unregisterService(String serviceType) {
        Registration reg = registrations.remove(serviceType);
        if (reg != null) {
            stopRegistration(reg);
        }
    }

    public synchronized void unregisterAllServices() {
        for (Registration reg : new ArrayList<>(registrations.values())) {
            unregisterService(reg.type);
        }
    }

    // ------------------------------------------------------------------ discovery

    /** Starts discovery of one service type. A second request for an active type is ignored. */
    public synchronized void startDiscovery(String serviceType) {
        closed = false;
        wantedDiscovery.add(serviceType);
        if (discoveryListeners.containsKey(serviceType)) {
            Log.d(TAG, "NSD discovery already active for " + serviceType + ", suppressing duplicate");
            return;
        }
        doStartDiscovery(serviceType);
    }

    private void doStartDiscovery(String serviceType) {
        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String sType, int errorCode) {
                Log.e(TAG, "NSD discovery start failed for " + serviceType + ": " + errorCode);
                int failures;
                synchronized (NsdDiscoveryManager.this) {
                    discoveryListeners.remove(serviceType);
                    if (closed || !wantedDiscovery.contains(serviceType)) {
                        return;
                    }
                    failures = discoveryFailures.merge(serviceType, 1, Integer::sum);
                }
                handler.postDelayed(() -> {
                    synchronized (NsdDiscoveryManager.this) {
                        if (!closed && wantedDiscovery.contains(serviceType)
                                && !discoveryListeners.containsKey(serviceType)) {
                            doStartDiscovery(serviceType);
                        }
                    }
                }, retryDelay(failures));
            }

            @Override
            public void onStopDiscoveryFailed(String sType, int errorCode) {
                Log.e(TAG, "NSD discovery stop failed for " + serviceType + ": " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String sType) {
                synchronized (NsdDiscoveryManager.this) {
                    discoveryFailures.remove(serviceType);
                }
                Log.d(TAG, "NSD discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String sType) {
                Log.d(TAG, "NSD discovery stopped for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                synchronized (NsdDiscoveryManager.this) {
                    if (name.equals(registeredNames.get(serviceType))) {
                        return; // our own advertisement
                    }
                }
                enqueueResolve(serviceInfo, serviceType);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "NSD service lost " + serviceType + ": " + name);
                synchronized (NsdDiscoveryManager.this) {
                    String key = serviceType + "|" + name;
                    resolvedPeers.remove(key);
                    queuedKeys.remove(key);
                }
                for (DiscoveryListener listener : listeners) {
                    listener.onPeerLost(name, serviceType);
                }
            }
        };

        discoveryListeners.put(serviceType, discoveryListener);
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error starting NSD discovery for " + serviceType, e);
            discoveryListeners.remove(serviceType);
        }
    }

    public synchronized void stopDiscovery(String serviceType) {
        wantedDiscovery.remove(serviceType);
        NsdManager.DiscoveryListener listener = discoveryListeners.remove(serviceType);
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Error stopping NSD discovery for " + serviceType + ": " + e.getMessage());
            }
        }
    }

    public synchronized void stopAllDiscoveries() {
        for (String type : new ArrayList<>(discoveryListeners.keySet())) {
            stopDiscovery(type);
        }
        wantedDiscovery.clear();
    }

    // ------------------------------------------------------------------ resolve queue

    private void enqueueResolve(NsdServiceInfo info, String type) {
        String key = type + "|" + info.getServiceName();
        synchronized (this) {
            if (resolvedPeers.containsKey(key) || queuedKeys.contains(key)) {
                return; // duplicate-discovery suppression
            }
            queuedKeys.add(key);
            resolveQueue.add(new ResolveRequest(info, type, key));
        }
        pumpResolve();
    }

    private void pumpResolve() {
        final ResolveRequest request;
        synchronized (this) {
            if (resolving || closed || resolveQueue.isEmpty()) {
                return;
            }
            request = resolveQueue.poll();
            resolving = true;
        }
        try {
            nsdManager.resolveService(request.info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo sInfo, int errorCode) {
                    Log.w(TAG, "Resolve failed (" + errorCode + ") for " + sInfo.getServiceName());
                    resolveFinished(request, false);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo sInfo) {
                    InetAddress host = sInfo.getHost();
                    String ip = host != null ? host.getHostAddress() : null;
                    if (ip == null) {
                        resolveFinished(request, false);
                        return;
                    }
                    Map<String, String> attributes = new HashMap<>();
                    for (Map.Entry<String, byte[]> a : sInfo.getAttributes().entrySet()) {
                        attributes.put(a.getKey(), a.getValue() == null ? ""
                                : new String(a.getValue(), StandardCharsets.UTF_8));
                    }
                    synchronized (NsdDiscoveryManager.this) {
                        resolvedPeers.put(request.key, ip + ":" + sInfo.getPort());
                    }
                    Log.d(TAG, "Resolved " + sInfo.getServiceName() + " at " + ip + ":" + sInfo.getPort());
                    for (DiscoveryListener listener : listeners) {
                        listener.onPeerFound(ip, sInfo.getPort(), sInfo.getServiceName(), request.type, attributes);
                    }
                    resolveFinished(request, true);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "resolveService threw", e);
            resolveFinished(request, false);
        }
    }

    private void resolveFinished(ResolveRequest request, boolean success) {
        boolean retry = false;
        synchronized (this) {
            resolving = false;
            if (success) {
                queuedKeys.remove(request.key);
            } else if (++request.attempts < MAX_RESOLVE_ATTEMPTS && !closed) {
                retry = true;
            } else {
                queuedKeys.remove(request.key);
            }
        }
        if (retry) {
            handler.postDelayed(() -> {
                synchronized (NsdDiscoveryManager.this) {
                    if (closed || !queuedKeys.contains(request.key)) {
                        return;
                    }
                    resolveQueue.add(request);
                }
                pumpResolve();
            }, 500L * request.attempts);
        }
        pumpResolve();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Redo discovery and advertisement from scratch. Used after the network changed (Wi-Fi
     * reconnect, access-point roaming), when old advertisements and cached addresses are stale.
     */
    public synchronized void restart() {
        Log.i(TAG, "Restarting NSD (registrations=" + registrations.size()
                + ", discoveries=" + wantedDiscovery.size() + ")");
        resolveQueue.clear();
        queuedKeys.clear();
        resolvedPeers.clear();
        resolving = false;
        discoveryFailures.clear();

        for (String type : new ArrayList<>(wantedDiscovery)) {
            NsdManager.DiscoveryListener listener = discoveryListeners.remove(type);
            if (listener != null) {
                try {
                    nsdManager.stopServiceDiscovery(listener);
                } catch (RuntimeException ignored) {
                    // already stopped
                }
            }
            doStartDiscovery(type);
        }
        for (Registration reg : new ArrayList<>(registrations.values())) {
            NsdManager.RegistrationListener listener = reg.listener;
            if (listener == null) {
                doRegister(reg);
                continue;
            }
            reg.restartPending = true;
            try {
                nsdManager.unregisterService(listener);
            } catch (RuntimeException e) {
                reg.restartPending = false;
                doRegister(reg);
            }
        }
    }

    public synchronized void cleanUp() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        resolveQueue.clear();
        queuedKeys.clear();
        resolvedPeers.clear();
        resolving = false;
        stopAllDiscoveries();
        unregisterAllServices();
        listeners.clear();
    }

    private static long retryDelay(int failures) {
        return Math.min(MAX_RETRY_DELAY_MS, 1000L << Math.min(Math.max(failures, 1), 5));
    }

    /** Peers currently resolved, for diagnostics. */
    public synchronized List<String> getResolvedPeers() {
        return new ArrayList<>(resolvedPeers.values());
    }
}
