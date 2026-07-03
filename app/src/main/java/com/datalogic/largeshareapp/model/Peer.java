package com.datalogic.largeshareapp.model;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

public class Peer {
    public final String ip;
    public final int port;
    public final String serviceName;
    public final String serviceType;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    public Peer(String ip, int port, String serviceName, String serviceType) {
        this.ip = ip;
        this.port = port;
        this.serviceName = serviceName;
        this.serviceType = serviceType;
    }

    public void disconnect() {
        isConnected.set(false);
    }
    public void connect() {
        isConnected.set(true);
    }
    public boolean isConnected() {
        return isConnected.get();
    }

    @NonNull
    @Override
    public String toString() {
        return "Peer{" +
                "ip='" + ip + '\'' +
                ", port=" + port +
                ", serviceName='" + serviceName + '\'' +
                ", serviceType='" + serviceType + '\'' +
                '}';
    }

}
