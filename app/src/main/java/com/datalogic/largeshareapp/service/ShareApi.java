package com.datalogic.largeshareapp.service;

import android.net.Uri;

/**
 * The only surface the UI uses to drive a share session. Every call is made on the main thread;
 * state changes come back as {@link SessionSnapshot}s through {@link Listener}.
 */
public interface ShareApi {

    interface Listener {
        /** Called on the main thread, at most a few times per second. */
        void onSnapshot(SessionSnapshot snapshot);
    }

    /** Registers a listener and immediately delivers the current snapshot to it. */
    void addListener(Listener listener);

    void removeListener(Listener listener);

    SessionSnapshot getSnapshot();

    /** Resolves a picked document and builds its chunk / SHA-256 manifest in the background. */
    void prepareFile(Uri uri);

    /** Same as {@link #prepareFile(Uri)} for a plain file path. */
    void prepareFile(String path);

    /**
     * Starts a session and moves the service to the foreground so it keeps running when the UI
     * is gone. Returns false (and explains why in the snapshot's message) when refused.
     */
    boolean start(SessionConfig config);

    /** Stops the session and leaves the foreground. */
    void stop();

    /** The fleet key last used (to prefill the UI). */
    String getFleetKey();
}
