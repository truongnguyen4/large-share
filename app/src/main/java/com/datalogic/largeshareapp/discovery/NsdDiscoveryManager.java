package com.datalogic.largeshareapp.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class NsdDiscoveryManager {
    private static final String TAG = "NsdDiscoveryManager";
    public static final String SERVICE_TYPE_SHARE = "_largeshare._tcp";
    public static final String SERVICE_TYPE_PROGRESS = "_largeprogress._tcp";

    public static final String SERVICE_NAME_PREFIX_SHARE = "LargeShare_";
    public static final String SERVICE_NAME_PREFIX_PROGRESS = "LargeProgress_";

    private final NsdManager nsdManager;
    private final Set<DiscoveryListener> listeners = new CopyOnWriteArraySet<>();
    
    private final Map<String, NsdManager.RegistrationListener> registrationListeners = new ConcurrentHashMap<>();
    private final Map<String, NsdManager.DiscoveryListener> discoveryListeners = new ConcurrentHashMap<>();
    private final Map<String, String> registeredNames = new ConcurrentHashMap<>();

    public void addDiscoveryListener(DiscoveryListener listener) {
        if (listener == null) {
            Log.w(TAG, "Attempted to add a null DiscoveryListener. Ignoring.");
            return;
        }
        listeners.add(listener);
    }

    public interface DiscoveryListener {
        void onPeerFound(String ip, int port, String serviceName, String serviceType);
        void onPeerLost(String serviceName, String serviceType);
    }

    public NsdDiscoveryManager(Context context) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    /**
     * Register a custom NSD service type with a specific suffix.
     */
    public synchronized void registerService(int port, String serviceType, String serviceNamePrefix, String uniqueId) {
        if (registrationListeners.containsKey(serviceType)) {
            Log.d(TAG, "NSD service of type " + serviceType + " already registered. Unregistering first.");
            unregisterService(serviceType);
        }

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceNamePrefix + uniqueId);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setPort(port);

        NsdManager.RegistrationListener registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo sInfo, int errorCode) {
                Log.e(TAG, "NSD Registration failed for type " + serviceType + ": " + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo sInfo, int errorCode) {
                Log.e(TAG, "NSD Unregistration failed for type " + serviceType + ": " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo sInfo) {
                registeredNames.put(serviceType, sInfo.getServiceName());
                Log.d(TAG, "NSD Service (" + serviceType + ") registered successfully: " + sInfo.getServiceName() + " on port " + port);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo sInfo) {
                registeredNames.remove(serviceType);
                Log.d(TAG, "NSD Service (" + serviceType + ") unregistered successfully: " + sInfo.getServiceName());
            }
        };

        registrationListeners.put(serviceType, registrationListener);

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            Log.e(TAG, "Error registering NSD service of type " + serviceType, e);
        }
    }

    /**
     * Unregister a custom NSD service by its service type.
     */
    public synchronized void unregisterService(String serviceType) {
        NsdManager.RegistrationListener oldListener = registrationListeners.remove(serviceType);
        if (oldListener != null) {
            try {
                nsdManager.unregisterService(oldListener);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering NSD service type " + serviceType, e);
            }
            registeredNames.remove(serviceType);
        }
    }

    /**
     * Unregister all registered services.
     */
    public synchronized void unregisterAllServices() {
        for (String serviceType : registrationListeners.keySet()) {
            unregisterService(serviceType);
        }
    }

    /**
     * Start discovery for a specific NSD service type.
     */
    public synchronized void startDiscovery(String serviceType) {
        if (discoveryListeners.containsKey(serviceType)) {
            Log.d(TAG, "NSD Discovery already active for type: " + serviceType);
            return;
        }

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String sType, int errorCode) {
                Log.e(TAG, "NSD Discovery start failed for type " + serviceType + ": " + errorCode);
                stopDiscovery(serviceType);
            }

            @Override
            public void onStopDiscoveryFailed(String sType, int errorCode) {
                Log.e(TAG, "NSD Discovery stop failed for type " + serviceType + ": " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String sType) {
                Log.d(TAG, "NSD Discovery started for type " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String sType) {
                Log.d(TAG, "NSD Discovery stopped for type " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String registeredName = registeredNames.get(serviceType);
                if (registeredName != null && serviceInfo.getServiceName().equals(registeredName)) {
                    Log.d(TAG, "Found self, ignoring: " + registeredName);
                    return;
                }

                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo sInfo, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode + " for " + sInfo.getServiceName());
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo sInfo) {
                        InetAddress host = sInfo.getHost();
                        String ip = host.getHostAddress();
                        int port = sInfo.getPort();
                        
                        if (ip != null) {
                            Log.d(TAG, "Resolved NSD Service: " + sInfo.getServiceName() + " at " + ip + ":" + port);
                            for (DiscoveryListener listener : listeners) {
                                listener.onPeerFound(ip, port, sInfo.getServiceName(), serviceType);
                            }
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.e(TAG, "NSD Service lost of type " + serviceType + ": " + serviceInfo.getServiceName());
                for (DiscoveryListener listener : listeners) {
                    listener.onPeerLost(serviceInfo.getServiceName(), serviceType);
                }
            }
        };

        discoveryListeners.put(serviceType, discoveryListener);

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Error starting NSD discovery for service type " + serviceType, e);
        }
    }

    /**
     * Stop discovery for a specific NSD service type.
     */
    public synchronized void stopDiscovery(String serviceType) {
        NsdManager.DiscoveryListener oldDiscoveryListener = discoveryListeners.remove(serviceType);
        if (oldDiscoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(oldDiscoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping NSD discovery for type " + serviceType, e);
            }
        }
    }

    /**
     * Stop all active service discoveries.
     */
    public synchronized void stopAllDiscoveries() {
        for (String serviceType : discoveryListeners.keySet()) {
            stopDiscovery(serviceType);
        }
    }

    public void cleanUp() {
        unregisterAllServices();
        stopAllDiscoveries();
    }
}
