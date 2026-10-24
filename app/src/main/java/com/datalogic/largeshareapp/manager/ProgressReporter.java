package com.datalogic.largeshareapp.manager;

import android.util.Log;

import com.datalogic.largeshareapp.model.Peer;
import com.datalogic.largeshareapp.network.HttpClient;
import com.datalogic.largeshareapp.network.PeerException;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reports this device's download progress to the root/tracker.
 *
 * <p>Reports are coalesced (only the latest value is ever sent) and rate-limited with jitter, so
 * thousands of devices do not flood the root. A failed report is retried with back-off and, above
 * all, the final "100%" report is retried until it is delivered, even after the download itself
 * has finished and the network layer is being torn down.
 */
public class ProgressReporter {
    private static final String TAG = "ProgressReporter";
    private static final long MAX_BACKOFF_MS = 15_000L;

    private final HttpClient mClient;
    private final String mDeviceId;
    private final long mMinIntervalMs;
    private final Random mRandom = new Random();
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "progress-reporter");
        t.setDaemon(true);
        return t;
    });

    private volatile Peer mRoot;
    private int mPendingCompleted = -1;
    private int mPendingTotal;
    private int mSentCompleted = -1;
    private long mLastSendAt;
    private long mNextAttemptAt;
    private int mFailures;
    private long mCloseDeadline = Long.MAX_VALUE;
    private long mJitterMs;

    public ProgressReporter(HttpClient client, String deviceId, long minIntervalMs) {
        mClient = client;
        mDeviceId = deviceId;
        mMinIntervalMs = minIntervalMs;
        mJitterMs = jitter();
        mExecutor.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                Log.e(TAG, "tick failed", e);
            }
        }, 0, 400, TimeUnit.MILLISECONDS);
    }

    private long jitter() {
        return (long) (mMinIntervalMs * (mRandom.nextDouble() * 0.6 - 0.3));
    }

    public void setRoot(Peer root) {
        mRoot = root;
        synchronized (this) {
            mNextAttemptAt = 0;
            mFailures = 0;
            mSentCompleted = -1; // a (re)discovered root has no memory of us: tell it again
        }
    }

    public void clearRoot(String serviceName) {
        Peer root = mRoot;
        if (root != null && serviceName.equals(root.serviceName)) {
            mRoot = null;
        }
    }

    public synchronized void update(int completed, int total) {
        mPendingCompleted = completed;
        mPendingTotal = total;
    }

    /** Allows an immediate attempt, e.g. right after the network came back. */
    public synchronized void kick() {
        mNextAttemptAt = 0;
        mFailures = 0;
    }

    /**
     * Stops reporting once the latest value has been delivered or after {@code graceMs}, whichever
     * comes first. Does not block the caller.
     */
    public synchronized void closeAfterDelivery(long graceMs) {
        mCloseDeadline = System.currentTimeMillis() + graceMs;
    }

    public void closeNow() {
        mExecutor.shutdownNow();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int completed;
        int total;
        synchronized (this) {
            completed = mPendingCompleted;
            total = mPendingTotal;
            boolean delivered = completed < 0 || completed == mSentCompleted;
            if (mCloseDeadline != Long.MAX_VALUE && (delivered || now > mCloseDeadline)) {
                mExecutor.shutdown();
                return;
            }
            if (delivered || now < mNextAttemptAt) {
                return;
            }
            boolean isFinal = total > 0 && completed >= total;
            if (!isFinal && now - mLastSendAt < mMinIntervalMs + mJitterMs) {
                return;
            }
        }
        Peer root = mRoot;
        if (root == null) {
            return;
        }
        try {
            mClient.reportProgress(root, mDeviceId, completed, total);
            synchronized (this) {
                mSentCompleted = completed;
                mLastSendAt = now;
                mFailures = 0;
                mJitterMs = jitter();
            }
        } catch (PeerException e) {
            synchronized (this) {
                mFailures++;
                mNextAttemptAt = now + Math.min(MAX_BACKOFF_MS, 1000L << Math.min(mFailures, 5));
            }
            Log.w(TAG, "Progress report failed (" + e.kind + "), will retry: " + e.getMessage());
        }
    }
}
